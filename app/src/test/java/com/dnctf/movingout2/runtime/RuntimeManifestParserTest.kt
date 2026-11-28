package com.dnctf.movingout2.runtime

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest

class RuntimeManifestParserTest {
 private fun x64SharedElf(machine:Int=62,type:Int=3):ByteArray{
  val bytes=ByteArray(120)
  val b=ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
  bytes[0]=0x7f;bytes[1]='E'.code.toByte();bytes[2]='L'.code.toByte();bytes[3]='F'.code.toByte()
  bytes[4]=2;bytes[5]=1;bytes[6]=1
  b.putShort(16,type.toShort());b.putShort(18,machine.toShort());b.putInt(20,1)
  b.putLong(24,0x400000);b.putLong(32,64);b.putShort(52,64.toShort());b.putShort(54,56.toShort());b.putShort(56,1.toShort())
  b.putInt(64,1);b.putInt(68,5);b.putLong(72,0);b.putLong(80,0x400000);b.putLong(88,0x400000);b.putLong(96,120);b.putLong(104,120);b.putLong(112,0x1000)
  return bytes
 }
 private fun pe64():ByteArray {
  val bytes=ByteArray(512); val b=ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
  bytes[0]='M'.code.toByte();bytes[1]='Z'.code.toByte();b.putInt(0x3c,0x80)
  bytes[0x80]='P'.code.toByte();bytes[0x81]='E'.code.toByte()
  b.putShort(0x84,0x8664.toShort());b.putShort(0x86,1.toShort());b.putShort(0x94,0xF0.toShort());b.putShort(0x98,0x20b.toShort())
  return bytes
 }
 private fun sha(file:File):String{val d=MessageDigest.getInstance("SHA-256");file.inputStream().use{input->val b=ByteArray(4096);while(true){val n=input.read(b);if(n<0)break;d.update(b,0,n)}};return d.digest().joinToString(""){"%02x".format(it)}}
 private fun fixture(turnip:Boolean=false):File{
  val root=Files.createTempDirectory("manifest-v1").toFile()
  val components=JSONObject()
  fun put(key:String,path:String,contents:String){val f=File(root,path);f.parentFile.mkdirs()
   when(key){
    "box64" -> f.writeBytes(x64SharedElf(183,2))
    "wine64","wineserver" -> f.writeBytes(x64SharedElf(62,2))
    "turnipSo" -> f.writeBytes(x64SharedElf(183,3))
    "d3d11","dxgi","xinput1_3","xinput1_4","xinput9_1_0","vcruntime140","vcruntime140_1","msvcp140" -> f.writeBytes(pe64())
    else -> f.writeText(contents)
   }
   val abi = when (key) {
   "box64" -> RuntimeBinaryInspector.ELF64_AARCH64
   "turnipSo" -> RuntimeBinaryInspector.ELF64_AARCH64_SHARED
   "turnipSource" -> "SOURCE_ARCHIVE"
   "turnipNotice", "turnipLicense" -> "TEXT"
   "wine64", "wineserver" -> RuntimeBinaryInspector.ELF64_X86_64
   "d3d11", "dxgi", "xinput1_3", "xinput1_4", "xinput9_1_0", "vcruntime140", "vcruntime140_1", "msvcp140" -> RuntimeBinaryInspector.PE32_PLUS_X86_64
   else -> "JSON"
  }
  components.put(key,JSONObject().put("included",true).put("path",path).put("sha256",sha(f)).put("abi",abi))}
  put("box64","box64/bin/box64","box64")
  put("wine64","wine/bin/wine64","wine64")
  put("wineserver","wine/bin/wineserver","server")
  val lib=File(root,"wine/lib/wine");lib.mkdirs();File(lib,"module.so").writeBytes(x64SharedElf());
  val unix=File(lib,"x86_64-unix");unix.mkdirs();File(unix,"ntdll.so").writeBytes(x64SharedElf());File(unix,"win32u.so").writeBytes(x64SharedElf())
  components.put("wineLib",JSONObject().put("included",true).put("path","wine/lib/wine").put("sha256",hashDir(lib)).put("abi","DIRECTORY_SHA256_X86_64"))
  val glibc=File(root,"wine/lib/x86_64-linux-gnu");glibc.mkdirs()
  File(glibc,"libc.so.6").writeBytes(x64SharedElf())
  components.put("guestGlibc",JSONObject().put("included",true).put("path","wine/lib/x86_64-linux-gnu").put("sha256",hashDir(glibc)).put("abi","DIRECTORY_SHA256_X86_64"))
  put("d3d11","dxvk/x64/d3d11.dll","d3d")
  put("dxgi","dxvk/x64/dxgi.dll","dxgi")
  put("xinput1_3","wine/lib/wine/x86_64-windows/xinput1_3.dll","xinput3")
  put("xinput1_4","wine/lib/wine/x86_64-windows/xinput1_4.dll","xinput4")
  put("xinput9_1_0","wine/lib/wine/x86_64-windows/xinput9_1_0.dll","xinput91")
  put("vcruntime140","wine/lib/wine/x86_64-windows/vcruntime140.dll","vcruntime")
  put("vcruntime140_1","wine/lib/wine/x86_64-windows/vcruntime140_1.dll","vcruntime1")
  put("msvcp140","wine/lib/wine/x86_64-windows/msvcp140.dll","msvcp")
  // XInput DLLs live under wineLib, so calculate its directory digest after both are staged.
  components.put("wineLib",JSONObject().put("included",true).put("path","wine/lib/wine").put("sha256",hashDir(lib)).put("abi","DIRECTORY_SHA256_X86_64"))
  components.put("turnip",JSONObject().put("included",turnip))
  if(turnip){
   put("turnipSo","turnip/libvulkan_freedreno.so","vk")
   put("turnipIcd","turnip/freedreno_icd.aarch64.json","{}")
    put("turnipMeta","turnip/meta.json","{\"libraryName\":\"libvulkan_freedreno.so\",\"minApi\":28}")
    put("turnipSource","turnip/SOURCE.tar.xz","source-archive-fixture")
    put("turnipNotice","turnip/NOTICE","Turnip driver notice")
    put("turnipLicense","turnip/LICENSES/GPL-3.0.txt","GPL-3.0")
  } else {
   components.put("turnipSo",JSONObject().put("included",false).put("path","turnip/libvulkan_freedreno.so").put("abi","ELF64_AARCH64_SHARED").put("sha256",""))
   components.put("turnipIcd",JSONObject().put("included",false).put("path","turnip/freedreno_icd.aarch64.json").put("abi","JSON").put("sha256",""))
  }
  val launch=JSONObject().put("arguments",org.json.JSONArray().put("-force-d3d11"))
   .put("environment",JSONObject().put("WINEDLLOVERRIDES","d3d11,dxgi=n,b;xinput1_3,xinput1_4,xinput9_1_0=n,b")
    .put("BOX64_DYNAREC","1").put("BOX64_DYNAREC_BIGBLOCK","1")
    .put("BOX64_DYNAREC_STRONGMEM","1").put("BOX64_DYNAREC_SAFEFLAGS","1"))
  File(root,"runtime-manifest.json").writeText(JSONObject().put("schemaVersion",2).put("targetAbi","arm64-v8a").put("bundleVersion","test-1").put("provenance",JSONObject().put("packager","scripts/package_runtime_bundle.sh").put("manifestFormat","role-validated-abi-v2")).put("components",components).put("defaultLaunchProfile",launch).toString())
  return root
 }
 private fun hashDir(dir:File):String{val listing=StringBuilder();dir.walkTopDown().filter{it.isFile}.sortedBy{it.relativeTo(dir).invariantSeparatorsPath}.forEach{f->listing.append(sha(f)).append("  ./").append(f.relativeTo(dir).invariantSeparatorsPath).append('\n')};val d=MessageDigest.getInstance("SHA-256");d.update(listing.toString().toByteArray(Charsets.UTF_8));return d.digest().joinToString(""){"%02x".format(it)}}
 @Test fun acceptsValidBundleWithAndWithoutTurnip(){val a=fixture();val ar=RuntimeManifestParser().validate(a);println("NON_TURNIP_MANIFEST_ERRORS=${ar.errors}");assertTrue(ar.errors.toString(),ar.valid);a.deleteRecursively();val b=fixture(true);val br=RuntimeManifestParser().validate(b);println("TURNIP_MANIFEST_ERRORS=${br.errors}");assertTrue(br.errors.toString(),br.valid);b.deleteRecursively()}
 @Test fun rejectsMissingGuestGlibcComponent(){
  val root=fixture()
  val json=JSONObject(File(root,"runtime-manifest.json").readText())
  json.getJSONObject("components").remove("guestGlibc")
  File(root,"runtime-manifest.json").writeText(json.toString())
  val result=RuntimeManifestParser().validate(root)
  assertFalse(result.valid)
  assertTrue(result.errors.any{it.contains("guestGlibc")})
  root.deleteRecursively()
 }
 @Test fun rejectsGuestGlibcHashMismatch(){
  val root=fixture()
  File(root,"wine/lib/x86_64-linux-gnu/libc.so.6").appendBytes(byteArrayOf(7))
  assertTrue(RuntimeManifestParser().validate(root).errors.any{it.contains("SHA-256 mismatch: guestGlibc")})
  root.deleteRecursively()
 }
 @Test fun rejectsNonElfGuestGlibcEvenWhenHashMatches(){
  val root=fixture()
  val libc=File(root,"wine/lib/x86_64-linux-gnu/libc.so.6");libc.writeText("not ELF")
  val json=JSONObject(File(root,"runtime-manifest.json").readText())
  json.getJSONObject("components").getJSONObject("guestGlibc").put("sha256",hashDir(libc.parentFile!!))
  File(root,"runtime-manifest.json").writeText(json.toString())
  val result=RuntimeManifestParser().validate(root)
  assertFalse(result.valid)
  assertTrue(result.errors.any{it.contains("Guest glibc object is not ELF64 x86-64")})
  root.deleteRecursively()
 }
 @Test fun rejectsUnexpectedPackagerProvenance(){
  val root=fixture()
  val json=JSONObject(File(root,"runtime-manifest.json").readText())
  json.getJSONObject("provenance").put("packager","untrusted-packager.sh")
  File(root,"runtime-manifest.json").writeText(json.toString())
  val result=RuntimeManifestParser().validate(root)
  assertFalse(result.valid)
  assertTrue(result.errors.any{it.contains("provenance")})
  root.deleteRecursively()
 }
 @Test fun rejectsComponentWithoutExplicitAbiProvenance(){
  val root=fixture()
  val json=JSONObject(File(root,"runtime-manifest.json").readText())
  json.getJSONObject("components").getJSONObject("box64").remove("abi")
  File(root,"runtime-manifest.json").writeText(json.toString())
  val result=RuntimeManifestParser().validate(root)
  assertFalse(result.valid)
  assertTrue(result.errors.any{it.contains("ABI must be declared")})
  root.deleteRecursively()
 }
 @Test fun rejectsMissingXinputComponents() {
  val root=fixture()
  val json=JSONObject(File(root,"runtime-manifest.json").readText())
  json.getJSONObject("components").remove("xinput1_3")
  File(root,"runtime-manifest.json").writeText(json.toString())
  val result=RuntimeManifestParser().validate(root)
  assertFalse(result.valid)
  assertTrue(result.errors.any{it.contains("xinput1_3")})
  root.deleteRecursively()
 }
 @Test fun rejectsWrongXinputAbi() {
  val root=fixture()
  val json=JSONObject(File(root,"runtime-manifest.json").readText())
  json.getJSONObject("components").getJSONObject("xinput1_4").put("abi",RuntimeBinaryInspector.ELF64_X86_64)
  File(root,"runtime-manifest.json").writeText(json.toString())
  val result=RuntimeManifestParser().validate(root)
  assertFalse(result.valid)
  assertTrue(result.errors.any{it.contains("xinput1_4") && it.contains("ABI")})
  root.deleteRecursively()
 }
 @Test fun rejectsWrongAbi(){val root=fixture();val j=JSONObject(File(root,"runtime-manifest.json").readText()).put("targetAbi","x86_64");File(root,"runtime-manifest.json").writeText(j.toString());assertFalse(RuntimeManifestParser().validate(root).valid);root.deleteRecursively()}
 @Test fun rejectsPathTraversal(){val root=fixture();val j=JSONObject(File(root,"runtime-manifest.json").readText());j.getJSONObject("components").getJSONObject("box64").put("path","../outside.so");File(root,"runtime-manifest.json").writeText(j.toString());assertTrue(RuntimeManifestParser().validate(root).errors.any{it.contains("Unsafe path")});root.deleteRecursively()}
 @Test fun rejectsChecksumMismatch(){val root=fixture();File(root,"dxvk/x64/dxgi.dll").writeText("changed");assertTrue(RuntimeManifestParser().validate(root).errors.any{it.contains("SHA-256 mismatch")});root.deleteRecursively()}

