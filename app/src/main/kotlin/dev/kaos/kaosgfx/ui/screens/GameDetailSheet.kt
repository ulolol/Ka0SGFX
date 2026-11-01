package dev.kaos.kaosgfx.ui.screens

import android.graphics.drawable.BitmapDrawable
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SportsEsports
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import dev.kaos.kaosgfx.domain.GameApp
import dev.kaos.kaosgfx.drivers.AdrenoGpu
import dev.kaos.kaosgfx.drivers.CompatibilityStatus
import dev.kaos.kaosgfx.drivers.DriverMetadata
import dev.kaos.kaosgfx.privileged.PrivilegedClient
import dev.kaos.kaosgfx.ui.components.GpuSupportBadges
import dev.kaos.kaosgfx.ui.components.SimpleBadge
import dev.kaos.kaosgfx.ui.theme.*
import kotlinx.coroutines.launch

private fun extractJsonField(json: String, field: String): String? {
    val key = "\"$field\""
    val idx = json.indexOf(key)
    if (idx == -1) return null
    val colon = json.indexOf(':', idx + key.length)
    if (colon == -1) return null
    val quoteStart = json.indexOf('\"', colon + 1)
    if (quoteStart == -1) return null
    val quoteEnd = json.indexOf('\"', quoteStart + 1)
    if (quoteEnd == -1) return null
    return json.substring(quoteStart + 1, quoteEnd)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GameDetailSheet(
    game: GameApp,
    availableDrivers: List<DriverMetadata>,
    detectedGpu: AdrenoGpu,
    isRootMode: Boolean = false,
    privilegedClient: PrivilegedClient? = null,
    onDismiss: () -> Unit,
    onPatchGame: (GameApp, DriverMetadata, Boolean) -> Unit,
    onUnpatchGame: (GameApp) -> Unit
) {
    // Read active staged driver config for this game
    var activeConfigJson by remember(game.packageName) {
        val directShared = java.io.File(
            android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS),
            "KaosGFX/configs/${game.packageName}.json"
        )
        val initialText = if (directShared.exists() && directShared.canRead()) {
            try { directShared.readText() } catch (e: Exception) { null }
        } else null
        mutableStateOf(initialText)
    }

    // Asynchronously query privileged service if direct shared config wasn't immediately found
    LaunchedEffect(game.packageName) {
        if (activeConfigJson.isNullOrBlank() && privilegedClient != null) {
            val res = privilegedClient.getDriverConfig(game.packageName)
            if (res.isSuccess && !res.getOrNull().isNullOrBlank()) {
                activeConfigJson = res.getOrNull()
            }
        }
    }

    val activeConfig = remember(activeConfigJson) {
        val json = activeConfigJson
        if (!json.isNullOrBlank()) {
            val driverDir = extractJsonField(json, "driverDir")
            val driverName = extractJsonField(json, "driverName")
            val driverId = extractJsonField(json, "driverId")
            val driverTitle = extractJsonField(json, "driverTitle")
            val enableAngle = json.contains("\"enableAngle\": true")
            val mode = extractJsonField(json, "mode") ?: "patch"
            val carrier = extractJsonField(json, "carrierPackage") ?: ""
            listOf(driverDir, driverName, driverId, driverTitle, if (enableAngle) "true" else "false", mode, carrier)
        } else null
    }

    // Match against available drivers to find the currently active driver
    val activeDriver = remember(activeConfig, availableDrivers) {
        if (activeConfig == null) null
        else {
            val driverDir = activeConfig.getOrNull(0)
            val driverId = activeConfig.getOrNull(2)
            val driverTitle = activeConfig.getOrNull(3)
            availableDrivers.firstOrNull { driver ->
                (!driverId.isNullOrBlank() && driver.id == driverId) ||
                (!driverTitle.isNullOrBlank() && (driver.name.equals(driverTitle, ignoreCase = true) || driverTitle.contains(driver.name))) ||
                (!driverDir.isNullOrBlank() && (
                    driverDir.contains(driver.id) ||
                    driverDir.contains(driver.fileName.substringBeforeLast("."))
                ))
            }
        }
    }

    val compatibleDrivers = remember(availableDrivers, detectedGpu) {
        availableDrivers.filter {
            it.gpuSupport.isCompatibleWith(detectedGpu) != CompatibilityStatus.INCOMPATIBLE
        }.sortedBy {
            when (it.gpuSupport.isCompatibleWith(detectedGpu)) {
                CompatibilityStatus.COMPATIBLE -> 0
                CompatibilityStatus.TENTATIVE -> 1
                CompatibilityStatus.UNVERIFIED -> 2
                CompatibilityStatus.INCOMPATIBLE -> 3
            }
        }
    }

    val hiddenIncompatibleCount = remember(availableDrivers, detectedGpu) {
        availableDrivers.count {
            it.gpuSupport.isCompatibleWith(detectedGpu) == CompatibilityStatus.INCOMPATIBLE
        }
    }

    var showIncompatibleDrivers by rememberSaveable { mutableStateOf(false) }

    var selectedDriver by remember(availableDrivers, detectedGpu) {
        mutableStateOf(
            activeDriver
                ?: compatibleDrivers.firstOrNull { it.isInstalled && it.gpuSupport.isCompatibleWith(detectedGpu) == CompatibilityStatus.COMPATIBLE }
                ?: compatibleDrivers.firstOrNull { it.gpuSupport.isCompatibleWith(detectedGpu) == CompatibilityStatus.COMPATIBLE }
                ?: compatibleDrivers.firstOrNull { it.isInstalled }
                ?: compatibleDrivers.firstOrNull()
                ?: availableDrivers.firstOrNull()
        )
    }

    // Keep selectedDriver aligned when activeDriver is resolved asynchronously
    LaunchedEffect(activeDriver) {
        if (activeDriver != null) {
            selectedDriver = activeDriver
        }
    }

    var enableAngle by remember(activeConfig) {
        mutableStateOf(activeConfig?.getOrNull(4) == "true")
    }
    var driverSearchQuery by rememberSaveable { mutableStateOf("") }

    val filteredDrivers = remember(availableDrivers, compatibleDrivers, showIncompatibleDrivers, driverSearchQuery) {
        val baseList = if (showIncompatibleDrivers) availableDrivers else compatibleDrivers
        if (driverSearchQuery.isBlank()) baseList
        else baseList.filter {
            it.name.contains(driverSearchQuery, ignoreCase = true) ||
                    it.version.contains(driverSearchQuery, ignoreCase = true) ||
                    it.author.contains(driverSearchQuery, ignoreCase = true) ||
                    it.libraryName.contains(driverSearchQuery, ignoreCase = true)
        }
    }

    var livePid by remember { mutableStateOf<Int?>(null) }
    var showLogDialog by remember { mutableStateOf(false) }
    var logContent by remember { mutableStateOf("") }
    var isLogLoading by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    // Poll live PID if custom driver is active
    LaunchedEffect(game.packageName, game.hasCustomDriver) {
        if (game.hasCustomDriver && privilegedClient != null) {
            val res = privilegedClient.getPackageProcessId(game.packageName)
            livePid = res.getOrNull()
        }
    }

    fun refreshLogs() {
        if (privilegedClient == null) return
        scope.launch {
            isLogLoading = true
            val logs = privilegedClient.getPipelineLogs(game.packageName, 150).getOrDefault("No logs found.")
            logContent = logs
            val pidRes = privilegedClient.getPackageProcessId(game.packageName)
            livePid = pidRes.getOrNull()
            isLogLoading = false
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = SurfaceDark,
        shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp)
    ) {
        Column(
            modifier = Modifier
                .padding(horizontal = 20.dp)
                .padding(bottom = 32.dp)
                .verticalScroll(rememberScrollState())
        ) {
            // Header
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                val bmp = (game.icon as? BitmapDrawable)?.bitmap
                if (bmp != null) {
                    Image(
                        bitmap = bmp.asImageBitmap(),
                        contentDescription = null,
                        modifier = Modifier
                            .size(54.dp)
                            .clip(RoundedCornerShape(12.dp))
                    )
                } else {
                    Box(
                        modifier = Modifier
                            .size(54.dp)
                            .background(SurfaceVariantDark, RoundedCornerShape(12.dp)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(Icons.Default.SportsEsports, contentDescription = null, tint = PrimaryNeon)
                    }
                }

                Spacer(modifier = Modifier.width(16.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = game.appName,
                        fontWeight = FontWeight.Bold,
                        fontSize = 18.sp,
                        color = TextPrimary
                    )
                    Text(
                        text = "${game.packageName} • v${game.versionName}",
                        fontSize = 12.sp,
                        color = TextSecondary
                    )
                }

                IconButton(onClick = onDismiss) {
                    Icon(Icons.Default.Close, contentDescription = "Close", tint = TextSecondary)
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Denylist Warning
            if (game.isDenylisted && !isRootMode) {
                Card(
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(containerColor = ErrorRed.copy(alpha = 0.15f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.Warning, contentDescription = null, tint = ErrorRed)
                        Spacer(modifier = Modifier.width(12.dp))
                        Column {
                            Text(
                                text = "Anti-Cheat Protected Game",
                                fontWeight = FontWeight.Bold,
                                color = ErrorRed,
                                fontSize = 14.sp
                            )
                            Text(
                                text = game.denylistReason ?: "Competitive multiplayer title blocked to protect account safety.",
                                color = TextSecondary,
                                fontSize = 12.sp
                            )
                        }
                    }
                }
            } else {
                // Live Pipeline Status Card (if Patched or Root Active)
                if (game.hasCustomDriver) {
                    val activeCarrier = activeConfig?.getOrNull(6).orEmpty()
                    Card(
                        shape = RoundedCornerShape(12.dp),
                        colors = CardDefaults.cardColors(containerColor = SurfaceVariantDark),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(14.dp)) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Icon(Icons.Default.CheckCircle, contentDescription = null, tint = PrimaryNeon, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = if (game.isRootActive) "Root Zero-Patch Pipeline Active" else "Custom Driver Pipeline Active",
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 14.sp,
                                    color = PrimaryNeon
                                )
                                Spacer(modifier = Modifier.weight(1f))
                                if (livePid != null && livePid!! > 0) {
                                    SimpleBadge(text = "Running (PID: $livePid)", color = PrimaryNeon)
                                } else {
                                    SimpleBadge(text = "Idle", color = TextSecondary)
                                }
                            }

                            Spacer(modifier = Modifier.height(10.dp))
                            Text(
                                text = if (game.isRootActive) {
                                    "Bootstrap: GraphicsEnvironment Carrier (${activeCarrier.ifBlank { "ro.gfx.driver.0" }})\nEngine: Native Linker Namespace Unload + Custom Vulkan HAL (Zero APK Patch)"
                                } else {
                                    "Bootstrap: TurnipAppComponentFactory (Injected)\nEngine: libadrenotools + In-process PLT Interceptor"
                                },
                                fontSize = 12.sp,
                                color = TextSecondary,
                                lineHeight = 16.sp
                            )

                            Spacer(modifier = Modifier.height(12.dp))
                            Button(
                                onClick = {
                                    refreshLogs()
                                    showLogDialog = true
                                },
                                shape = RoundedCornerShape(8.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = SurfaceDark),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Icon(Icons.Default.Terminal, contentDescription = null, tint = PrimaryNeon, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Inspect Live Pipeline Logs", fontSize = 13.sp, color = TextPrimary)
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))
                }

                // ANGLE GLES Toggle
                Card(
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(containerColor = SurfaceVariantDark),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Enable ANGLE for OpenGL ES", fontWeight = FontWeight.SemiBold, fontSize = 14.sp, color = TextPrimary)
                            Text("Translates OpenGL ES calls to Vulkan through ANGLE runtime.", fontSize = 12.sp, color = TextSecondary)
                        }
                        Switch(
                            checked = enableAngle,
                            onCheckedChange = { enableAngle = it },
                            colors = SwitchDefaults.colors(checkedThumbColor = PrimaryNeon, checkedTrackColor = PrimaryBlue)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                // Action Buttons: Apply/Update & Restore
                val buttonLabel = when {
                    game.hasCustomDriver && isRootMode -> "Update Root Driver (Zero APK Patch)"
                    game.hasCustomDriver -> "Update Custom Driver"
                    isRootMode -> "Apply Driver (Root — Zero APK Patch)"
                    else -> "Patch & Enable Custom Driver"
                }

                Button(
                    onClick = {
                        val drv = selectedDriver
                        if (drv != null) {
                            onPatchGame(game, drv, enableAngle)
                        }
                    },
                    enabled = selectedDriver != null && (!game.isDenylisted || isRootMode),
                    colors = ButtonDefaults.buttonColors(containerColor = PrimaryNeon),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(50.dp)
                ) {
                    Text(
                        buttonLabel,
                        color = Color.Black,
                        fontWeight = FontWeight.Bold,
                        fontSize = 15.sp
                    )
                }

                if (game.hasCustomDriver) {
                    Spacer(modifier = Modifier.height(10.dp))
                    OutlinedButton(
                        onClick = { onUnpatchGame(game) },
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = ErrorRed),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp)
                    ) {
                        Text("Restore to Stock Configuration", fontWeight = FontWeight.SemiBold)
                    }
                }

                Spacer(modifier = Modifier.height(20.dp))

                // Active / Selected Driver Summary
                Card(
                    shape = RoundedCornerShape(10.dp),
                    colors = CardDefaults.cardColors(containerColor = SurfaceVariantDark.copy(alpha = 0.5f)),
                    border = androidx.compose.foundation.BorderStroke(1.dp, PrimaryNeon.copy(alpha = 0.3f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        if (activeDriver != null) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(
                                    text = "Active in App: ${activeDriver.name}",
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 13.sp,
                                    color = PrimaryNeon,
                                    modifier = Modifier.weight(1f, fill = false),
                                    maxLines = 1,
                                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                SimpleBadge(text = "Currently Active", color = PrimaryNeon)
                            }
                            Text(
                                text = "Author: ${activeDriver.author} • Target: ${activeDriver.libraryName}",
                                fontSize = 11.sp,
                                color = TextSecondary
                            )
                            if (selectedDriver != null && selectedDriver?.id != activeDriver.id) {
                                HorizontalDivider(color = SurfaceDark, modifier = Modifier.padding(vertical = 8.dp))
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text(
                                        text = "Selected for Update: ${selectedDriver!!.name}",
                                        fontWeight = FontWeight.SemiBold,
                                        fontSize = 12.sp,
                                        color = TextPrimary,
                                        modifier = Modifier.weight(1f, fill = false),
                                        maxLines = 1,
                                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                                    )
                                }
                                Text(
                                    text = "Tap '$buttonLabel' above to switch to this driver.",
                                    fontSize = 11.sp,
                                    color = PrimaryBlue
                                )
                            }
                        } else if (selectedDriver != null) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(
                                    text = "Selected Target: ${selectedDriver!!.name}",
                                    fontWeight = FontWeight.SemiBold,
                                    fontSize = 13.sp,
                                    color = TextPrimary,
                                    modifier = Modifier.weight(1f, fill = false),
                                    maxLines = 1,
                                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                if (selectedDriver!!.isInstalled) {
                                    SimpleBadge(text = "Downloaded", color = PrimaryNeon)
                                }
                            }
                            Text(
                                text = "Author: ${selectedDriver!!.author} • Target: ${selectedDriver!!.libraryName}",
                                fontSize = 11.sp,
                                color = TextSecondary
                            )
                        }
                    }
                }
                Spacer(modifier = Modifier.height(16.dp))

                // Driver Filter Header + Toggle
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Available Drivers (${filteredDrivers.size})",
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 15.sp,
                            color = TextPrimary
                        )
                        Text(
                            text = "Hardware filter: ${detectedGpu.displayName}",
                            fontSize = 11.sp,
                            color = TextSecondary
                        )
                    }
                    if (hiddenIncompatibleCount > 0) {
                        FilterChip(
                            selected = showIncompatibleDrivers,
                            onClick = { showIncompatibleDrivers = !showIncompatibleDrivers },
                            label = {
                                Text(
                                    text = if (showIncompatibleDrivers) "Hide Incompatible" else "Show All (+$hiddenIncompatibleCount)",
                                    fontSize = 11.sp
                                )
                            },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = WarningOrange.copy(alpha = 0.25f),
                                selectedLabelColor = WarningOrange
                            )
                        )
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                OutlinedTextField(
                    value = driverSearchQuery,
                    onValueChange = { driverSearchQuery = it },
                    placeholder = { Text("Search drivers (name, author, version)...", color = TextSecondary, fontSize = 13.sp) },
                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, tint = TextSecondary, modifier = Modifier.size(18.dp)) },
                    trailingIcon = {
                        if (driverSearchQuery.isNotEmpty()) {
                            IconButton(onClick = { driverSearchQuery = "" }) {
                                Icon(Icons.Default.Close, contentDescription = "Clear", tint = TextSecondary, modifier = Modifier.size(18.dp))
                            }
                        }
                    },
                    singleLine = true,
                    shape = RoundedCornerShape(10.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = SurfaceVariantDark,
                        unfocusedContainerColor = SurfaceVariantDark,
                        focusedBorderColor = PrimaryNeon,
                        unfocusedBorderColor = Color.Transparent,
                        focusedTextColor = TextPrimary,
                        unfocusedTextColor = TextPrimary
                    ),
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(8.dp))

                if (filteredDrivers.isEmpty()) {
                    Text(
                        text = if (driverSearchQuery.isNotBlank()) "No drivers match '$driverSearchQuery'." else "No compatible drivers found. Toggle 'Show All' or visit the Catalog tab.",
                        fontSize = 13.sp,
                        color = TextSecondary,
                        modifier = Modifier.padding(vertical = 12.dp)
                    )
                } else {
                    filteredDrivers.forEach { driver ->
                        Card(
                            shape = RoundedCornerShape(10.dp),
                            colors = CardDefaults.cardColors(
                                containerColor = if (selectedDriver?.id == driver.id) SurfaceVariantDark else Color.Transparent
                            ),
                            border = if (selectedDriver?.id == driver.id) CardDefaults.outlinedCardBorder().copy(brush = androidx.compose.ui.graphics.SolidColor(PrimaryNeon)) else null,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp)
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                RadioButton(
                                    selected = selectedDriver?.id == driver.id,
                                    onClick = { selectedDriver = driver },
                                    colors = RadioButtonDefaults.colors(selectedColor = PrimaryNeon)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Text(
                                            text = driver.name,
                                            fontWeight = FontWeight.Medium,
                                            fontSize = 14.sp,
                                            color = TextPrimary,
                                            modifier = Modifier.weight(1f, fill = false),
                                            maxLines = 1,
                                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                                        )
                                        if (driver.isInstalled) {
                                            Spacer(modifier = Modifier.width(6.dp))
                                            SimpleBadge(text = "Downloaded", color = PrimaryNeon)
                                        }
                                    }
                                    Text("Author: ${driver.author} • Target: ${driver.libraryName}", fontSize = 11.sp, color = TextSecondary)
                                    Spacer(modifier = Modifier.height(4.dp))
                                    GpuSupportBadges(driver = driver, detectedGpu = detectedGpu)
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // Live Logs Dialog
    if (showLogDialog) {
        Dialog(onDismissRequest = { showLogDialog = false }) {
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = Color(0xFF0A0D14),
                border = androidx.compose.foundation.BorderStroke(1.dp, PrimaryNeon.copy(alpha = 0.4f)),
                modifier = Modifier
                    .fillMaxWidth()
                    .fillMaxHeight(0.8f)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.Terminal, contentDescription = null, tint = PrimaryNeon, modifier = Modifier.size(20.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Pipeline Logcat Stream",
                            fontWeight = FontWeight.Bold,
                            fontSize = 15.sp,
                            color = PrimaryNeon
                        )
                        Spacer(modifier = Modifier.weight(1f))
                        IconButton(
                            onClick = { refreshLogs() },
                            enabled = !isLogLoading
                        ) {
                            Icon(Icons.Default.Refresh, contentDescription = "Refresh", tint = PrimaryNeon)
                        }
                        IconButton(onClick = { showLogDialog = false }) {
                            Icon(Icons.Default.Close, contentDescription = "Close", tint = TextSecondary)
                        }
                    }

                    HorizontalDivider(color = SurfaceVariantDark, modifier = Modifier.padding(vertical = 8.dp))

                    if (isLogLoading) {
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxWidth(),
                            contentAlignment = Alignment.Center
                        ) {
                            CircularProgressIndicator(color = PrimaryNeon)
                        }
                    } else {
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxWidth()
                                .background(Color(0xFF05070A), RoundedCornerShape(8.dp))
                                .padding(12.dp)
                                .verticalScroll(rememberScrollState())
                        ) {
                            Text(
                                text = if (logContent.isBlank()) "No pipeline logs detected yet. Launch ${game.appName} to generate logs." else logContent,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 11.sp,
                                color = TextPrimary,
                                lineHeight = 15.sp
                            )
                        }
                    }
                }
            }
        }
    }
}
