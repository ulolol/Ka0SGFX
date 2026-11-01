package dev.kaos.kaosgfx.ui

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.SportsEsports
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.lifecycle.lifecycleScope
import dev.kaos.kaosgfx.KaosGFXApplication
import dev.kaos.kaosgfx.domain.GameApp
import dev.kaos.kaosgfx.domain.GameScanner
import dev.kaos.kaosgfx.domain.PatchOrchestrator
import dev.kaos.kaosgfx.drivers.DownloadProgress
import dev.kaos.kaosgfx.drivers.DriverCatalogRepository
import dev.kaos.kaosgfx.drivers.DriverMetadata
import dev.kaos.kaosgfx.drivers.GpuDetector
import dev.kaos.kaosgfx.privileged.OperatingMode
import dev.kaos.kaosgfx.ui.components.ProgressModal
import dev.kaos.kaosgfx.ui.screens.CatalogScreen
import dev.kaos.kaosgfx.ui.screens.DiagnosticsScreen
import dev.kaos.kaosgfx.ui.screens.GameDetailSheet
import dev.kaos.kaosgfx.ui.screens.GamesScreen
import dev.kaos.kaosgfx.ui.theme.BgDark
import dev.kaos.kaosgfx.ui.theme.KaosGFXTheme
import dev.kaos.kaosgfx.ui.theme.PrimaryNeon
import dev.kaos.kaosgfx.ui.theme.SurfaceDark
import dev.kaos.kaosgfx.ui.theme.TextSecondary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class Screen(val title: String, val icon: androidx.compose.ui.graphics.vector.ImageVector) {
    GAMES("Games", Icons.Default.SportsEsports),
    CATALOG("Catalog", Icons.Default.Download),
    DIAGNOSTICS("Diagnostics", Icons.Default.Info)
}

class MainActivity : ComponentActivity() {

