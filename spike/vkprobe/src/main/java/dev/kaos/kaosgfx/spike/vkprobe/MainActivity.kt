package dev.kaos.kaosgfx.spike.vkprobe

import android.app.Activity
import android.os.Bundle
import android.util.Log
import android.widget.ScrollView
import android.widget.TextView
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.util.zip.ZipInputStream

class MainActivity : Activity() {

    companion object {
        private const val TAG = "VKPROBE_UI"

        init {
            System.loadLibrary("vkprobe")
        }
    }

    private external fun runProbe(
        hookLibDir: String,
        customDriverDir: String,
        customDriverName: String
    ): String

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val scrollView = ScrollView(this)
        val textView = TextView(this).apply {
            textSize = 14f
            setPadding(32, 48, 32, 48)
            text = "Initializing KaosGFX VKProbe...\n"
        }
        scrollView.addView(textView)
        setContentView(scrollView)

        Thread {
            try {
                val result = executeProbe()
                runOnUiThread {
                    textView.append("\n" + result)
                }
            } catch (e: Throwable) {
                Log.e(TAG, "Error executing probe", e)
                runOnUiThread {
                    textView.append("\nException during probe: ${e.message}\n${Log.getStackTraceString(e)}")
                }
            }
        }.start()
    }

    private fun executeProbe(): String {
        val turnipDir = File(filesDir, "turnip/test_turnip").apply { mkdirs() }
        var driverLibName = "libvulkan_freedreno.so"

        // 1. Extract turnip_driver.zip from assets
        assets.open("turnip_driver.zip").use { input ->
            ZipInputStream(input).use { zis ->
                var entry = zis.nextEntry
                while (entry != null) {
                    val targetFile = File(turnipDir, entry.name)
                    if (!entry.isDirectory) {
                        targetFile.parentFile?.mkdirs()
                        FileOutputStream(targetFile).use { fos ->
                            zis.copyTo(fos)
                        }
                        if (entry.name == "meta.json") {
                            try {
                                val json = JSONObject(targetFile.readText())
                                driverLibName = json.optString("libraryName", driverLibName)
                            } catch (e: Exception) {
                                Log.w(TAG, "Failed to parse meta.json", e)
                            }
                        }
                    }
                    zis.closeEntry()
                    entry = zis.nextEntry
                }
            }
        }
        Log.i(TAG, "Driver extracted to: ${turnipDir.absolutePath}, target: $driverLibName")

        // 2. Resolve hook library directory
        val nativeLibDir = applicationInfo.nativeLibraryDir
        var hookLibDir = nativeLibDir

        val hookImplInNativeDir = File(nativeLibDir, "libhook_impl.so")
        if (!hookImplInNativeDir.exists()) {
            val fallbackHooks = File(filesDir, "turnip_hooks").apply { mkdirs() }
            // Try extracting from apk if not found in nativeLibDir
            val hookLibs = listOf("libhook_impl.so", "libmain_hook.so", "libfile_redirect_hook.so", "libgsl_alloc_hook.so")
            for (lib in hookLibs) {
                val f = File(nativeLibDir, lib)
                if (f.exists()) {
                    f.copyTo(File(fallbackHooks, lib), overwrite = true)
                }
            }
            if (File(fallbackHooks, "libhook_impl.so").exists()) {
                hookLibDir = fallbackHooks.absolutePath
            }
        }
        Log.i(TAG, "Using hookLibDir: $hookLibDir")

        // 3. Call native probe
        val driverDirWithSlash = if (turnipDir.absolutePath.endsWith("/")) {
            turnipDir.absolutePath
        } else {
            "${turnipDir.absolutePath}/"
        }

        return runProbe(hookLibDir, driverDirWithSlash, driverLibName)
    }
}
