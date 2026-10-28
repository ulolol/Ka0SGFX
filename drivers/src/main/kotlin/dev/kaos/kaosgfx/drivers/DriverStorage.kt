package dev.kaos.kaosgfx.drivers

import android.content.Context
import android.os.Environment
import android.util.Log
import java.io.File

object DriverStorage {
    private const val TAG = "KaosGFX-Storage"

    fun getDriversDir(context: Context): File {
        val downloadKaos = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            "KaosGFX"
        )
        val target = if (downloadKaos.exists() || downloadKaos.mkdirs()) {
            downloadKaos
        } else {
            val extFiles = context.getExternalFilesDir(null)
            if (extFiles != null && (extFiles.exists() || extFiles.mkdirs())) {
                extFiles
            } else {
                File("/sdcard/Download/KaosGFX").apply { mkdirs() }
            }
        }
        migrateLegacyDrivers(context, target)
        return target
    }

    private fun migrateLegacyDrivers(context: Context, targetDir: File) {
        try {
            // 1. Migrate any driver archives previously stored in app-private files/drivers
            val internalLegacy = File(context.filesDir, "drivers")
            if (internalLegacy.exists() && internalLegacy.isDirectory) {
                internalLegacy.listFiles { _, name -> name.endsWith(".zip") }?.forEach { src ->
                    val dest = File(targetDir, src.name)
                    if (!dest.exists()) {
                        src.copyTo(dest, overwrite = true)
                    }
                    src.delete()
                    Log.i(TAG, "Migrated driver zip from private storage to Downloads/KaosGFX: ${src.name}")
                }
            }

            // 2. Migrate from subfolder 'drivers' if it was created inside KaosGFX
            val subfolder = File(targetDir, "drivers")
            if (subfolder.exists() && subfolder.isDirectory && subfolder.canonicalPath != targetDir.canonicalPath) {
                subfolder.listFiles { _, name -> name.endsWith(".zip") }?.forEach { src ->
                    val dest = File(targetDir, src.name)
                    if (!dest.exists()) {
                        src.copyTo(dest, overwrite = true)
                    }
                    src.delete()
                    Log.i(TAG, "Migrated driver zip from subfolder to Downloads/KaosGFX: ${src.name}")
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Driver migration check warning: ${e.message}")
        }
    }
}
