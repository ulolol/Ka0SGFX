package dev.kaos.kaosgfx.drivers

import kotlinx.serialization.Serializable

@Serializable
data class DriverMetadata(
    val id: String,
    val name: String,
    val version: String,
    val author: String,
    val description: String = "",
    val downloadUrl: String,
    val fileName: String,
    val fileSize: Long = 0L,
    val libraryName: String = "libvulkan_freedreno.so",
    val sourceRepo: String = "",
    val releaseDate: String = "",
    var isInstalled: Boolean = false,
    var localPath: String? = null
)

@Serializable
data class DriverSource(
    val id: String,
    val name: String,
    val repoOwner: String,
    val repoName: String,
    val branch: String = "main",
    val isEnabled: Boolean = true
)

@Serializable
data class DriverConfig(
    val driverDir: String,
    val driverName: String,
    val hookLibDir: String = "",
    val enableAngle: Boolean = false,
    val driverId: String = "",
    val driverTitle: String = ""
)

sealed class DownloadProgress {
    data class Progress(val bytesRead: Long, val totalBytes: Long, val percentage: Int) : DownloadProgress()
    data class Completed(val file: java.io.File, val metadata: DriverMetadata) : DownloadProgress()
    data class Failed(val error: Throwable) : DownloadProgress()
}
