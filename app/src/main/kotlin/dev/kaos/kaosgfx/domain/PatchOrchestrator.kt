package dev.kaos.kaosgfx.domain

import android.content.Context
import android.util.Log
import android.os.Environment
import dev.kaos.kaosgfx.drivers.DriverMetadata
import dev.kaos.kaosgfx.drivers.DriverManager
import dev.kaos.kaosgfx.patcher.ApkPatcherEngine
import dev.kaos.kaosgfx.privileged.AngleDriverType
import dev.kaos.kaosgfx.privileged.PrivilegedClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

class PatchOrchestrator(
    private val context: Context,
    private val patcherEngine: ApkPatcherEngine,
    private val privilegedClient: PrivilegedClient,
    private val driverManager: DriverManager,
    private val driverDownloader: dev.kaos.kaosgfx.drivers.DriverDownloader
) {

    companion object {
        private const val TAG = "KaosGFX-Orchestrator"
    }

    private fun getWorkDir(): File {
        val downloadKaos = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "KaosGFX")
        if (downloadKaos.exists() || downloadKaos.mkdirs()) {
            return downloadKaos
        }
        val extFiles = context.getExternalFilesDir("patch_work")
        if (extFiles != null && (extFiles.exists() || extFiles.mkdirs())) {
            return extFiles
        }
        val extCache = context.externalCacheDir
        if (extCache != null && (extCache.exists() || extCache.mkdirs())) {
            return File(extCache, "patch_work").apply { mkdirs() }
        }
        return File("/sdcard/Download/KaosGFX").apply { mkdirs() }
    }

    suspend fun patchAndInstallGame(
        game: GameApp,
        driver: DriverMetadata,
        enableAngle: Boolean,
        onProgress: (step: String, progress: Float) -> Unit
    ): Result<Unit> = withContext(Dispatchers.IO) {
        if (game.isDenylisted) {
            return@withContext Result.failure(
                SecurityException("Cannot patch ${game.appName}: ${game.denylistReason}")
            )
        }

        val baseApk = File(game.baseApkPath)
        if (!baseApk.exists()) {
            return@withContext Result.failure(
                IllegalArgumentException("Base APK not found at ${game.baseApkPath}")
            )
        }

        try {
            val workDir = getWorkDir()

            // Back up pristine stock APK if not already backed up or if currently running unpatched stock
            val stockBackup = File(workDir, "${game.packageName}-stock.apk")
            val stockSplitsDir = File(workDir, "${game.packageName}-stock-splits")
            if (!stockBackup.exists() || !game.isPatched) {
                try {
                    baseApk.copyTo(stockBackup, overwrite = true)
                    Log.i(TAG, "Pristine stock APK backed up to ${stockBackup.absolutePath}")
                    if (game.splitApkPaths.isNotEmpty()) {
                        stockSplitsDir.mkdirs()
                        game.splitApkPaths.forEach { sp ->
                            val sf = File(sp)
                            if (sf.exists()) {
                                sf.copyTo(File(stockSplitsDir, sf.name), overwrite = true)
                            }
                        }
                        Log.i(TAG, "Pristine stock splits backed up (${game.splitApkPaths.size} splits)")
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Could not back up stock APK: ${e.message}")
                }
            }

            // Always select the pristine stock APK as the source for patching
            val sourceApk = if (stockBackup.exists() && stockBackup.length() > 0) {
                Log.i(TAG, "Using pristine stock backup as source for patching: ${stockBackup.absolutePath}")
                stockBackup
            } else {
                baseApk
            }

            // Step 1: Patch APK
            onProgress("Patching base APK (injecting bootstrap DEX and native hooks)...", 0.2f)
            val patchedApk = File(workDir, "${game.packageName}-patched.apk")
            if (patchedApk.exists()) patchedApk.delete()

            patcherEngine.patchBaseApk(sourceApk, patchedApk)
            Log.i(TAG, "Patched APK successfully written to ${patchedApk.absolutePath}")

            // Step 2: Re-sign split APKs with matching KaosGFX credentials so pm install-commit accepts the session
            val splitsToInstall = mutableListOf<File>()
            if (game.splitApkPaths.isNotEmpty()) {
                val signedSplitsDir = File(workDir, "${game.packageName}-signed-splits").apply { mkdirs() }
                val totalSplits = game.splitApkPaths.size
                game.splitApkPaths.forEachIndexed { idx, splitPath ->
                    val origFile = File(splitPath)
                    val pristineSplit = File(stockSplitsDir, origFile.name).takeIf { it.exists() && it.length() > 0 } ?: origFile
                    val outSplit = File(signedSplitsDir, origFile.name)
                    val progressFraction = 0.25f + 0.2f * ((idx + 1f) / totalSplits)
                    onProgress("Re-signing split APK ${idx + 1}/$totalSplits (${origFile.name})...", progressFraction)
                    patcherEngine.reSignSplitApk(pristineSplit, outSplit)
                    splitsToInstall.add(outSplit)
                }
                Log.i(TAG, "Successfully re-signed ${splitsToInstall.size} split APKs with consistent key")
            }

            // Step 3: Install via Shizuku (with automatic data backup & signature mismatch handling)
            onProgress("Installing patched APK via privileged PackageInstaller...", 0.5f)
            val installResult = privilegedClient.installApk(
                game.packageName,
                patchedApk,
                splitsToInstall,
                allowReinstallOnSignatureMismatch = true
            )
            if (installResult.isFailure) {
                return@withContext Result.failure(
                    installResult.exceptionOrNull() ?: RuntimeException("Installation failed")
                )
            }

            // Step 3: Ensure driver is downloaded and stage driver bundle
            var driverToStage = driver
            if (driverToStage.localPath == null || !File(driverToStage.localPath!!).exists()) {
                onProgress("Downloading ${driver.name} driver package...", 0.6f)
                driverDownloader.downloadDriver(driverToStage).collect { progress ->
                    when (progress) {
                        is dev.kaos.kaosgfx.drivers.DownloadProgress.Progress -> {
                            val p = 0.6f + 0.15f * (progress.percentage / 100f)
                            onProgress("Downloading ${driver.name} (${progress.percentage}%)...", p)
                        }
                        is dev.kaos.kaosgfx.drivers.DownloadProgress.Completed -> {
                            driverToStage = progress.metadata
                        }
                        is dev.kaos.kaosgfx.drivers.DownloadProgress.Failed -> {
                            Log.w(TAG, "Driver download failed: ${progress.error.message}")
                        }
                    }
                }
            }

            onProgress("Staging ${driverToStage.name} bundle into external storage...", 0.78f)
            val configJson = driverManager.buildConfigJson(game.packageName, driverToStage, enableAngle)
            val driverZip = driverToStage.localPath?.let { File(it) }?.takeIf { it.exists() }
                ?: File(dev.kaos.kaosgfx.drivers.DriverStorage.getDriversDir(context), driverToStage.fileName).takeIf { it.exists() }
            val stageResult = privilegedClient.stageDriver(game.packageName, driverZip, configJson)
            if (stageResult.isFailure) {
                Log.w(TAG, "Driver staging warning: ${stageResult.exceptionOrNull()?.message}")
            }

            // Save shared config copy in Downloads/KaosGFX/configs/ for instant reading by KaosGFX
            try {
                val configsDir = File(dev.kaos.kaosgfx.drivers.DriverStorage.getDriversDir(context), "configs").apply { mkdirs() }
                File(configsDir, "${game.packageName}.json").writeText(configJson)
            } catch (e: Exception) {
                Log.w(TAG, "Failed writing local config copy: ${e.message}")
            }

            // Step 4: Configure ANGLE GLES driver selection
            onProgress("Configuring graphics driver routing...", 0.9f)
            val angleType = if (enableAngle) AngleDriverType.ANGLE else AngleDriverType.DEFAULT
            privilegedClient.setAngleDriver(game.packageName, angleType)

            // Step 5: Force stop to apply changes
            onProgress("Finalizing setup and stopping stale process...", 1.0f)
            privilegedClient.forceStop(game.packageName)

            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to patch and install ${game.packageName}", e)
            Result.failure(e)
        }
    }

    suspend fun unpatchGame(
        game: GameApp,
        onProgress: ((step: String, progress: Float) -> Unit)? = null
    ): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val workDir = getWorkDir()
            val stockBackup = File(workDir, "${game.packageName}-stock.apk")
            val apkToInstall: File

            if (stockBackup.exists()) {
                onProgress?.invoke("Restoring from pristine stock backup...", 0.25f)
                apkToInstall = stockBackup
            } else {
                onProgress?.invoke("Reversing injected bootstrap and restoring manifest...", 0.25f)
                val unpatchedApk = File(workDir, "${game.packageName}-unpatched.apk")
                val currentBaseApk = File(game.baseApkPath)
                if (!currentBaseApk.exists()) {
                    return@withContext Result.failure(IllegalStateException("Current base APK not found at ${game.baseApkPath}"))
                }
                patcherEngine.unpatchBaseApk(currentBaseApk, unpatchedApk)
                apkToInstall = unpatchedApk
            }

            val stockSplitsDir = File(workDir, "${game.packageName}-stock-splits")
            val splitsToRestore = if (stockSplitsDir.exists()) {
                stockSplitsDir.listFiles { _, name -> name.endsWith(".apk") }?.toList() ?: emptyList()
            } else {
                game.splitApkPaths.map { File(it) }.filter { it.exists() }
            }

            onProgress?.invoke("Installing restored stock APK via privileged installer...", 0.55f)
            val installResult = privilegedClient.installApk(
                game.packageName,
                apkToInstall,
                splitsToRestore,
                allowReinstallOnSignatureMismatch = true
            )
            if (installResult.isFailure) {
                return@withContext Result.failure(
                    installResult.exceptionOrNull() ?: RuntimeException("Stock installation failed")
                )
            }

            // Clean up staged driver files, signed splits, and patched artifact
            onProgress?.invoke("Cleaning up custom driver bundles and routing...", 0.85f)
            try {
                val patchedFile = File(workDir, "${game.packageName}-patched.apk")
                if (patchedFile.exists()) patchedFile.delete()
                val signedSplitsDir = File(workDir, "${game.packageName}-signed-splits")
                if (signedSplitsDir.exists()) signedSplitsDir.deleteRecursively()
                val stagedDriverDir = File("/sdcard/Android/data/${game.packageName}/files/kaosgfx")
                if (stagedDriverDir.exists()) stagedDriverDir.deleteRecursively()
                val tmpStagedDir = File("/data/local/tmp/kaosgfx/${game.packageName}")
                if (tmpStagedDir.exists()) tmpStagedDir.deleteRecursively()
                val configsDir = File(dev.kaos.kaosgfx.drivers.DriverStorage.getDriversDir(context), "configs")
                val localConfig = File(configsDir, "${game.packageName}.json")
                if (localConfig.exists()) localConfig.delete()
            } catch (ignored: Exception) {}

            privilegedClient.setAngleDriver(game.packageName, AngleDriverType.DEFAULT)
            privilegedClient.forceStop(game.packageName)
            onProgress?.invoke("Stock configuration restored successfully!", 1.0f)

            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to unpatch ${game.packageName}", e)
            Result.failure(e)
        }
    }
}
