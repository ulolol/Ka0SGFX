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

    private val storageDir get() = DriverStorage.getDriversDir(context)
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
            var metaDesc = ""
            var metaName = fileName
            var metaVersion = "custom"
            try {
                ZipFile(destFile).use { zip ->
                    val metaEntry = zip.getEntry("meta.json")
                    if (metaEntry != null) {
                        val text = zip.getInputStream(metaEntry).bufferedReader().readText()
                        val obj = Json.parseToJsonElement(text) as? kotlinx.serialization.json.JsonObject
                        obj?.get("libraryName")?.toString()?.replace("\"", "")?.takeIf { it.isNotBlank() }?.let { libName = it }
                        obj?.get("description")?.toString()?.replace("\"", "")?.takeIf { it.isNotBlank() }?.let { metaDesc = it }
                        obj?.get("name")?.toString()?.replace("\"", "")?.takeIf { it.isNotBlank() }?.let { metaName = it }
                        obj?.get("driverVersion")?.toString()?.replace("\"", "")?.takeIf { it.isNotBlank() }?.let { metaVersion = it }
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Zip inspection warning: ${e.message}")
            }

            val gpuSupport = GpuSupportParser.parse(
                assetName = fileName,
                releaseName = metaName,
                tagName = metaVersion,
                bodyText = metaDesc
            )
            val driverType = GpuSupportParser.inferDriverType(
                assetName = fileName,
                releaseName = metaName,
                bodyText = metaDesc,
                libraryName = libName
            )

            val meta = DriverMetadata(
                id = fileName.substringBeforeLast("."),
                name = metaName,
                version = metaVersion,
                author = "User Imported",
                description = metaDesc,
                downloadUrl = "",
                fileName = fileName,
                fileSize = destFile.length(),
                libraryName = libName,
                gpuSupport = gpuSupport,
                driverType = driverType,
                isInstalled = true,
                localPath = destFile.absolutePath
            )
            Result.success(meta)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    fun buildConfigJson(
        targetPackage: String,
        driver: DriverMetadata,
        enableAngle: Boolean = false,
        mode: String = "patch",
        carrierPackage: String = ""
    ): String {
        var resolvedLibName = driver.libraryName

        // Inspect meta.json from local zip file if present
        val zipFile = driver.localPath?.let { File(it) }?.takeIf { it.exists() }
            ?: File(storageDir, driver.fileName).takeIf { it.exists() }

        if (zipFile != null) {
            try {
                ZipFile(zipFile).use { zip ->
                    val metaEntry = zip.getEntry("meta.json")
                    if (metaEntry != null) {
                        val text = zip.getInputStream(metaEntry).bufferedReader().readText()
                        val element = Json.parseToJsonElement(text)
                        val name = (element as? kotlinx.serialization.json.JsonObject)?.get("libraryName")
                        if (name != null) {
                            resolvedLibName = name.toString().replace("\"", "").trim()
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Could not inspect zip meta.json: ${e.message}")
            }
        }

        val config = DriverConfig(
            driverDir = if (mode == "root") "/data/adb/kaosgfx/drivers/$targetPackage" else "/data/user/0/$targetPackage/files/turnip/${driver.id}",
            driverName = resolvedLibName,
            hookLibDir = "",
            enableAngle = enableAngle,
            driverId = driver.id,
            driverTitle = driver.name,
            mode = mode,
            carrierPackage = carrierPackage
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
