package com.dnctf.movingout2.core.logging

import com.dnctf.movingout2.runtime.RuntimeDiagnostic
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipFile

class CrashDiagnosticExporterTest {
 @Test fun exportsAllExpectedEntriesAndLogTail() {
  val temp=Files.createTempDirectory("diagnostic-export").toFile()
  val entries=listOf(
   RuntimeDiagnostic(RuntimeDiagnostic.Severity.INFO,"wine","Wine started"),
   RuntimeDiagnostic(RuntimeDiagnostic.Severity.ERROR,"box64","crash near startup")
  )
  val zip=CrashDiagnosticExporter(temp).export("""{"gpu":"Adreno"}""","""{"game":"valid"}""","""{"bundle":"ready"}""",entries,exitCode=139,crashInfo="SIGSEGV")
  assertTrue(zip.isFile&&zip.length()>0)
  ZipFile(zip).use { archive ->
   val expected=setOf("device-vulkan-report.json","game-installation-report.json","runtime-bundle-report.json","runtime-process.log")
   assertEquals(expected,archive.entries().asSequence().map{it.name}.toSet())
   assertEquals("""{"gpu":"Adreno"}""",archive.getInputStream(archive.getEntry("device-vulkan-report.json")).bufferedReader().readText())
   val log=archive.getInputStream(archive.getEntry("runtime-process.log")).bufferedReader().readText()
   assertTrue(log.contains("Wine started"));assertTrue(log.contains("crash near startup"));assertTrue(log.contains("139"));assertTrue(log.contains("SIGSEGV"))
  }
  temp.deleteRecursively()
 }
 @Test fun exportsFailurePathExitCodeStderrAndStackTraceWithoutTruncation() {
  val temp=Files.createTempDirectory("diagnostic-failure").toFile()
  val failure = runCatching { throw IllegalStateException("HTTP 404 while fetching runtime asset") }.exceptionOrNull()!!
  val trace = java.io.StringWriter().also { failure.printStackTrace(java.io.PrintWriter(it)) }.toString()
  val details = "stage=download; target=/data/user/0/com.dnctf.movingout2/files/cache/runtime-download.zip; exitCode=127; stderr=exec format error; $trace"
  val zip=CrashDiagnosticExporter(temp).export(
   """{"device":"test"}""", """{"status":"not_selected"}""", """{"status":"NOT_FOUND"}""",
   listOf(RuntimeDiagnostic(RuntimeDiagnostic.Severity.ERROR,"runtime-installer",details)),
   exitCode=127,
   crashInfo="runtime download/launch failed"
  )
  ZipFile(zip).use { archive ->
   val log=archive.getInputStream(archive.getEntry("runtime-process.log")).bufferedReader().readText()
   assertTrue(log.contains("runtime-download.zip"))
   assertTrue(log.contains("exitCode=127"))
   assertTrue(log.contains("stderr=exec format error"))
   assertTrue(log.contains("IllegalStateException: HTTP 404"))
   assertTrue(log.contains("Crash info: runtime download/launch failed"))
   assertTrue(log.contains("Process exit code: 127"))
  }
  temp.deleteRecursively()
 }

}