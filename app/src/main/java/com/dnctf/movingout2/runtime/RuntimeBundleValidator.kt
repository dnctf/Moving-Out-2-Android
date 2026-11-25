package com.dnctf.movingout2.runtime
import java.io.File
import java.io.FileInputStream
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
 private fun isArm64Elf(f:File)=runCatching{FileInputStream(f).use{i->val b=ByteArray(20);var n=0;while(n<b.size){val r=i.read(b,n,b.size-n);if(r<=0)return@use false;n+=r};b[0]==0x7f.toByte()&&b[1]=='E'.code.toByte()&&b[2]=='L'.code.toByte()&&b[3]=='F'.code.toByte()&&b[4]==2.toByte()&&b[5]==1.toByte()&&((b[18].toInt()and 255)or((b[19].toInt()and 255)shl 8))==183}}.getOrDefault(false)
 private fun report(s:RuntimeBundleStatus,r:File?,b:List<String>)=RuntimeBundleReport(s,r,null,null,null,null,b)
}