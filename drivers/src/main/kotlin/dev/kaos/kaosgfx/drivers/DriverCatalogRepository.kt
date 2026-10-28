package dev.kaos.kaosgfx.drivers

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
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

    private val storageDir get() = DriverStorage.getDriversDir(context)

    private fun getCatalogCacheFile(): File {
        val sharedDir = DriverStorage.getDriversDir(context)
        return File(sharedDir, "catalog_cache.json")
    }

    private fun loadPersistentCatalogCache(): MutableList<DriverMetadata> {
        val file = getCatalogCacheFile()
        if (file.exists() && file.canRead()) {
            try {
                val text = file.readText()
                return json.decodeFromString<List<DriverMetadata>>(text).toMutableList()
            } catch (e: Exception) {
                Log.w(TAG, "Failed reading catalog cache: ${e.message}")
            }
        }
        return mutableListOf()
    }

    private fun savePersistentCatalogCache(drivers: List<DriverMetadata>) {
        try {
            val file = getCatalogCacheFile()
            val text = json.encodeToString(drivers)
            file.writeText(text)
        } catch (e: Exception) {
            Log.w(TAG, "Failed saving catalog cache: ${e.message}")
        }
    }

    suspend fun fetchAllSources(sources: List<DriverSource> = DEFAULT_SOURCES): List<DriverMetadata> = withContext(Dispatchers.IO) {
        val allDrivers = mutableListOf<DriverMetadata>()
        val cachedCatalog = loadPersistentCatalogCache()
        val builtin = getBuiltinCatalog()

        for (source in sources) {
            if (!source.isEnabled) continue
            try {
                val drivers = fetchReleasesForSource(source)
                if (drivers.isNotEmpty()) {
                    allDrivers.addAll(drivers)
                } else {
                    // Fall back to cached drivers for this source if rate limited or network failure
                    val cachedForSource = cachedCatalog.filter {
                        it.sourceRepo.equals("${source.repoOwner}/${source.repoName}", ignoreCase = true) ||
                        it.id.startsWith(source.id, ignoreCase = true)
                    }
                    if (cachedForSource.isNotEmpty()) {
                        allDrivers.addAll(cachedForSource)
                    } else {
                        val builtinForSource = builtin.filter {
                            it.sourceRepo.equals("${source.repoOwner}/${source.repoName}", ignoreCase = true) ||
                            it.id.startsWith(source.id, ignoreCase = true)
                        }
                        allDrivers.addAll(builtinForSource)
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to fetch from ${source.name} (${source.repoOwner}/${source.repoName})", e)
                val fallbackForSource = cachedCatalog.filter {
                    it.sourceRepo.equals("${source.repoOwner}/${source.repoName}", ignoreCase = true) ||
                    it.id.startsWith(source.id, ignoreCase = true)
                }.ifEmpty {
                    builtin.filter {
                        it.sourceRepo.equals("${source.repoOwner}/${source.repoName}", ignoreCase = true) ||
                        it.id.startsWith(source.id, ignoreCase = true)
                    }
                }
                allDrivers.addAll(fallbackForSource)
            }
        }

        // Add built-in fallback/cached entries if list is empty
        if (allDrivers.isEmpty()) {
            allDrivers.addAll(cachedCatalog.ifEmpty { builtin })
        }

        // Deduplicate by ID
        val distinctDrivers = allDrivers.distinctBy { it.id }.toMutableList()

        // ALWAYS include all locally installed/downloaded drivers from storageDir
        val localInstalled = getInstalledDrivers()
        for (local in localInstalled) {
            if (distinctDrivers.none { it.fileName.equals(local.fileName, ignoreCase = true) }) {
                distinctDrivers.add(local)
            }
        }

        // Save updated cache
        savePersistentCatalogCache(distinctDrivers)

        // Mark installed status
        markInstalledDrivers(distinctDrivers)
        distinctDrivers
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
        val dir = storageDir
        val installedFiles = dir.listFiles { _, name -> name.endsWith(".zip", ignoreCase = true) } ?: emptyArray()
        val installedMap = installedFiles.associateBy { it.name.lowercase() }

        for (driver in drivers) {
            val localZip = installedMap[driver.fileName.lowercase()]
                ?: installedFiles.firstOrNull { file ->
                    file.name.equals(driver.fileName, ignoreCase = true) ||
                    file.name.contains(driver.id, ignoreCase = true) ||
                    (driver.fileName.isNotBlank() && file.name.contains(driver.fileName.substringBeforeLast("."), ignoreCase = true))
                }

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
            ),
            DriverMetadata(
                id = "mrpurple_vturnip_mrpurple_T30-toasted.adpkg_turnip_mrpurple_T30-toasted.adpkg",
                name = "turnip_mrpurple_T30-toasted.adpkg - turnip_mrpurple_T30-toasted.adpkg.zip",
                version = "vturnip_mrpurple_T30-toasted.adpkg",
                author = "MrPurple666",
                description = "Turnip Mesa T30 driver with optimized Adreno 750 / 8 Gen 3 pipeline support.",
                downloadUrl = "https://github.com/MrPurple666/purple-turnip/releases/download/vturnip_mrpurple_T30-toasted.adpkg/turnip_mrpurple_T30-toasted.adpkg.zip",
                fileName = "turnip_mrpurple_T30-toasted.adpkg.zip",
                fileSize = 3713730L,
                libraryName = "libvulkan_freedreno.so",
                sourceRepo = "MrPurple666/purple-turnip"
            ),
            DriverMetadata(
                id = "mrpurple_vturnip_mrpurple_T29-toasted.adpkg_turnip_mrpurple_T29-toasted.adpkg",
                name = "turnip_mrpurple_T29-toasted.adpkg - turnip_mrpurple_T29-toasted.adpkg.zip",
                version = "vturnip_mrpurple_T29-toasted.adpkg",
                author = "MrPurple666",
                description = "Turnip Mesa T29 driver with optimized Adreno 750 / 8 Gen 3 pipeline support.",
                downloadUrl = "https://github.com/MrPurple666/purple-turnip/releases/download/vturnip_mrpurple_T29-toasted.adpkg/turnip_mrpurple_T29-toasted.adpkg.zip",
                fileName = "turnip_mrpurple_T29-toasted.adpkg.zip",
                fileSize = 3680000L,
                libraryName = "libvulkan_freedreno.so",
                sourceRepo = "MrPurple666/purple-turnip"
            ),
            DriverMetadata(
                id = "mrpurple_vturnip_mrpurple_T28-toasted.adpkg_turnip_mrpurple_T28-toasted.adpkg",
                name = "turnip_mrpurple_T28-toasted.adpkg - turnip_mrpurple_T28-toasted.adpkg.zip",
                version = "vturnip_mrpurple_T28-toasted.adpkg",
                author = "MrPurple666",
                description = "Turnip Mesa T28 driver with optimized Adreno 750 / 8 Gen 3 pipeline support.",
                downloadUrl = "https://github.com/MrPurple666/purple-turnip/releases/download/vturnip_mrpurple_T28-toasted.adpkg/turnip_mrpurple_T28-toasted.adpkg.zip",
                fileName = "turnip_mrpurple_T28-toasted.adpkg.zip",
                fileSize = 3650000L,
                libraryName = "libvulkan_freedreno.so",
                sourceRepo = "MrPurple666/purple-turnip"
            ),
            DriverMetadata(
                id = "k11mch1_v840_Qualcomm_840_adpkg",
                name = "Qualcomm Driver v840 - Qualcomm_840_adpkg.zip",
                version = "v840",
                author = "K11MCH1",
                description = "Stock Qualcomm Proprietary Adreno Driver v840 package.",
                downloadUrl = "https://github.com/K11MCH1/AdrenoToolsDrivers/releases/download/v840/Qualcomm_840_adpkg.zip",
                fileName = "Qualcomm_840_adpkg.zip",
                fileSize = 17385120L,
                libraryName = "vkgsl.so",
                sourceRepo = "K11MCH1/AdrenoToolsDrivers"
            ),
            DriverMetadata(
                id = "k11mch1_v837_Qualcomm_837_adpkg",
                name = "Qualcomm Driver v837 - Qualcomm_837_adpkg.zip",
                version = "v837",
                author = "K11MCH1",
                description = "Stock Qualcomm Proprietary Adreno Driver v837 package.",
                downloadUrl = "https://github.com/K11MCH1/AdrenoToolsDrivers/releases/download/v837/Qualcomm_837_adpkg.zip",
                fileName = "Qualcomm_837_adpkg.zip",
                fileSize = 17200000L,
                libraryName = "vkgsl.so",
                sourceRepo = "K11MCH1/AdrenoToolsDrivers"
            )
        )
    }
}
