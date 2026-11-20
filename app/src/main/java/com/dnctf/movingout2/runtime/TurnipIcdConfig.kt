package com.dnctf.movingout2.runtime
import org.json.JSONObject
import java.io.File
import java.io.IOException

object TurnipIcdConfig {
 fun rewrite(source:File,library:File,destination:File):File {
  if(!source.isFile||source.length()==0)throw IOException("Turnip ICD JSON missing or empty.")
  if(!library.isFile||library.length()==0L)throw IOException("Turnip Vulkan library missing or empty.")
  val json=try{JSONObject(source.readText())}catch(e:Exception){throw IOException("Invalid ICD JSON: ${e.message}",e)}
  json.put("library_path",library.absolutePath)
  destination.parentFile?.mkdirs()
  destination.writeText(json.toString(2))
  return destination
 }
}