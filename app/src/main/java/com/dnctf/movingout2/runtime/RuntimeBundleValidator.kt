package com.dnctf.movingout2.runtime
import java.io.File
import java.io.FileInputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Properties

enum class RuntimeBundleStatus { NOT_FOUND, INVALID_MANIFEST, INCOMPLETE, READY }
data class RuntimeBundleReport(val status:RuntimeBundleStatus,val root:File?,val box64:File?,val wine64:File?,val wineserver:File?,val libraryDirectory:File?,val blockers:List<String>,val manifestVersion:Int?=null){val isReady:Boolean get()=status==RuntimeBundleStatus.READY}
class RuntimeBundleValidator {
 fun validate(root:File):RuntimeBundleReport {
  if(!root.exists()||!root.isDirectory)return report(RuntimeBundleStatus.NOT_FOUND,null,listOf("Runtime bundle directory is not present."))
  val canonical=runCatching{root.canonicalFile}.getOrNull()?:return report(RuntimeBundleStatus.INVALID_MANIFEST,root,listOf("Unable to resolve bundle root."))
  if(File(canonical,"runtime-manifest.json").exists()){
   val parsed=RuntimeManifestParser().validate(canonical)
   val map=parsed.componentFiles
   val box=map["box64"];val wine=map["wine64"];val server=map["wineserver"];val libs=map["wineLib"]
   val blockers=parsed.errors.toMutableList()
   for ((key, file) in listOf("box64" to box, "wine64" to wine, "wineserver" to server)) {
    if (file != null) {
     if (!file.canExecute()) blockers += "Executable $key is not executable."
     // The JSON manifest checksum proves identity, not target architecture. All
     // Linux runtime entry points must be ELF64 little-endian AArch64 for this app.
     if (!isArm64Elf(file)) blockers += "Executable $key is not ELF AArch64."
    }
   }
   if(box==null||wine==null||server==null||libs==null)blockers+="Required runtime files/directories unresolved."
   val ready=blockers.isEmpty()
   return RuntimeBundleReport(if(ready)RuntimeBundleStatus.READY else RuntimeBundleStatus.INCOMPLETE,canonical,box,wine,server,libs,blockers.distinct(),1)
  }
  val manifest=File(canonical,"runtime.properties")
  if(!manifest.isFile||!manifest.canRead())return report(RuntimeBundleStatus.INVALID_MANIFEST,canonical,listOf("runtime.properties missing or unreadable."))
  val p=runCatching{Properties().apply{FileInputStream(manifest).use(::load)}}.getOrElse{return report(RuntimeBundleStatus.INVALID_MANIFEST,canonical,listOf("Manifest parse failed: ${it.message}"))}
  if(p.getProperty("format_version")?.toIntOrNull()!=1)return report(RuntimeBundleStatus.INVALID_MANIFEST,canonical,listOf("Unsupported format_version; expected 1."))
  if(p.getProperty("target_abi")!="arm64-v8a")return report(RuntimeBundleStatus.INVALID_MANIFEST,canonical,listOf("target_abi must be arm64-v8a."))
  val blockers=mutableListOf<String>()
  fun resolve(key:String):File?{val v=p.getProperty(key)?.trim().orEmpty();if(v.isBlank()){blockers+="Manifest key '$key' empty.";return null};val f=runCatching{File(canonical,v).canonicalFile}.getOrNull();if(f==null||!f.toPath().startsWith(canonical.toPath())){blockers+="Manifest path '$key' escapes bundle.";return null};if(!f.isFile||f.length()<=0||!f.canRead()){blockers+="Required '$key' missing/empty/unreadable.";return null};if(!f.canExecute())blockers+="Executable '$key' not executable.";if(!isArm64Elf(f))blockers+="Executable '$key' is not ELF AArch64.";return f}
  val box=resolve("box64");val wine=resolve("wine64");val server=resolve("wineserver")
  val lp=p.getProperty("library_dir")?.trim().orEmpty();val libs=if(lp.isBlank()){blockers+="library_dir empty.";null}else runCatching{File(canonical,lp).canonicalFile}.getOrNull()?.also{if(!it.toPath().startsWith(canonical.toPath())||!it.isDirectory||it.listFiles().isNullOrEmpty())blockers+="library_dir invalid."}
  return RuntimeBundleReport(if(blockers.isEmpty())RuntimeBundleStatus.READY else RuntimeBundleStatus.INCOMPLETE,canonical,box,wine,server,libs,blockers.distinct(),1)
 }
 private fun isArm64Elf(file: File): Boolean = runCatching {
  RandomAccessFile(file, "r").use { input ->
   val fileLength = input.length()
   if (fileLength < ELF64_HEADER_SIZE) return@use false

   val headerBytes = ByteArray(ELF64_HEADER_SIZE.toInt())
   input.readFully(headerBytes)
   val header = ByteBuffer.wrap(headerBytes).order(ByteOrder.LITTLE_ENDIAN)
   val ident = headerBytes
   if (ident[0] != 0x7f.toByte() ||
       ident[1] != 'E'.code.toByte() ||
       ident[2] != 'L'.code.toByte() ||
       ident[3] != 'F'.code.toByte() ||
       ident[4] != ELFCLASS64 ||
       ident[5] != ELFDATA2LSB ||
       ident[6] != EV_CURRENT.toByte()) return@use false

   val type = header.getShort(16).toInt() and 0xffff
   val machine = header.getShort(18).toInt() and 0xffff
   val version = header.getInt(20).toLong() and 0xffff_ffffL
   val entryPoint = header.getLong(24)
   val programHeaderOffset = header.getLong(32)
   val headerSize = header.getShort(52).toInt() and 0xffff
   val programHeaderSize = header.getShort(54).toInt() and 0xffff
   val programHeaderCount = header.getShort(56).toInt() and 0xffff

   if ((type != ET_EXEC && type != ET_DYN) ||
       machine != EM_AARCH64 ||
       version != EV_CURRENT.toLong() ||
       entryPoint <= 0L ||
       headerSize != ELF64_HEADER_SIZE.toInt() ||
       programHeaderSize != ELF64_PROGRAM_HEADER_SIZE.toInt() ||
       programHeaderCount !in 1..MAX_PROGRAM_HEADERS ||
       programHeaderOffset < headerSize ||
       programHeaderOffset > fileLength) return@use false

   val tableSize = programHeaderSize.toLong() * programHeaderCount.toLong()
   if (tableSize > fileLength - programHeaderOffset) return@use false

   var hasExecutableLoadSegment = false
   var entryPointInExecutableSegment = false
   val programHeaderBytes = ByteArray(ELF64_PROGRAM_HEADER_SIZE.toInt())

   for (index in 0 until programHeaderCount) {
    val offset = programHeaderOffset + index.toLong() * programHeaderSize
    input.seek(offset)
    input.readFully(programHeaderBytes)
    val programHeader = ByteBuffer.wrap(programHeaderBytes).order(ByteOrder.LITTLE_ENDIAN)
    val segmentType = programHeader.getInt(0)
    if (segmentType != PT_LOAD) continue

    val flags = programHeader.getInt(4)
    val fileOffset = programHeader.getLong(8)
    val virtualAddress = programHeader.getLong(16)
    val fileSize = programHeader.getLong(32)
    val memorySize = programHeader.getLong(40)

    if (fileOffset < 0L ||
        virtualAddress < 0L ||
        fileSize < 0L ||
        memorySize <= 0L ||
        memorySize < fileSize ||
        fileOffset > fileLength ||
        fileSize > fileLength - fileOffset ||
        virtualAddress > Long.MAX_VALUE - memorySize) return@use false

    if ((flags and PF_X) != 0) {
     hasExecutableLoadSegment = true
     val segmentEnd = virtualAddress + memorySize
     if (entryPoint >= virtualAddress && entryPoint < segmentEnd) {
      entryPointInExecutableSegment = true
     }
    }
   }

   hasExecutableLoadSegment && entryPointInExecutableSegment
  }
 }.getOrDefault(false)

 private companion object {
  const val ELF64_HEADER_SIZE = 64L
  const val ELF64_PROGRAM_HEADER_SIZE = 56L
  const val MAX_PROGRAM_HEADERS = 4096
  const val ELFCLASS64: Byte = 2
  const val ELFDATA2LSB: Byte = 1
  const val EV_CURRENT = 1
  const val ET_EXEC = 2
  const val ET_DYN = 3
  const val EM_AARCH64 = 183
  const val PT_LOAD = 1
  const val PF_X = 1
 }
 private fun report(s:RuntimeBundleStatus,r:File?,b:List<String>)=RuntimeBundleReport(s,r,null,null,null,null,b)
}