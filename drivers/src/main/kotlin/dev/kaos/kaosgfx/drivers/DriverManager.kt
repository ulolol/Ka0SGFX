package dev.kaos.kaosgfx.drivers

import android.content.Context
import android.net.Uri
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipFile

class DriverManager(private val context: Context) {

    companion object {
        private const val TAG = "KaosGFX-DrvMgr"
    }

    private val storageDir = File(context.filesDir, "drivers").apply { mkdirs() }
    private val json = Json { prettyPrint = true }

    suspend fun importDriverFromUri(uri: Uri, fileName: String): Result<DriverMetadata> = withContext(Dispatchers.IO) {
        try {
            val destFile = File(storageDir, fileName)
            context.contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(destFile).use { output ->
                    input.copyTo(output)
                }
            } ?: return@withContext Result.failure(IllegalStateException("Cannot open input stream for URI: $uri"))

            var libName = "libvulkan_freedreno.so"
            try {
                ZipFile(destFile).use { zip ->
                    val metaEntry = zip.getEntry("meta.json")
                    if (metaEntry != null) {
                        val text = zip.getInputStream(metaEntry).bufferedReader().readText()
                        val element = Json.parseToJsonElement(text)
                        val name = (element as? kotlinx.serialization.json.JsonObject)?.get("libraryName")
                        if (name != null) {
                            libName = name.toString().replace("\"", "")
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Zip inspection warning: ${e.message}")
            }

            val meta = DriverMetadata(
                id = fileName.substringBeforeLast("."),
                name = fileName,
                version = "custom",
                author = "User Imported",
                downloadUrl = "",
                fileName = fileName,
                fileSize = destFile.length(),
                libraryName = libName,
                isInstalled = true,
                localPath = destFile.absolutePath
            )
            Result.success(meta)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    fun buildConfigJson(targetPackage: String, driver: DriverMetadata, enableAngle: Boolean = false): String {
        val config = DriverConfig(
            driverDir = "/data/user/0/$targetPackage/files/turnip/${driver.id}",
            driverName = driver.libraryName,
            hookLibDir = "",
            enableAngle = enableAngle
        )
        return json.encodeToString(config)
    }

    fun deleteDriver(driver: DriverMetadata): Boolean {
        val file = driver.localPath?.let { File(it) } ?: File(storageDir, driver.fileName)
        return if (file.exists()) {
            file.delete()
        } else false
    }
}
