package dev.kaos.kaosgfx.privileged

import android.os.Process
import android.util.Log
import androidx.annotation.Keep
import java.io.File
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
        val resultStr = installPackageDetailed("", baseApkPath, splitApkPaths, true)
        return if (resultStr.isEmpty()) 0 else -1
    }

    override fun installPackageDetailed(
        packageName: String,
        baseApkPath: String,
        splitApkPaths: List<String>?,
        allowReinstallOnSignatureMismatch: Boolean
    ): String {
        val baseFile = File(baseApkPath)
        if (!baseFile.exists()) {
            val msg = "Base APK not found at $baseApkPath (readable: ${baseFile.canRead()})"
            Log.e(TAG, msg)
            return msg
        }

        val splits = splitApkPaths?.filter { File(it).exists() } ?: emptyList()

        // Stage APKs to /data/local/tmp/kaos_staging so system_server can read them without FUSE SELinux denial
        val stagingDir = File("/data/local/tmp/kaos_staging").apply { mkdirs() }
        val stagedBase = File(stagingDir, "base.apk")

        try {
            baseFile.copyTo(stagedBase, overwrite = true)
            executeCommand(listOf("chmod", "644", stagedBase.absolutePath))

            val stagedSplits: List<String> = splits.mapIndexed { index, splitPath ->
                val dest = File(stagingDir, "split_$index.apk")
                File(splitPath).copyTo(dest, overwrite = true)
                executeCommand(listOf("chmod", "644", dest.absolutePath))
                dest.absolutePath
            }

            var result = if (stagedSplits.isEmpty()) {
                installSingleApk(stagedBase)
            } else {
                installSplitApks(stagedBase, stagedSplits)
            }

            val isSigMismatch = result.combinedOutput.contains("INSTALL_FAILED_UPDATE_INCOMPATIBLE", ignoreCase = true) ||
                    result.combinedOutput.contains("signatures do not match", ignoreCase = true)

            if (!result.isSuccess && isSigMismatch && allowReinstallOnSignatureMismatch && packageName.isNotBlank()) {
                Log.w(TAG, "Signature mismatch detected for $packageName. Performing data backup and fresh install...")

                val dataDir = File("/sdcard/Android/data/$packageName")
                val dataBak = File("/sdcard/Android/data/${packageName}.kaos_bak")
                val obbDir = File("/sdcard/Android/obb/$packageName")
                val obbBak = File("/sdcard/Android/obb/${packageName}.kaos_bak")

                try {
                    if (dataDir.exists()) dataDir.renameTo(dataBak)
                    if (obbDir.exists()) obbDir.renameTo(obbBak)

                    // Uninstall previous package
                    val uninstRes = executeCommand(listOf("pm", "uninstall", "--user", "0", packageName))
                    Log.i(TAG, "pm uninstall exit: ${uninstRes.exitCode}, out: ${uninstRes.stdout}")

                    // Re-attempt install
                    result = if (stagedSplits.isEmpty()) {
                        installSingleApk(stagedBase)
                    } else {
                        installSplitApks(stagedBase, stagedSplits)
                    }
                } finally {
                    // Restore backup
                    if (dataBak.exists()) {
                        if (dataDir.exists()) dataDir.deleteRecursively()
                        dataBak.renameTo(dataDir)
                    }
                    if (obbBak.exists()) {
                        if (obbDir.exists()) obbDir.deleteRecursively()
                        obbBak.renameTo(obbDir)
                    }
                }
            }

            return if (result.isSuccess) {
                ""
            } else {
                result.combinedOutput.ifEmpty { "Installation exited with code ${result.exitCode}" }
            }
        } finally {
            stagingDir.deleteRecursively()
        }
    }

    private data class InstallRunResult(val isSuccess: Boolean, val exitCode: Int, val combinedOutput: String)

    private fun installSingleApk(apk: File): InstallRunResult {
        Log.i(TAG, "Installing single APK: ${apk.absolutePath}")
        val cmd = listOf(
            "pm", "install",
            "-r", "-d", "-t",
            "--bypass-low-target-sdk-block",
            apk.absolutePath
        )
        val result = executeCommand(cmd)
        val combined = "${result.stdout}\n${result.stderr}".trim()
        Log.i(TAG, "pm install exit: ${result.exitCode}, combined: $combined")
        val isOk = result.exitCode == 0 && combined.contains("Success", ignoreCase = true)
        return InstallRunResult(isOk, result.exitCode, combined)
    }

    private fun installSplitApks(baseApk: File, splitApkPaths: List<String>): InstallRunResult {
        Log.i(TAG, "Installing multi-split APK: base=${baseApk.absolutePath}, ${splitApkPaths.size} splits")

        val createCmd = listOf(
            "pm", "install-create",
            "-r", "-d", "-t",
            "--bypass-low-target-sdk-block"
        )
        val createRes = executeCommand(createCmd)
        if (createRes.exitCode != 0) {
            val err = "pm install-create failed: ${createRes.stderr} ${createRes.stdout}"
            Log.e(TAG, err)
            return InstallRunResult(false, createRes.exitCode, err)
        }

        val matcher = SESSION_PATTERN.matcher(createRes.stdout)
        if (!matcher.find()) {
            val err = "Failed to parse session ID from: ${createRes.stdout}"
            Log.e(TAG, err)
            return InstallRunResult(false, -3, err)
        }
        val sessionId = matcher.group(1) ?: return InstallRunResult(false, -3, "Null session ID")

        try {
            val writeBaseCmd = listOf(
                "pm", "install-write",
                "-S", baseApk.length().toString(),
                sessionId,
                "base.apk",
                baseApk.absolutePath
            )
            val writeBaseRes = executeCommand(writeBaseCmd)
            if (writeBaseRes.exitCode != 0) {
                val err = "pm install-write base failed: ${writeBaseRes.stderr} ${writeBaseRes.stdout}"
                Log.e(TAG, err)
                abandonSession(sessionId)
                return InstallRunResult(false, writeBaseRes.exitCode, err)
            }

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
                    val err = "pm install-write split $splitName failed: ${writeSplitRes.stderr} ${writeSplitRes.stdout}"
                    Log.e(TAG, err)
                    abandonSession(sessionId)
                    return InstallRunResult(false, writeSplitRes.exitCode, err)
                }
            }

            val commitCmd = listOf("pm", "install-commit", sessionId)
            val commitRes = executeCommand(commitCmd)
            val combined = "${commitRes.stdout}\n${commitRes.stderr}".trim()
            Log.i(TAG, "pm install-commit exit: ${commitRes.exitCode}, combined: $combined")

            val isOk = commitRes.exitCode == 0 && combined.contains("Success", ignoreCase = true)
            return InstallRunResult(isOk, commitRes.exitCode, combined)
        } catch (e: Exception) {
            Log.e(TAG, "Exception during multi-split install", e)
            abandonSession(sessionId)
            return InstallRunResult(false, -5, e.message ?: "Exception in split install")
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

            val configFile = File(targetDir, "kaosgfx_driver_config.json")
            configFile.writeText(configJson)

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
