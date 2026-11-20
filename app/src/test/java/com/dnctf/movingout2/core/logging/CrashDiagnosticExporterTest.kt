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
}