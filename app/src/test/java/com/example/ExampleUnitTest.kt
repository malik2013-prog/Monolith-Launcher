package com.example

import com.example.auth.AuthAccountManager
import com.example.pojavruntime.AiCrashAndHardwareEngine
import com.example.pojavruntime.JavaRuntimeMajor
import com.example.pojavruntime.JvmRuntimeConfig
import com.example.pojavruntime.MojangManifestLoader
import com.example.pojavruntime.OneClickFixAction
import com.example.pojavruntime.VanillaEra
import com.example.skineditor.ProceduralAiSkinSynthesizer
import com.example.skineditor.SkinFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ExampleUnitTest {

    @Test
    fun offlineUuidV3_matchesMinecraftStandard() {
        val auth = AuthAccountManager()
        val uuid1 = auth.generateOfflineUuid("Steve")
        val uuid2 = auth.generateOfflineUuid("Steve")
        assertEquals(uuid1, uuid2)
        assertEquals(36, uuid1.length)
    }

    @Test
    fun mojangManifestLoader_classifiesErasAndJavaRuntimes() {
        val loader = MojangManifestLoader()
        assertEquals(VanillaEra.PRE_CLASSIC, loader.classifyEra("rd-132211", "old_alpha"))
        assertEquals(VanillaEra.CLASSIC, loader.classifyEra("c0.30_01c", "old_alpha"))
        assertEquals(VanillaEra.INFDEV, loader.classifyEra("inf-20100618", "old_alpha"))
        assertEquals(VanillaEra.ALPHA, loader.classifyEra("a1.2.6", "old_alpha"))
        assertEquals(VanillaEra.BETA, loader.classifyEra("b1.7.3", "old_beta"))
        assertEquals(
            JavaRuntimeMajor.JRE_21,
            loader.inferRequiredJava("1.21.4", VanillaEra.RELEASE, "2024-12-03")
        )
        assertEquals(
            JavaRuntimeMajor.JRE_8,
            loader.inferRequiredJava("b1.7.3", VanillaEra.BETA, "2011-07-08")
        )
    }

    @Test
    fun aiCrashAnalyzer_detectsClassVersionMismatchAndSuggestsJava21() {
        val engine = AiCrashAndHardwareEngine()
        val version = MojangManifestLoader().curatedHistoricalCatalog().first { it.id == "1.21.4" }
        val log = "java.lang.UnsupportedClassVersionError: Main has been compiled by a more recent version (class file version 65.0)"
        val diagnosis = engine.analyzeLogOrCrash(
            logText = log,
            targetVersion = version,
            currentConfig = JvmRuntimeConfig(selectedJava = JavaRuntimeMajor.JRE_8)
        )
        assertEquals("JVM_CLASSFILE_MISMATCH", diagnosis.errorCode)
        assertTrue(diagnosis.fixAction is OneClickFixAction.SwitchJavaRuntime)
    }

    @Test
    fun proceduralAiSkinSynthesizer_generatesValid64x64Buffer() {
        val pixels = ProceduralAiSkinSynthesizer.generateFromPrompt(
            prompt = "Кибер-рыцарь в обсидиановой броне с изумрудным визором",
            format = SkinFormat.MODERN_64X64
        )
        assertEquals(64 * 64, pixels.size)
        assertTrue(pixels.any { ((it ushr 24) and 0xFF) == 0xFF })
    }
}
