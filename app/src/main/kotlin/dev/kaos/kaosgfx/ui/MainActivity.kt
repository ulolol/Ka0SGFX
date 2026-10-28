package dev.kaos.kaosgfx.ui

import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.SportsEsports
import androidx.compose.material3.*
import androidx.compose.runtime.*
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
import dev.kaos.kaosgfx.privileged.ShizukuState
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
import kotlinx.coroutines.launch

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
            app.driverManager
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
        app.privilegedClient.checkStatus()
    }

    @Composable
    private fun MainContent() {
        var currentScreen by remember { mutableStateOf(Screen.GAMES) }

        val shizukuState by app.privilegedClient.shizukuState.collectAsState()

        var games by remember { mutableStateOf<List<GameApp>>(emptyList()) }
        var isGamesLoading by remember { mutableStateOf(false) }

        var catalogDrivers by remember { mutableStateOf<List<DriverMetadata>>(emptyList()) }
        var isCatalogLoading by remember { mutableStateOf(false) }
        val downloadingMap = remember { mutableStateMapOf<String, Int>() }

        var selectedGame by remember { mutableStateOf<GameApp?>(null) }
        var patchProgressStep by remember { mutableStateOf<String?>(null) }
        var patchProgressPercent by remember { mutableFloatStateOf(0f) }

        fun loadGames() {
            lifecycleScope.launch {
                isGamesLoading = true
                games = scanner.scanGames(includeAllApps = false)
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
            app.privilegedClient.checkStatus()
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
                        shizukuState = shizukuState,
                        onRefresh = { loadGames() },
                        onRequestShizuku = {
                            lifecycleScope.launch {
                                app.privilegedClient.requestPermission()
                            }
                        },
                        onSelectGame = { selectedGame = it }
                    )

                    Screen.CATALOG -> CatalogScreen(
                        drivers = catalogDrivers,
                        sources = DriverCatalogRepository.DEFAULT_SOURCES,
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
                        shizukuState = shizukuState
                    )
                }

                // Detail Sheet
                selectedGame?.let { game ->
                    GameDetailSheet(
                        game = game,
                        availableDrivers = catalogDrivers.filter { it.isInstalled },
                        onDismiss = { selectedGame = null },
                        onPatchGame = { targetGame, driver, enableAngle ->
                            selectedGame = null
                            lifecycleScope.launch {
                                patchProgressStep = "Starting patch workflow..."
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
                                    Toast.makeText(this@MainActivity, "Successfully injected ${driver.name} into ${targetGame.appName}!", Toast.LENGTH_LONG).show()
                                    loadGames()
                                } else {
                                    Toast.makeText(this@MainActivity, "Patch failed: ${res.exceptionOrNull()?.message}", Toast.LENGTH_LONG).show()
                                }
                            }
                        },
                        onUnpatchGame = { targetGame ->
                            selectedGame = null
                            lifecycleScope.launch {
                                patchProgressStep = "Restoring stock settings..."
                                patchProgressPercent = 0.5f
                                orchestrator.unpatchGame(targetGame, null)
                                patchProgressStep = null
                                Toast.makeText(this@MainActivity, "Restored stock configuration for ${targetGame.appName}", Toast.LENGTH_SHORT).show()
                                loadGames()
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
