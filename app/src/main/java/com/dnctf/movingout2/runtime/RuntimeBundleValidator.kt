package com.dnctf.movingout2.runtime
import java.io.File
import java.io.FileInputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Properties
import java.security.MessageDigest

enum class RuntimeBundleStatus { NOT_FOUND, INVALID_MANIFEST, INCOMPLETE, READY }
data class RuntimeBundleReport(val status:RuntimeBundleStatus,val root:File?,val box64:File?,val wine64:File?,val wineserver:File?,val libraryDirectory:File?,val blockers:List<String>,val manifestVersion:Int?=null,val guestGlibcDirectory:File?=null){val isReady:Boolean get()=status==RuntimeBundleStatus.READY}
class RuntimeBundleValidator {
 fun resolveBox64ForExecution(
  bundleBox64: File,
  nativeLibraryDirectory: File,
  allowBundleFallback: Boolean = false
 ): File? {
  val packaged = File(nativeLibraryDirectory, "libbox64.so")
  if (packaged.isFile && packaged.canExecute() &&
      bundleBox64.isFile && bundleBox64.canExecute() &&
      RuntimeBinaryInspector.matches(packaged, RuntimeBinaryInspector.ELF64_AARCH64) &&
      sha256(packaged) == sha256(bundleBox64)) return packaged
  return if (allowBundleFallback && bundleBox64.isFile && bundleBox64.canExecute()) bundleBox64 else null
 }

