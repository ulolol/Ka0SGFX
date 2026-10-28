package dev.kaos.kaosgfx.domain

import android.content.Context
import android.util.Log
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
    private val driverManager: DriverManager
) {

    companion object {
        private const val TAG = "KaosGFX-Orchestrator"
    }

    private val workDir = File(context.cacheDir, "patch_work").apply { mkdirs() }

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
            // Step 1: Patch APK
            onProgress("Patching base APK (injecting bootstrap DEX and native hooks)...", 0.2f)
            val patchedApk = File(workDir, "${game.packageName}-patched.apk")
            if (patchedApk.exists()) patchedApk.delete()

            patcherEngine.patchBaseApk(baseApk, patchedApk)
            Log.i(TAG, "Patched APK successfully written to ${patchedApk.absolutePath}")

            // Step 2: Install via Shizuku
            onProgress("Installing patched APK via privileged PackageInstaller...", 0.5f)
            val splitFiles = game.splitApkPaths.map { File(it) }
            val installResult = privilegedClient.installApk(patchedApk, splitFiles)
            if (installResult.isFailure) {
                return@withContext Result.failure(
                    installResult.exceptionOrNull() ?: RuntimeException("Installation failed")
                )
            }

            // Step 3: Stage driver bundle
            onProgress("Staging ${driver.name} bundle into external storage...", 0.75f)
            val configJson = driverManager.buildConfigJson(game.packageName, driver, enableAngle)
            val driverZip = driver.localPath?.let { File(it) }
            val stageResult = privilegedClient.stageDriver(game.packageName, driverZip, configJson)
            if (stageResult.isFailure) {
                Log.w(TAG, "Driver staging warning: ${stageResult.exceptionOrNull()?.message}")
            }

            // Step 4: Configure ANGLE GLES driver selection
            onProgress("Configuring graphics driver routing...", 0.9f)
            val angleType = if (enableAngle) AngleDriverType.ANGLE else AngleDriverType.DEFAULT
            privilegedClient.setAngleDriver(game.packageName, angleType)

            // Step 5: Force stop to apply changes
            onProgress("Finalizing setup and stopping stale process...", 1.0f)
            privilegedClient.forceStop(game.packageName)

            // Clean up temporary patched APK
            patchedApk.delete()

            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to patch and install ${game.packageName}", e)
            Result.failure(e)
        }
    }

    suspend fun unpatchGame(game: GameApp, originalApk: File?): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            if (originalApk != null && originalApk.exists()) {
                val splitFiles = game.splitApkPaths.map { File(it) }
                privilegedClient.installApk(originalApk, splitFiles).getOrThrow()
            }
            privilegedClient.setAngleDriver(game.packageName, AngleDriverType.DEFAULT)
            privilegedClient.forceStop(game.packageName)
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
