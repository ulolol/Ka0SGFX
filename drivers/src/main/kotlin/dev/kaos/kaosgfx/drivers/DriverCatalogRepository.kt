package dev.kaos.kaosgfx.drivers

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import okhttp3.Cache
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

class DriverCatalogRepository(private val context: Context) {

    companion object {
        private const val TAG = "KaosGFX-Catalog"
        private const val USER_AGENT = "KaosGFX-DriverCatalog/1.0"

        val DEFAULT_SOURCES = listOf(
            DriverSource("banners", "Banners Turnip", "The412Banner", "Banners-Turnip"),
            DriverSource("k11mch1", "K11MCH1 Drivers", "K11MCH1", "AdrenoToolsDrivers"),
            DriverSource("whitebelyash", "Whitebelyash Turnip", "whitebelyash", "AdrenoToolsDrivers"),
            DriverSource("stevenmxz", "StevenMXZ Drivers", "StevenMXZ", "Adreno-Tools-Drivers"),
            DriverSource("mrpurple", "MrPurple Turnip", "MrPurple666", "purple-turnip")
        )
    }

    private val cacheDir = File(context.cacheDir, "catalog_http_cache").apply { mkdirs() }
    private val client = OkHttpClient.Builder()
        .cache(Cache(cacheDir, 20L * 1024 * 1024))
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    private val storageDir = File(context.filesDir, "drivers").apply { mkdirs() }

    suspend fun fetchAllSources(sources: List<DriverSource> = DEFAULT_SOURCES): List<DriverMetadata> = withContext(Dispatchers.IO) {
        val allDrivers = mutableListOf<DriverMetadata>()
        for (source in sources) {
            if (!source.isEnabled) continue
            try {
                val drivers = fetchReleasesForSource(source)
                allDrivers.addAll(drivers)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to fetch from ${source.name} (${source.repoOwner}/${source.repoName})", e)
            }
        }

        // Add built-in fallback/cached entries if list is empty
        if (allDrivers.isEmpty()) {
            allDrivers.addAll(getBuiltinCatalog())
        }

        // Mark installed status
        markInstalledDrivers(allDrivers)
        allDrivers
    }

    suspend fun fetchReleasesForSource(source: DriverSource): List<DriverMetadata> = withContext(Dispatchers.IO) {
        val url = "https://api.github.com/repos/${source.repoOwner}/${source.repoName}/releases"
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .header("Accept", "application/vnd.github.v3+json")
            .build()

        val response = client.newCall(request).execute()
        if (!response.isSuccessful) {
            Log.w(TAG, "GitHub API returned ${response.code} for ${source.repoName}")
            return@withContext emptyList()
        }

        val bodyString = response.body?.string() ?: return@withContext emptyList()
        val releasesArray = json.parseToJsonElement(bodyString).jsonArray

        val drivers = mutableListOf<DriverMetadata>()
        for (releaseElement in releasesArray) {
            val releaseObj = releaseElement.jsonObject
            val releaseName = releaseObj["name"]?.jsonPrimitive?.contentOrNull
                ?: releaseObj["tag_name"]?.jsonPrimitive?.content ?: "Release"
            val tagName = releaseObj["tag_name"]?.jsonPrimitive?.content ?: ""
            val bodyText = releaseObj["body"]?.jsonPrimitive?.contentOrNull ?: ""
            val publishedAt = releaseObj["published_at"]?.jsonPrimitive?.contentOrNull ?: ""

            val assetsArray = releaseObj["assets"]?.jsonArray ?: continue
            for (assetElement in assetsArray) {
                val assetObj = assetElement.jsonObject
                val fileName = assetObj["name"]?.jsonPrimitive?.content ?: continue
                val downloadUrl = assetObj["browser_download_url"]?.jsonPrimitive?.content ?: continue
                val size = assetObj["size"]?.jsonPrimitive?.longOrNull ?: 0L

                // Only consider .zip archives
                if (!fileName.endsWith(".zip", ignoreCase = true)) continue

                val id = "${source.id}_${tagName}_${fileName.substringBeforeLast(".")}"
                    .replace("[^a-zA-Z0-9._-]".toRegex(), "_")

                // Infer libraryName based on filename or repo type
                val inferredLibName = if (fileName.contains("adreno", ignoreCase = true) && !fileName.contains("turnip", ignoreCase = true)) {
                    "vkgsl.so"
                } else {
                    "libvulkan_freedreno.so"
                }

                drivers.add(
                    DriverMetadata(
                        id = id,
                        name = "$releaseName - $fileName",
                        version = tagName,
                        author = source.repoOwner,
                        description = bodyText.take(200),
                        downloadUrl = downloadUrl,
                        fileName = fileName,
                        fileSize = size,
                        libraryName = inferredLibName,
                        sourceRepo = "${source.repoOwner}/${source.repoName}",
                        releaseDate = publishedAt
                    )
                )
            }
        }
        drivers
    }

