package dev.kaos.kaosgfx.drivers

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.util.zip.ZipFile

class DriverDownloader(private val context: Context) {

    companion object {
        private const val TAG = "KaosGFX-Downloader"
    }

    private val client = OkHttpClient()
    private val storageDir get() = DriverStorage.getDriversDir(context)
    private val json = Json { ignoreUnknownKeys = true }

    fun downloadDriver(driver: DriverMetadata): Flow<DownloadProgress> = flow {
        val destFile = File(storageDir, driver.fileName)
        val tempFile = File(storageDir, "${driver.fileName}.tmp")

        try {
            val request = Request.Builder()
                .url(driver.downloadUrl)
                .header("User-Agent", "KaosGFX-DriverCatalog/1.0")
                .build()

            val response = client.newCall(request).execute()
            if (!response.isSuccessful) {
                emit(DownloadProgress.Failed(RuntimeException("HTTP ${response.code} download error: ${response.message}")))
                return@flow
            }

            val body = response.body ?: run {
                emit(DownloadProgress.Failed(RuntimeException("Empty response body")))
                return@flow
            }

            val totalBytes = body.contentLength()
            var bytesReadTotal = 0L

            body.byteStream().use { input ->
                FileOutputStream(tempFile).use { output ->
                    val buffer = ByteArray(8192)
                    var read: Int
                    var lastPercent = -1

                    while (input.read(buffer).also { read = it } != -1) {
                        output.write(buffer, 0, read)
                        bytesReadTotal += read

                        val percent = if (totalBytes > 0) ((bytesReadTotal * 100) / totalBytes).toInt() else 0
                        if (percent != lastPercent) {
                            lastPercent = percent
                            emit(DownloadProgress.Progress(bytesReadTotal, totalBytes, percent))
                        }
                    }
                }
            }

            // Rename tmp file to target file
            if (destFile.exists()) destFile.delete()
            tempFile.renameTo(destFile)

            // Inspect zip for meta.json
            var actualLibName = driver.libraryName
            try {
                ZipFile(destFile).use { zip ->
                    val metaEntry = zip.getEntry("meta.json")
                    if (metaEntry != null) {
                        val metaContent = zip.getInputStream(metaEntry).bufferedReader().readText()
                        val metaObj = json.parseToJsonElement(metaContent).jsonObject
                        val parsedName = metaObj["libraryName"]?.jsonPrimitive?.content
                        if (!parsedName.isNullOrEmpty()) {
                            actualLibName = parsedName
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Could not inspect zip meta.json: ${e.message}")
            }

            val updatedMetadata = driver.copy(
                isInstalled = true,
                localPath = destFile.absolutePath,
                libraryName = actualLibName
            )

            emit(DownloadProgress.Completed(destFile, updatedMetadata))

        } catch (e: Exception) {
            if (tempFile.exists()) tempFile.delete()
            emit(DownloadProgress.Failed(e))
        }
    }.flowOn(Dispatchers.IO)
}
