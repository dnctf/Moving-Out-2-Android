package com.dnctf.movingout2.runtime

import org.junit.Assert.* 
import org.junit.Test
import java.io.File
import java.nio.file.Files

class RuntimeGraphicsComponentsTest {
    private fun root(): File = Files.createTempDirectory("graphics-bundle").toFile().apply {
        File(this, "runtime.properties").writeText("format_version=1\ntarget_abi=arm64-v8a\nbox64=bin/box64\nwine64=wine/bin/wine64\nwineserver=wine/bin/wineserver\nlibrary_dir=lib\n")
    }
    private fun pe(file: File) {
        file.parentFile.mkdirs(); val bytes=ByteArray(128); bytes[0]='M'.code.toByte(); bytes[1]='Z'.code.toByte(); bytes[0x3c]=64; bytes[64]='P'.code.toByte(); bytes[65]='E'.code.toByte(); bytes[68]=0x64; bytes[69]=0x86.toByte(); file.writeBytes(bytes)
    }
    private fun elf(file: File) {
        file.parentFile.mkdirs(); val bytes=ByteArray(64); bytes[0]=0x7f; bytes[1]='E'.code.toByte(); bytes[2]='L'.code.toByte(); bytes[3]='F'.code.toByte(); bytes[4]=2; bytes[5]=1; bytes[18]=183.toByte(); bytes[19]=0; file.writeBytes(bytes)
    }
    @Test fun absentOptionalGraphicsRemainNotConfigured() {
        val root=root(); val result=RuntimeGraphicsComponents.assess(root)
        assertEquals("NOT_CONFIGURED", result.dxvk); assertEquals("NOT_CONFIGURED", result.turnip); root.deleteRecursively()
    }
    @Test fun detectsPayloadFilesWithoutClaimingRuntimeSuccess() {
        val root=root(); File(root,"runtime.properties").appendText("dxvk_dir=graphics/dxvk\nturnip_dir=graphics/turnip\n")
        pe(File(root,"graphics/dxvk/d3d11.dll")); pe(File(root,"graphics/dxvk/dxgi.dll"));
        elf(File(root,"graphics/turnip/libvulkan_freedreno.so")); File(root,"graphics/turnip/freedreno_icd.json").apply { parentFile.mkdirs(); writeText("{\\\"library_path\\\":\\\"libvulkan_freedreno.so\\\"}") }
        val result=RuntimeGraphicsComponents.assess(root)
        assertEquals("FILES_PRESENT_UNVERIFIED",result.dxvk); assertEquals("FILES_PRESENT_UNVERIFIED",result.turnip)
        root.deleteRecursively()
    }

    @Test fun readsGraphicsComponentsFromManifestV2WithoutSelectingTurnipAutomatically() {
        val root = Files.createTempDirectory("graphics-manifest-v2").toFile()
        pe(File(root, "dxvk/x64/d3d11.dll"))
        pe(File(root, "dxvk/x64/dxgi.dll"))
        elf(File(root, "turnip/libvulkan_freedreno.so"))
        File(root, "turnip/freedreno_icd.aarch64.json").apply {
            parentFile.mkdirs()
            writeText("""{"library_path":"libvulkan_freedreno.so"}""")
        }
        File(root, "runtime-manifest.json").writeText(
            """{"schemaVersion":2,"components":{
                "d3d11":{"included":true,"path":"dxvk/x64/d3d11.dll"},
                "dxgi":{"included":true,"path":"dxvk/x64/dxgi.dll"},
                "turnipSo":{"included":true,"path":"turnip/libvulkan_freedreno.so"},
                "turnipIcd":{"included":true,"path":"turnip/freedreno_icd.aarch64.json"}
            }}"""
        )
        val result = RuntimeGraphicsComponents.assess(root)
        assertEquals("FILES_PRESENT_UNVERIFIED", result.dxvk)
        assertEquals("BUNDLE_AVAILABLE_NOT_SELECTED", result.turnip)
        root.deleteRecursively()
    }

    @Test fun rejectsGraphicsDirectoryPathEscape() {
        val root=root(); File(root,"runtime.properties").appendText("turnip_dir=../outside\n")
        val result=RuntimeGraphicsComponents.assess(root)
        assertEquals("NOT_CONFIGURED",result.turnip); assertTrue(result.notes.any { it.contains("escapes") }); root.deleteRecursively()
    }
}
