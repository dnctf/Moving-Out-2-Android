package com.dnctf.movingout2.runtime

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest

class RuntimeManifestParserTest {
 private fun sha(file:File):String{val d=MessageDigest.getInstance("SHA-256");file.inputStream().use{input->val b=ByteArray(4096);while(true){val n=input.read(b);if(n<0)break;d.update(b,0,n)}};return d.digest().joinToString(""){"%02x".format(it)}}
 private fun fixture(turnip:Boolean=false):File{
  val root=Files.createTempDirectory("manifest-v1").toFile()
  val components=JSONObject()
  fun put(key:String,path:String,contents:String){val f=File(root,path);f.parentFile.mkdirs();f.writeText(contents);components.put(key,JSONObject().put("included",true).put("path",path).put("sha256",sha(f)))}
  put("box64","box64/bin/box64","box64")
  put("wine64","wine/bin/wine64","wine64")
  put("wineserver","wine/bin/wineserver","server")
  val lib=File(root,"wine/lib/wine");lib.mkdirs();File(lib,"module.so").writeText("module")
  components.put("wineLib",JSONObject().put("included",true).put("path","wine/lib/wine").put("sha256",hashDir(lib)))
  put("d3d11","dxvk/x64/d3d11.dll","d3d")
  put("dxgi","dxvk/x64/dxgi.dll","dxgi")
  components.put("turnip",JSONObject().put("included",turnip))
  if(turnip){put("turnipSo","turnip/libvulkan_freedreno.so","vk");put("turnipIcd","turnip/freedreno_icd.aarch64.json","{}")}
  File(root,"runtime-manifest.json").writeText(JSONObject().put("schemaVersion",1).put("targetAbi","arm64-v8a").put("components",components).put("defaultLaunchProfile",JSONObject()).toString())
  return root
 }
 private fun hashDir(dir:File):String{val d=MessageDigest.getInstance("SHA-256");dir.walkTopDown().filter{it.isFile}.sortedBy{it.relativeTo(dir).invariantSeparatorsPath}.forEach{f->d.update(f.relativeTo(dir).invariantSeparatorsPath.toByteArray(Charsets.UTF_8));f.inputStream().use{input->val b=ByteArray(4096);while(true){val n=input.read(b);if(n<0)break;d.update(b,0,n)}}};return d.digest().joinToString(""){"%02x".format(it)}}
 @Test fun acceptsValidBundleWithAndWithoutTurnip(){val a=fixture();assertTrue(RuntimeManifestParser().validate(a).errors.toString(),RuntimeManifestParser().validate(a).valid);a.deleteRecursively();val b=fixture(true);assertTrue(RuntimeManifestParser().validate(b).errors.toString(),RuntimeManifestParser().validate(b).valid);b.deleteRecursively()}
 @Test fun rejectsWrongAbi(){val root=fixture();val j=JSONObject(File(root,"runtime-manifest.json").readText()).put("targetAbi","x86_64");File(root,"runtime-manifest.json").writeText(j.toString());assertFalse(RuntimeManifestParser().validate(root).valid);root.deleteRecursively()}
 @Test fun rejectsPathTraversal(){val root=fixture();val j=JSONObject(File(root,"runtime-manifest.json").readText());j.getJSONObject("components").getJSONObject("box64").put("path","../outside.so");File(root,"runtime-manifest.json").writeText(j.toString());assertTrue(RuntimeManifestParser().validate(root).errors.any{it.contains("Unsafe path")});root.deleteRecursively()}
 @Test fun rejectsChecksumMismatch(){val root=fixture();File(root,"dxvk/x64/dxgi.dll").writeText("changed");assertTrue(RuntimeManifestParser().validate(root).errors.any{it.contains("SHA-256 mismatch")});root.deleteRecursively()}
}