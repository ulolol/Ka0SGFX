package dev.kaos.kaosgfx.patcher

import com.reandroid.apk.ApkModule
import com.reandroid.archive.ByteInputSource
import com.reandroid.archive.writer.ZipAligner
import java.io.File
import java.io.InputStream
import java.util.regex.Pattern
import java.util.zip.ZipEntry

class ApkPatcherEngine(
    private val credentials: ApkSignerUtil.SignerCredentials = ApkSignerUtil.loadDefaultCredentials()
) {
    companion object {
        private const val BOOTSTRAP_DEX_RES = "/bootstrap.dex"
        private val NATIVE_LIBS = listOf(
            "libkaosgfx_bridge.so",
            "libbytehook.so",
            "libshadowhook.so",
            "libhook_impl.so",
            "libfile_redirect_hook.so",
            "libmain_hook.so",
            "libgsl_alloc_hook.so"
        )
    }

    data class PatchSummary(
        val originalPackageName: String?,
        val originalFactory: String?,
        val injectedDexName: String,
        val injectedLibs: List<String>,
        val outputApk: File
    )

    fun patchBaseApk(inputApk: File, outputApk: File): PatchSummary {
        val tempUnsigned = File.createTempFile("kaos_unsigned_", ".apk")
        val apkModule = ApkModule.loadApkFile(inputApk)
        try {
            // 1. Patch AndroidManifest.xml
            val originalPackageName = apkModule.packageName
            val manifest = apkModule.androidManifest
                ?: throw IllegalStateException("AndroidManifest.xml not found in ${inputApk.name}")

            val manifestBytes = manifest.bytes
            val manifestResult = ManifestPatcher.patchManifestBytes(manifestBytes)

            // Replace manifest in apkModule with patched binary XML
            apkModule.add(ByteInputSource(manifestResult.patchedXmlBytes, "AndroidManifest.xml"))

            // 2. Determine next DEX file name or replace existing bootstrap dex
            val dexFiles = apkModule.listDexFiles()
            val injectedDexName: String
            if (manifestResult.wasAlreadyPatched && dexFiles.size > 1) {
                // Was already patched: replace the last dex (injected bootstrap) instead of compounding dex files!
                val lastDex = dexFiles.maxByOrNull { com.reandroid.archive.InputSource.getDexNumber(it.name) }
                injectedDexName = lastDex?.name ?: "classes2.dex"
            } else {
                var highestDexIndex = 1
                for (dex in dexFiles) {
                    val num = com.reandroid.archive.InputSource.getDexNumber(dex.name)
                    if (num > highestDexIndex) {
                        highestDexIndex = num
                    }
                }
                val nextDexIndex = if (dexFiles.isEmpty()) 1 else highestDexIndex + 1
                injectedDexName = if (nextDexIndex == 1) "classes.dex" else "classes$nextDexIndex.dex"
            }

            // 3. Add injected bootstrap DEX
            val bootstrapDexBytes = readResourceBytes(BOOTSTRAP_DEX_RES)
            apkModule.add(ByteInputSource(bootstrapDexBytes, injectedDexName))

            // 4. Add native hook libraries under lib/arm64-v8a/
            for (lib in NATIVE_LIBS) {
                val libBytes = readResourceBytes("/arm64-v8a/$lib.bin")
                val libSource = ByteInputSource(libBytes, "lib/arm64-v8a/$lib")
                libSource.setMethod(ZipEntry.STORED)
                apkModule.add(libSource)
            }

            // 5. Remove old signatures
            apkModule.removeDir("META-INF")

            // 6. Write aligned unsigned APK using ARSCLib ZipAligner
            val aligner = ZipAligner.apkAligner()
            aligner.setFileAlignment(Pattern.compile(".*\\.so$"), 16384)

            val writer = apkModule.createApkFileWriter(tempUnsigned)
            writer.zipAligner = aligner
            writer.write()
            writer.close()

            // 7. Sign the APK preserving 16KB alignment
            outputApk.parentFile?.mkdirs()
            ApkSignerUtil.signApk(
                inputApk = tempUnsigned,
                outputApk = outputApk,
                credentials = credentials
            )

            return PatchSummary(
                originalPackageName = originalPackageName,
                originalFactory = manifestResult.originalFactory,
                injectedDexName = injectedDexName,
                injectedLibs = NATIVE_LIBS,
                outputApk = outputApk
            )
        } finally {
            apkModule.close()
            tempUnsigned.delete()
        }
    }

    fun reSignSplitApk(inputSplit: File, outputSplit: File) {
        val tempUnsigned = File.createTempFile("kaos_split_unsigned_", ".apk")
        val apkModule = ApkModule.loadApkFile(inputSplit)
        try {
            apkModule.removeDir("META-INF")

            val aligner = ZipAligner.apkAligner()
            aligner.setFileAlignment(Pattern.compile(".*\\.so$"), 16384)

            val writer = apkModule.createApkFileWriter(tempUnsigned)
            writer.zipAligner = aligner
            writer.write()
            writer.close()

            outputSplit.parentFile?.mkdirs()
            ApkSignerUtil.signApk(
                inputApk = tempUnsigned,
                outputApk = outputSplit,
                credentials = credentials
            )
        } finally {
            apkModule.close()
            tempUnsigned.delete()
        }
    }

    fun unpatchBaseApk(inputPatchedApk: File, outputStockApk: File) {
        val tempUnsigned = File.createTempFile("kaos_unpatch_unsigned_", ".apk")
        val apkModule = ApkModule.loadApkFile(inputPatchedApk)
        try {
            // 1. Unpatch AndroidManifest.xml
            val manifest = apkModule.androidManifest
                ?: throw IllegalStateException("AndroidManifest.xml not found in ${inputPatchedApk.name}")

            val unpatchedBytes = ManifestPatcher.unpatchManifestBytes(manifest.bytes)
            apkModule.add(ByteInputSource(unpatchedBytes, "AndroidManifest.xml"))

            // 2. Remove highest DEX file (which was the injected bootstrap DEX)
            val dexFiles = apkModule.listDexFiles()
            var highestDexIndex = 1
            var highestDexName: String? = null
            for (dex in dexFiles) {
                val num = com.reandroid.archive.InputSource.getDexNumber(dex.name)
                if (num >= highestDexIndex) {
                    highestDexIndex = num
                    highestDexName = dex.name
                }
            }
            if (highestDexIndex > 1 && highestDexName != null) {
                apkModule.removeInputSource(highestDexName)
            }

            // 3. Remove injected native hook libraries
            for (lib in NATIVE_LIBS) {
                apkModule.removeInputSource("lib/arm64-v8a/$lib")
            }

            // 4. Remove signatures
            apkModule.removeDir("META-INF")

            // 5. Realign & sign
            val aligner = ZipAligner.apkAligner()
            aligner.setFileAlignment(Pattern.compile(".*\\.so$"), 16384)

            val writer = apkModule.createApkFileWriter(tempUnsigned)
            writer.zipAligner = aligner
            writer.write()
            writer.close()

            outputStockApk.parentFile?.mkdirs()
            ApkSignerUtil.signApk(
                inputApk = tempUnsigned,
                outputApk = outputStockApk,
                credentials = credentials
            )
        } finally {
            apkModule.close()
            tempUnsigned.delete()
        }
    }

    private fun readResourceBytes(path: String): ByteArray {
        val stream: InputStream = ApkPatcherEngine::class.java.getResourceAsStream(path)
            ?: throw IllegalStateException("Required resource $path not found")
        return stream.use { it.readBytes() }
    }
}
