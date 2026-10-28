package dev.kaos.kaosgfx.patcher

import com.android.apksig.ApkVerifier
import com.reandroid.arsc.chunk.xml.AndroidManifestBlock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File
import java.util.zip.ZipFile

class ApkPatcherTest {

    @Test
    fun testPatchVkProbeApk() {
        val rootDir = File(".").canonicalFile.parentFile
        val inputApk = File(rootDir, "spike/vkprobe/build/outputs/apk/debug/vkprobe-debug.apk")
        assertTrue("Input test APK must exist at ${inputApk.absolutePath}", inputApk.exists())

        val outputDir = File(rootDir, "patcher/build/test-outputs")
        outputDir.mkdirs()
        val outputApk = File(outputDir, "vkprobe-patched.apk")
        if (outputApk.exists()) outputApk.delete()

        val engine = ApkPatcherEngine()
        val summary = engine.patchBaseApk(inputApk, outputApk)

        assertTrue("Output APK should be generated", outputApk.exists())
        assertTrue("Output APK should be non-empty", outputApk.length() > 0)
        assertTrue("Injected DEX name should start with classes and end with .dex", summary.injectedDexName.startsWith("classes") && summary.injectedDexName.endsWith(".dex"))

        // Verify entries in patched APK
        ZipFile(outputApk).use { zip ->
            // 1. Verify injected DEX
            assertNotNull("Injected DEX (${summary.injectedDexName}) should exist", zip.getEntry(summary.injectedDexName))

            // 2. Verify injected native libraries
            assertNotNull("libkaosgfx_bridge.so should exist", zip.getEntry("lib/arm64-v8a/libkaosgfx_bridge.so"))
            assertNotNull("libbytehook.so should exist", zip.getEntry("lib/arm64-v8a/libbytehook.so"))
            assertNotNull("libhook_impl.so should exist", zip.getEntry("lib/arm64-v8a/libhook_impl.so"))

            // 3. Verify patched AndroidManifest.xml
            val manifestEntry = zip.getEntry("AndroidManifest.xml")
            assertNotNull("AndroidManifest.xml should exist", manifestEntry)
            val manifestBytes = zip.getInputStream(manifestEntry).readBytes()

            val manifest = AndroidManifestBlock()
            manifest.readBytes(ByteArrayInputStream(manifestBytes))
            val app = manifest.applicationElement
            assertNotNull("Application element should exist", app)

            val factoryAttr = app.searchAttributeByName("appComponentFactory")
            assertNotNull("appComponentFactory attribute must exist", factoryAttr)
            assertEquals(
                "dev.kaos.kaosgfx.bootstrap.TurnipAppComponentFactory",
                factoryAttr.valueAsString
            )

            val extractLibs = app.searchAttributeByName("extractNativeLibs")
            assertNotNull("extractNativeLibs attribute must exist", extractLibs)
            assertTrue("extractNativeLibs must be true", extractLibs.valueAsBoolean)
        }

        // 4. Verify cryptographic signature via Google apksig ApkVerifier
        val verifier = ApkVerifier.Builder(outputApk).build()
        val verifyResult = verifier.verify()
        println("Verification status: isVerified=${verifyResult.isVerified}")
        println("  v1=${verifyResult.isVerifiedUsingV1Scheme}")
        println("  v2=${verifyResult.isVerifiedUsingV2Scheme}")
        println("  v3=${verifyResult.isVerifiedUsingV3Scheme}")
        println("  errors=${verifyResult.errors.map { it.toString() }}")
        println("  warnings=${verifyResult.warnings.map { it.toString() }}")
        assertTrue("Patched APK signature verification failed", verifyResult.isVerified)
    }
}