    private val app by lazy { KaosGFXApplication.instance }
    private val scanner by lazy { GameScanner(this) }
    private val orchestrator by lazy {
        PatchOrchestrator(
            this,
            app.patcherEngine,
            app.privilegedClient,
            app.driverManager,
            app.driverDownloader
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            KaosGFXTheme {
                MainContent()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        lifecycleScope.launch {
            app.privilegedClient.refreshCapabilities()
        }
    }

    @Composable
    private fun MainContent() {
        var currentScreen by rememberSaveable { mutableStateOf(Screen.GAMES) }

        val shizukuState by app.privilegedClient.shizukuState.collectAsState()
        val rootStatus by app.privilegedClient.rootStatus.collectAsState()
        val operatingMode by app.privilegedClient.operatingMode.collectAsState()
        val modePreference by app.privilegedClient.modePreference.collectAsState()

        var detectedGpu by remember { mutableStateOf(GpuDetector.detect()) }

        var games by remember { mutableStateOf<List<GameApp>>(emptyList()) }
        var isGamesLoading by remember { mutableStateOf(false) }

        var catalogDrivers by remember { mutableStateOf<List<DriverMetadata>>(emptyList()) }
        var isCatalogLoading by remember { mutableStateOf(false) }
        val downloadingMap = remember { mutableStateMapOf<String, Int>() }

        var selectedGamePackage by rememberSaveable { mutableStateOf<String?>(null) }
        val selectedGame = remember(games, selectedGamePackage) {
            selectedGamePackage?.let { pkg -> games.find { it.packageName == pkg } }
        }
        var patchProgressStep by remember { mutableStateOf<String?>(null) }
        var patchProgressPercent by remember { mutableFloatStateOf(0f) }

        val storageLauncher = rememberLauncherForActivityResult(
            contract = ActivityResultContracts.StartActivityForResult()
        ) {
            // Refreshes view state when returning from settings
        }

        fun requestStoragePermission() {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                try {
                    val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                        data = Uri.parse("package:$packageName")
                    }
                    storageLauncher.launch(intent)
                } catch (e: Exception) {
                    val intent = Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
                    storageLauncher.launch(intent)
                }
            }
        }

        var includeSystemApps by rememberSaveable { mutableStateOf(false) }

        fun loadGames(includeSystem: Boolean = includeSystemApps) {
            lifecycleScope.launch {
                isGamesLoading = true
                games = scanner.scanGames(includeAllApps = includeSystem)
                isGamesLoading = false
            }
        }

        fun loadCatalog() {
            lifecycleScope.launch {
                isCatalogLoading = true
                catalogDrivers = app.catalogRepository.fetchAllSources()
                isCatalogLoading = false
            }
        }

        LaunchedEffect(Unit) {
            withContext(Dispatchers.IO) {
                detectedGpu = GpuDetector.detect()
            }
            app.privilegedClient.refreshCapabilities()
            loadGames()
            loadCatalog()
        }

        Scaffold(
            bottomBar = {
                NavigationBar(
                    containerColor = SurfaceDark,
                    contentColor = PrimaryNeon
                ) {
                    Screen.entries.forEach { screen ->
                        NavigationBarItem(
                            selected = currentScreen == screen,
                            onClick = { currentScreen = screen },
                            icon = { Icon(screen.icon, contentDescription = screen.title) },
                            label = { Text(screen.title) },
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = Color.Black,
                                selectedTextColor = PrimaryNeon,
                                indicatorColor = PrimaryNeon,
                                unselectedIconColor = TextSecondary,
                                unselectedTextColor = TextSecondary
                            )
                        )
                    }
                }
            },
            containerColor = BgDark
        ) { padding ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
            ) {
                when (currentScreen) {
                    Screen.GAMES -> GamesScreen(
                        games = games,
                        isLoading = isGamesLoading,
                        detectedGpu = detectedGpu,
                        operatingMode = operatingMode,
                        rootStatus = rootStatus,
                        shizukuState = shizukuState,
                        modePreference = modePreference,
                        onChangeModePreference = { pref ->
                            app.privilegedClient.setModePreference(pref)
                        },
                        includeSystemApps = includeSystemApps,
                        onToggleSystemApps = {
                            includeSystemApps = it
                            loadGames(it)
                        },
                        onRefresh = {
                            lifecycleScope.launch {
                                app.privilegedClient.refreshCapabilities()
                                loadGames(includeSystemApps)
                            }
                        },
                        onRequestShizuku = {
                            lifecycleScope.launch {
                                app.privilegedClient.requestPermission()
                                app.privilegedClient.refreshCapabilities()
                            }
                        },
                        onRequestStorage = { requestStoragePermission() },
                        onSelectGame = { selectedGamePackage = it.packageName }
                    )

                    Screen.CATALOG -> CatalogScreen(
                        drivers = catalogDrivers,
                        sources = DriverCatalogRepository.DEFAULT_SOURCES,
                        detectedGpu = detectedGpu,
                        isLoading = isCatalogLoading,
                        downloadingMap = downloadingMap,
                        onRefresh = { loadCatalog() },
                        onDownloadDriver = { driver ->
                            lifecycleScope.launch {
                                app.driverDownloader.downloadDriver(driver).collect { progress ->
                                    when (progress) {
                                        is DownloadProgress.Progress -> {
                                            downloadingMap[driver.id] = progress.percentage
                                        }
                                        is DownloadProgress.Completed -> {
                                            downloadingMap.remove(driver.id)
                                            Toast.makeText(this@MainActivity, "Downloaded ${driver.name}", Toast.LENGTH_SHORT).show()
                                            loadCatalog()
                                        }
                                        is DownloadProgress.Failed -> {
                                            downloadingMap.remove(driver.id)
                                            Toast.makeText(this@MainActivity, "Download failed: ${progress.error.message}", Toast.LENGTH_LONG).show()
                                        }
                                    }
                                }
                            }
                        },
                        onImportZip = { uri ->
                            lifecycleScope.launch {
                                val res = app.driverManager.importDriverFromUri(uri, "custom_driver_${System.currentTimeMillis()}.zip")
                                if (res.isSuccess) {
                                    Toast.makeText(this@MainActivity, "Custom driver imported!", Toast.LENGTH_SHORT).show()
                                    loadCatalog()
                                } else {
                                    Toast.makeText(this@MainActivity, "Failed to import: ${res.exceptionOrNull()?.message}", Toast.LENGTH_LONG).show()
                                }
                            }
                        }
                    )

                    Screen.DIAGNOSTICS -> DiagnosticsScreen(
                        detectedGpu = detectedGpu,
                        operatingMode = operatingMode,
                        rootStatus = rootStatus,
                        shizukuState = shizukuState,
                        modePreference = modePreference,
                        onChangeModePreference = { pref ->
                            app.privilegedClient.setModePreference(pref)
                        },
                        privilegedClient = app.privilegedClient
                    )
                }

                // Detail Sheet
                selectedGame?.let { game ->
                    GameDetailSheet(
                        game = game,
                        availableDrivers = catalogDrivers,
                        detectedGpu = detectedGpu,
                        isRootMode = operatingMode == OperatingMode.ROOT,
                        privilegedClient = app.privilegedClient,
                        onDismiss = { selectedGamePackage = null },
                        onPatchGame = { targetGame, driver, enableAngle ->
                            selectedGamePackage = null
                            lifecycleScope.launch {
                                patchProgressStep = if (operatingMode == OperatingMode.ROOT) {
                                    "Starting Root zero-patch driver workflow..."
                                } else {
                                    "Starting APK patch workflow..."
                                }
                                patchProgressPercent = 0.05f

                                val res = orchestrator.patchAndInstallGame(
                                    targetGame,
                                    driver,
                                    enableAngle
                                ) { step, progress ->
                                    patchProgressStep = step
                                    patchProgressPercent = progress
                                }

                                patchProgressStep = null
                                if (res.isSuccess) {
                                    val msg = if (operatingMode == OperatingMode.ROOT) {
                                        "Root driver ${driver.name} enabled for ${targetGame.appName} (Zero APK Patch)!"
                                    } else {
                                        "Successfully injected ${driver.name} into ${targetGame.appName}!"
                                    }
                                    Toast.makeText(this@MainActivity, msg, Toast.LENGTH_LONG).show()
                                    app.privilegedClient.refreshCapabilities()
                                    loadGames()
                                    loadCatalog()
                                } else {
                                    Toast.makeText(this@MainActivity, "Failed: ${res.exceptionOrNull()?.message}", Toast.LENGTH_LONG).show()
                                }
                            }
                        },
                        onUnpatchGame = { targetGame ->
                            selectedGamePackage = null
                            lifecycleScope.launch {
                                patchProgressStep = "Restoring stock configuration..."
                                patchProgressPercent = 0.1f
                                val res = orchestrator.unpatchGame(targetGame) { step, progress ->
                                    patchProgressStep = step
                                    patchProgressPercent = progress
                                }
                                patchProgressStep = null
                                if (res.isSuccess) {
                                    Toast.makeText(this@MainActivity, "Restored stock configuration for ${targetGame.appName}", Toast.LENGTH_SHORT).show()
                                    app.privilegedClient.refreshCapabilities()
                                    loadGames()
                                    loadCatalog()
                                } else {
                                    Toast.makeText(this@MainActivity, "Restore failed: ${res.exceptionOrNull()?.message}", Toast.LENGTH_LONG).show()
                                }
                            }
                        }
                    )
                }

                // Progress Modal
                patchProgressStep?.let { step ->
                    ProgressModal(
                        step = step,
                        progress = patchProgressPercent,
                        onDismiss = {}
                    )
                }
            }
        }
    }
}
