package com.dnctf.movingout2.runtime
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files

class TurnipIcdConfigTest {
 @Test fun rewritesIcdLibraryPathToAbsoluteRuntimePath(){
  val root=Files.createTempDirectory("turnip-icd").toFile()
  val source=File(root,"original.json").apply{writeText("""{"file_format_version":"1.0.0","library_path":"./libvulkan_freedreno.so"}""")}
  val lib=File(root,"libvulkan_freedreno.so").apply{writeText("stub")}
  val output=File(root,"session/freedreno_icd.aarch64.json")
  TurnipIcdConfig.rewrite(source,lib,output)
  assertEquals(lib.absolutePath,JSONObject(output.readText()).getString("library_path"))
  assertTrue(output.isFile);root.deleteRecursively()
 }
}