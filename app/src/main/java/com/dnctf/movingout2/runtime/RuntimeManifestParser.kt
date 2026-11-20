package com.dnctf.movingout2.runtime
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

data class RuntimeManifestValidation(val valid:Boolean,val errors:List<String>,val manifest:JSONObject?,val componentFiles:Map<String,File>)
class RuntimeManifestParser {
 fun validate(root:File):RuntimeManifestValidation {
  val errors=mutableListOf<String>(); val files=linkedMapOf<String,File>()
  val mf=File(root,"runtime-manifest.json")
  if(!mf.isFile||mf.length()==0L||!mf.canRead()) return RuntimeManifestValidation(false,listOf("runtime-manifest.json missing/empty/unreadable"),null,files)
  val json=try{JSONObject(mf.readText())}catch(e:Exception){return RuntimeManifestValidation(false,listOf("Manifest JSON parse failed: ${e.message}"),null,files)}
  if(json.optInt("schemaVersion",-1)!=1) errors+="schemaVersion must be 1"
  if(json.optString("targetAbi")!="arm64-v8a") errors+="targetAbi must be arm64-v8a"
  val components=json.optJSONObject("components")?:run{errors+="components object missing";JSONObject()}
  fun verify(key:String,fallback:String,required:Boolean,dir:Boolean=false){
   val c=components.optJSONObject(key)
   if(c==null||!c.optBoolean("included",false)){if(required)errors+="Required component $key missing or marked excluded";return}
   val path=c.optString("path",fallback);val hash=c.optString("sha256").lowercase()
   if(path.isBlank()||path.startsWith("/")||path.startsWith("\\")||path.contains(':')||path.split('/', '\\').any{it==".."}){errors+="Unsafe path for $key";return}
   if(!hash.matches(Regex("[a-f0-9]{64}"))){errors+="Invalid SHA-256 for $key";return}
   val file=runCatching{File(root,path).canonicalFile}.getOrNull()
   if(file==null||!file.toPath().startsWith(root.canonicalFile.toPath())){errors+="Path escapes bundle for $key";return}
   if(dir){if(!file.isDirectory||file.walkTopDown().none{it.isFile&&it.length()>0}){errors+="Directory missing/empty: $key";return}}
   else if(!file.isFile||file.length()<=0){errors+="File missing/empty: $key";return}
   val actual=if(dir)hashDirectory(file)else hashFile(file)
   if(actual!=hash){errors+="SHA-256 mismatch: $key";return};files[key]=file
  }
  verify("box64","box64/bin/box64",true);verify("wine64","wine/bin/wine64",true);verify("wineserver","wine/bin/wineserver",true)
  verify("wineLib","wine/lib/wine",true,true);verify("d3d11","dxvk/x64/d3d11.dll",true);verify("dxgi","dxvk/x64/dxgi.dll",true)
  if(components.optJSONObject("turnip")?.optBoolean("included",false)==true){verify("turnipSo","turnip/libvulkan_freedreno.so",false);verify("turnipIcd","turnip/freedreno_icd.aarch64.json",false)}
  return RuntimeManifestValidation(errors.isEmpty(),errors.distinct(),json,files)
 }
 private fun hashDirectory(dir:File):String{val listing=StringBuilder();dir.walkTopDown().filter{it.isFile}.sortedBy{it.relativeTo(dir).invariantSeparatorsPath}.forEach{f->listing.append(hashFile(f)).append("  ./").append(f.relativeTo(dir).invariantSeparatorsPath).append('\n')};val d=MessageDigest.getInstance("SHA-256");d.update(listing.toString().toByteArray(Charsets.UTF_8));return d.digest().joinToString(""){"%02x".format(it)}}
 private fun hashFile(file:File):String{val d=MessageDigest.getInstance("SHA-256");file.inputStream().use{input->val b=ByteArray(65536);while(true){val n=input.read(b);if(n<0)break;d.update(b,0,n)}};return d.digest().joinToString(""){"%02x".format(it)}}
}