    fun markInstalledDrivers(drivers: List<DriverMetadata>) {
        val installedFiles = storageDir.listFiles { _, name -> name.endsWith(".zip") } ?: emptyArray()
        val installedMap = installedFiles.associateBy { it.name }

        for (driver in drivers) {
            val localZip = installedMap[driver.fileName]
            if (localZip != null && localZip.exists()) {
                driver.isInstalled = true
                driver.localPath = localZip.absolutePath
            } else {
                driver.isInstalled = false
                driver.localPath = null
            }
        }
    }

    fun getInstalledDrivers(): List<DriverMetadata> {
        val list = mutableListOf<DriverMetadata>()
        val files = storageDir.listFiles { _, name -> name.endsWith(".zip") } ?: return emptyList()
        for (file in files) {
            val id = file.nameWithoutExtension
            list.add(
                DriverMetadata(
                    id = id,
                    name = file.name,
                    version = "local",
                    author = "Custom / Installed",
                    downloadUrl = "",
                    fileName = file.name,
                    fileSize = file.length(),
                    libraryName = "libvulkan_freedreno.so",
                    isInstalled = true,
                    localPath = file.absolutePath
                )
            )
        }
        return list
    }

    private fun getBuiltinCatalog(): List<DriverMetadata> {
        return listOf(
            DriverMetadata(
                id = "banners_turnip_25.0.0_r12",
                name = "Turnip Mesa 25.0.0 (Banners R12)",
                version = "v25.0.0-r12",
                author = "The412Banner",
                description = "Optimized Mesa Turnip driver for Adreno 7xx/6xx with Android 15/16 support.",
                downloadUrl = "https://github.com/The412Banner/Banners-Turnip/releases/download/v25.0.0-r12/turnip_25.0.0_r12.zip",
                fileName = "turnip_25.0.0_r12.zip",
                fileSize = 14500000L,
                libraryName = "libvulkan_freedreno.so",
                sourceRepo = "The412Banner/Banners-Turnip"
            ),
            DriverMetadata(
                id = "k11mch1_turnip_v24.3.0",
                name = "Turnip v24.3.0-devel (K11MCH1)",
                version = "v24.3.0",
                author = "K11MCH1",
                description = "Turnip Vulkan driver compiled with aggressive performance optimizations.",
                downloadUrl = "https://github.com/K11MCH1/AdrenoToolsDrivers/releases/download/v24.3.0/turnip-v24.3.0.zip",
                fileName = "turnip-v24.3.0.zip",
                fileSize = 13800000L,
                libraryName = "libvulkan_freedreno.so",
                sourceRepo = "K11MCH1/AdrenoToolsDrivers"
            ),
            DriverMetadata(
                id = "whitebelyash_turnip_mesa_24.2",
                name = "Turnip Mesa 24.2.0 (Whitebelyash)",
                version = "v24.2.0",
                author = "whitebelyash",
                description = "Turnip driver with Adreno 750 / Snapdragon 8 Gen 3 pipeline bug fixes.",
                downloadUrl = "https://github.com/whitebelyash/AdrenoToolsDrivers/releases/download/v24.2.0/turnip-mesa-24.2.0.zip",
                fileName = "turnip-mesa-24.2.0.zip",
                fileSize = 14200000L,
                libraryName = "libvulkan_freedreno.so",
                sourceRepo = "whitebelyash/AdrenoToolsDrivers"
            )
        )
    }
}