 private fun sha256(file: File): String {
  val digest = MessageDigest.getInstance("SHA-256")
  file.inputStream().use { input ->
   val buffer = ByteArray(64 * 1024)
   while (true) { val count = input.read(buffer); if (count < 0) break; digest.update(buffer, 0, count) }
  }
  return digest.digest().joinToString("") { "%02x".format(it) }
 }
 fun validate(root:File):RuntimeBundleReport {
  if(!root.exists()||!root.isDirectory)return report(RuntimeBundleStatus.NOT_FOUND,null,listOf("Runtime bundle directory is not present."))
  val canonical=runCatching{root.canonicalFile}.getOrNull()?:return report(RuntimeBundleStatus.INVALID_MANIFEST,root,listOf("Unable to resolve bundle root."))
  if(File(canonical,"runtime-manifest.json").exists()){
   val parsed=RuntimeManifestParser().validate(canonical)
   val map=parsed.componentFiles
   val box=map["box64"];val wine=map["wine64"];val server=map["wineserver"];val libs=map["wineLib"];val glibc=map["guestGlibc"]
   val blockers=parsed.errors.toMutableList()
   fun requireFormat(key: String, file: File?, format: String, executable: Boolean = true) {
    if (file == null) return
    if (executable && !file.canExecute()) blockers += "Executable $key is not executable."
    if (!RuntimeBinaryInspector.matches(file, format)) blockers += "Executable $key does not match declared role format $format."
   }
   requireFormat("box64", box, RuntimeBinaryInspector.ELF64_AARCH64)
   requireFormat("wine64", wine, RuntimeBinaryInspector.ELF64_X86_64)
   requireFormat("wineserver", server, RuntimeBinaryInspector.ELF64_X86_64)
   requireFormat("d3d11", map["d3d11"], RuntimeBinaryInspector.PE32_PLUS_X86_64, executable = false)
   requireFormat("dxgi", map["dxgi"], RuntimeBinaryInspector.PE32_PLUS_X86_64, executable = false)
   requireFormat("xinput1_3", map["xinput1_3"], RuntimeBinaryInspector.PE32_PLUS_X86_64, executable = false)
   requireFormat("xinput1_4", map["xinput1_4"], RuntimeBinaryInspector.PE32_PLUS_X86_64, executable = false)
   requireFormat("xinput9_1_0", map["xinput9_1_0"], RuntimeBinaryInspector.PE32_PLUS_X86_64, executable = false)
   requireFormat("vcruntime140", map["vcruntime140"], RuntimeBinaryInspector.PE32_PLUS_X86_64, executable = false)
   requireFormat("vcruntime140_1", map["vcruntime140_1"], RuntimeBinaryInspector.PE32_PLUS_X86_64, executable = false)
   requireFormat("msvcp140", map["msvcp140"], RuntimeBinaryInspector.PE32_PLUS_X86_64, executable = false)
   map["turnipSo"]?.let { requireFormat("turnipSo", it, RuntimeBinaryInspector.ELF64_AARCH64_SHARED, executable = false) }
   if (glibc != null) {
    val libc = File(glibc, "libc.so.6")
    if (!libc.isFile || libc.length() <= 0L) blockers += "Guest glibc libc.so.6 is missing or empty."
    val guestGlibcObjects = glibc.walkTopDown().filter {
     it.isFile && (it.name.endsWith(".so", true) || it.name.contains(".so.", true))
    }.toList()
    if (guestGlibcObjects.isEmpty()) blockers += "Guest glibc directory contains no shared ELF objects."
    guestGlibcObjects.forEach { library ->
     if (!RuntimeBinaryInspector.matches(library, RuntimeBinaryInspector.ELF64_X86_64_SHARED)) {
      blockers += "Guest glibc library is not ELF64 x86-64 shared: ${library.relativeTo(canonical).invariantSeparatorsPath}"
     }
    }
   }
   if (libs != null) {
    val guestLibraries = libs.walkTopDown().filter { it.isFile && (it.name.endsWith(".so", true) || it.name.contains(".so.", true)) }.toList()
    if (guestLibraries.isEmpty()) blockers += "Wine guest library directory contains no shared ELF objects."
    guestLibraries.forEach { library ->
     if (!RuntimeBinaryInspector.matches(library, RuntimeBinaryInspector.ELF64_X86_64_SHARED)) {
      blockers += "Guest Wine library is not ELF64 x86-64: ${library.relativeTo(canonical).invariantSeparatorsPath}"
     }
    }
   }
   if (wine?.parentFile?.isDirectory == true) {
    wine.parentFile.listFiles()?.filter { it.isFile && it.canExecute() }?.forEach { guestBinary ->
     if (!RuntimeBinaryInspector.matches(guestBinary, RuntimeBinaryInspector.ELF64_X86_64)) {
      blockers += "Executable in guest Wine bin directory is not ELF64 x86-64: ${guestBinary.name}"
     }
    }
   }
   if(box==null||wine==null||server==null||libs==null||glibc==null)blockers+="Required runtime files/directories unresolved."
   val ready=blockers.isEmpty()
   return RuntimeBundleReport(if(ready)RuntimeBundleStatus.READY else RuntimeBundleStatus.INCOMPLETE,canonical,box,wine,server,libs,blockers.distinct(),2,glibc)
  }
  val manifest=File(canonical,"runtime.properties")
  if(!manifest.isFile||!manifest.canRead())return report(RuntimeBundleStatus.INVALID_MANIFEST,canonical,listOf("runtime.properties missing or unreadable."))
  val p=runCatching{Properties().apply{FileInputStream(manifest).use(::load)}}.getOrElse{return report(RuntimeBundleStatus.INVALID_MANIFEST,canonical,listOf("Manifest parse failed: ${it.message}"))}
  if(p.getProperty("format_version")?.toIntOrNull()!=1)return report(RuntimeBundleStatus.INVALID_MANIFEST,canonical,listOf("Unsupported format_version; expected 1."))
  if(p.getProperty("target_abi")!="arm64-v8a")return report(RuntimeBundleStatus.INVALID_MANIFEST,canonical,listOf("target_abi must be arm64-v8a."))
  val blockers=mutableListOf<String>()
  fun resolve(key:String):File?{val v=p.getProperty(key)?.trim().orEmpty();if(v.isBlank()){blockers+="Manifest key '$key' empty.";return null};val f=runCatching{File(canonical,v).canonicalFile}.getOrNull();if(f==null||!f.toPath().startsWith(canonical.toPath())){blockers+="Manifest path '$key' escapes bundle.";return null};if(!f.isFile||f.length()<=0||!f.canRead()){blockers+="Required '$key' missing/empty/unreadable.";return null};if(!f.canExecute())blockers+="Executable '$key' not executable.";val expected = if (key == "box64") RuntimeBinaryInspector.ELF64_AARCH64 else RuntimeBinaryInspector.ELF64_X86_64
   if (!RuntimeBinaryInspector.matches(f, expected)) blockers += "Executable " + key + " does not match " + expected + ".";return f}
  val box=resolve("box64");val wine=resolve("wine64");val server=resolve("wineserver")
  val lp=p.getProperty("library_dir")?.trim().orEmpty();val libs=if(lp.isBlank()){blockers+="library_dir empty.";null}else runCatching{File(canonical,lp).canonicalFile}.getOrNull()?.also{if(!it.toPath().startsWith(canonical.toPath())||!it.isDirectory||it.listFiles().isNullOrEmpty())blockers+="library_dir invalid."}
  return RuntimeBundleReport(if(blockers.isEmpty())RuntimeBundleStatus.READY else RuntimeBundleStatus.INCOMPLETE,canonical,box,wine,server,libs,blockers.distinct(),1)
 }
 private fun report(s:RuntimeBundleStatus,r:File?,b:List<String>)=RuntimeBundleReport(s,r,null,null,null,null,b)
}