 @Test fun acceptsExactNonTurnipManifestShapeWithEmptyOptionalHashes(){
  val root=fixture(false)
  val manifest=JSONObject(File(root,"runtime-manifest.json").readText())
  val components=manifest.getJSONObject("components")
  assertFalse(components.getJSONObject("turnip").getBoolean("included"))
  for(key in listOf("turnipSo","turnipIcd")){
   val item=components.getJSONObject(key)
   assertFalse(item.getBoolean("included"))
   assertEquals("",item.getString("sha256"))
   assertTrue(item.getString("path").isNotBlank())
   assertTrue(item.getString("abi").isNotBlank())
  }
  val launch=manifest.getJSONObject("defaultLaunchProfile")
  assertEquals("-force-d3d11",launch.getJSONArray("arguments").getString(0))
  assertTrue(launch.getJSONObject("environment").has("BOX64_DYNAREC"))
  val result=RuntimeManifestParser().validate(root)
  assertTrue(result.errors.toString(),result.valid)
  root.deleteRecursively()
 }
 @Test fun acceptsTurnipManifestShapeAndLaunchProfile(){
  val root=fixture(true)
  val manifest=JSONObject(File(root,"runtime-manifest.json").readText())
  val components=manifest.getJSONObject("components")
  assertTrue(components.getJSONObject("turnip").getBoolean("included"))
  assertTrue(components.getJSONObject("turnipSo").getString("sha256").matches(Regex("[a-f0-9]{64}")))
  assertTrue(components.getJSONObject("turnipIcd").getString("sha256").matches(Regex("[a-f0-9]{64}")))
  assertEquals("-force-d3d11",manifest.getJSONObject("defaultLaunchProfile").getJSONArray("arguments").getString(0))
  val result=RuntimeManifestParser().validate(root)
  assertTrue(result.errors.toString(),result.valid)
  root.deleteRecursively()
 }
}
