package dev.kaos.kaosgfx.privileged

import android.os.Process
import android.util.Log
import androidx.annotation.Keep
import java.io.BufferedReader
import java.io.File
import java.io.FileOutputStream
import java.io.InputStreamReader
import java.util.regex.Pattern
import kotlin.system.exitProcess

@Keep
class PrivilegedService : IPrivilegedService.Stub() {

    companion object {
        private const val TAG = "KaosGFX-Privileged"
        private val SESSION_PATTERN = Pattern.compile("\\[(\\d+)\\]")
    }

    init {
        Log.i(TAG, "PrivilegedService started with UID: ${Process.myUid()} (expected 2000 for shell)")
    }

    override fun installPackage(baseApkPath: String, splitApkPaths: List<String>?): Int {
        val baseFile = File(baseApkPath)
        if (!baseFile.exists()) {
            Log.e(TAG, "Base APK not found at $baseApkPath")
            return -1
        }

        val splits = splitApkPaths?.filter { File(it).exists() } ?: emptyList()

        return if (splits.isEmpty()) {
            installSingleApk(baseFile)
        } else {
            installSplitApks(baseFile, splits)
        }
    }

    private fun installSingleApk(apk: File): Int {
        Log.i(TAG, "Installing single APK: ${apk.absolutePath}")
        val cmd = listOf(
            "pm", "install",
            "-r", "-d", "-t",
            "--bypass-low-target-sdk-block",
            apk.absolutePath
        )
        val result = executeCommand(cmd)
        Log.i(TAG, "pm install exit: ${result.exitCode}, out: ${result.stdout}, err: ${result.stderr}")
        return if (result.exitCode == 0 && result.stdout.contains("Success", ignoreCase = true)) {
            0
        } else {
            if (result.exitCode != 0) result.exitCode else -2
        }
    }

    private fun installSplitApks(baseApk: File, splitApkPaths: List<String>): Int {
        Log.i(TAG, "Installing multi-split APK: base=${baseApk.absolutePath}, ${splitApkPaths.size} splits")

        // 1. Create install session
        val createCmd = listOf(
            "pm", "install-create",
            "-r", "-d", "-t",
            "--bypass-low-target-sdk-block"
        )
        val createRes = executeCommand(createCmd)
        if (createRes.exitCode != 0) {
            Log.e(TAG, "pm install-create failed: ${createRes.stderr}")
            return createRes.exitCode
        }

        val matcher = SESSION_PATTERN.matcher(createRes.stdout)
        if (!matcher.find()) {
            Log.e(TAG, "Failed to parse session ID from: ${createRes.stdout}")
            return -3
        }
        val sessionId = matcher.group(1) ?: return -3
        Log.i(TAG, "Created install session: $sessionId")

        try {
            // 2. Write base APK
            val writeBaseCmd = listOf(
                "pm", "install-write",
                "-S", baseApk.length().toString(),
                sessionId,
                "base.apk",
                baseApk.absolutePath
            )
            val writeBaseRes = executeCommand(writeBaseCmd)
            if (writeBaseRes.exitCode != 0) {
                Log.e(TAG, "pm install-write base failed: ${writeBaseRes.stderr}")
                abandonSession(sessionId)
                return writeBaseRes.exitCode
            }

            // 3. Write each split APK
            for ((index, splitPath) in splitApkPaths.withIndex()) {
                val splitFile = File(splitPath)
                val splitName = "split_$index.apk"
                val writeSplitCmd = listOf(
                    "pm", "install-write",
                    "-S", splitFile.length().toString(),
                    sessionId,
                    splitName,
                    splitFile.absolutePath
                )
                val writeSplitRes = executeCommand(writeSplitCmd)
                if (writeSplitRes.exitCode != 0) {
                    Log.e(TAG, "pm install-write split $splitName failed: ${writeSplitRes.stderr}")
                    abandonSession(sessionId)
                    return writeSplitRes.exitCode
                }
            }

            // 4. Commit session
            val commitCmd = listOf("pm", "install-commit", sessionId)
            val commitRes = executeCommand(commitCmd)
            Log.i(TAG, "pm install-commit exit: ${commitRes.exitCode}, out: ${commitRes.stdout}, err: ${commitRes.stderr}")

            return if (commitRes.exitCode == 0 && commitRes.stdout.contains("Success", ignoreCase = true)) {
                0
            } else {
                if (commitRes.exitCode != 0) commitRes.exitCode else -4
            }
        } catch (e: Exception) {
            Log.e(TAG, "Exception during multi-split install", e)
            abandonSession(sessionId)
            return -5
        }
    }

    private fun abandonSession(sessionId: String) {
        try {
            executeCommand(listOf("pm", "install-abandon", sessionId))
        } catch (ignored: Exception) {}
    }

    override fun forceStop(packageName: String) {
        Log.i(TAG, "forceStop: $packageName")
        executeCommand(listOf("am", "force-stop", packageName))
    }

    override fun setGlobalSetting(key: String, value: String?) {
        Log.i(TAG, "setGlobalSetting: $key = $value")
        if (value.isNullOrEmpty()) {
            executeCommand(listOf("settings", "delete", "global", key))
        } else {
            executeCommand(listOf("settings", "put", "global", key, value))
        }
    }

    override fun getGlobalSetting(key: String): String {
        val res = executeCommand(listOf("settings", "get", "global", key))
        val output = res.stdout.trim()
        return if (output == "null") "" else output
    }

    override fun stageDriverBundle(packageName: String, driverZipPath: String?, configJson: String): Boolean {
        return try {
            val targetDir = File("/sdcard/Android/data/$packageName/files/kaosgfx")
            if (!targetDir.exists()) {
                targetDir.mkdirs()
            }

            // Write configuration JSON
            val configFile = File(targetDir, "kaosgfx_driver_config.json")
            configFile.writeText(configJson)

            // If a driver zip path is provided, copy it to targetDir/driver.zip
            if (!driverZipPath.isNullOrEmpty()) {
                val srcZip = File(driverZipPath)
                if (srcZip.exists()) {
                    val destZip = File(targetDir, "driver.zip")
                    srcZip.copyTo(destZip, overwrite = true)
                }
            }
            Log.i(TAG, "Successfully staged driver bundle for $packageName at ${targetDir.absolutePath}")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to stage driver bundle for $packageName", e)
            false
        }
    }

    override fun destroy() {
        Log.i(TAG, "PrivilegedService destroy requested, exiting process...")
        exitProcess(0)
    }

    private data class CommandResult(val exitCode: Int, val stdout: String, val stderr: String)

    private fun executeCommand(cmd: List<String>): CommandResult {
        return try {
            val proc = ProcessBuilder(cmd).redirectErrorStream(false).start()
            val stdout = proc.inputStream.bufferedReader().use { it.readText() }
            val stderr = proc.errorStream.bufferedReader().use { it.readText() }
            val exitCode = proc.waitFor()
            CommandResult(exitCode, stdout, stderr)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to execute cmd: ${cmd.joinToString(" ")}", e)
            CommandResult(-1, "", e.message ?: "Exception")
        }
    }
}
