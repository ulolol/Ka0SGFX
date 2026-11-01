package dev.kaos.kaosgfx.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.FileOpen
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.kaos.kaosgfx.drivers.AdrenoGpu
import dev.kaos.kaosgfx.drivers.CompatibilityStatus
import dev.kaos.kaosgfx.drivers.DriverMetadata
import dev.kaos.kaosgfx.drivers.DriverSource
import dev.kaos.kaosgfx.ui.components.GpuSupportBadges
import dev.kaos.kaosgfx.ui.theme.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CatalogScreen(
    drivers: List<DriverMetadata>,
    sources: List<DriverSource>,
    detectedGpu: AdrenoGpu,
    isLoading: Boolean,
    downloadingMap: Map<String, Int>, // driverId -> percent
    onRefresh: () -> Unit,
    onDownloadDriver: (DriverMetadata) -> Unit,
    onImportZip: (Uri) -> Unit,
    modifier: Modifier = Modifier
) {
    var selectedSourceId by rememberSaveable { mutableStateOf<String?>("all") }
    var showIncompatible by rememberSaveable { mutableStateOf(false) }

    val filePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) {
            onImportZip(uri)
        }
    }

    val sourceDrivers = remember(drivers, selectedSourceId) {
        if (selectedSourceId == null || selectedSourceId == "all") drivers
        else drivers.filter { it.id.startsWith(selectedSourceId!!) }
    }

    val hiddenIncompatibleCount = remember(sourceDrivers, detectedGpu) {
        sourceDrivers.count {
            it.gpuSupport.isCompatibleWith(detectedGpu) == CompatibilityStatus.INCOMPATIBLE
        }
    }

    val filteredDrivers = remember(sourceDrivers, detectedGpu, showIncompatible) {
        val visible = if (showIncompatible) {
            sourceDrivers
        } else {
            sourceDrivers.filter {
                it.gpuSupport.isCompatibleWith(detectedGpu) != CompatibilityStatus.INCOMPATIBLE
            }
        }
        visible.sortedWith(
            compareBy<DriverMetadata> {
                when (it.gpuSupport.isCompatibleWith(detectedGpu)) {
                    CompatibilityStatus.COMPATIBLE -> 0
                    CompatibilityStatus.TENTATIVE -> 1
                    CompatibilityStatus.UNVERIFIED -> 2
                    CompatibilityStatus.INCOMPATIBLE -> 3
                }
            }.thenByDescending { it.isInstalled }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Driver Catalog", fontWeight = FontWeight.Bold, fontSize = 20.sp, color = PrimaryNeon)
                        Text("Mesa Turnip & Adreno Drivers • Filtered for ${detectedGpu.gen.label}", fontSize = 12.sp, color = TextSecondary)
                    }
                },
                actions = {
                    IconButton(onClick = onRefresh) {
                        Icon(Icons.Default.Refresh, contentDescription = "Refresh", tint = TextPrimary)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = BgDark)
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = { filePicker.launch("application/zip") },
                containerColor = PrimaryNeon,
                contentColor = Color.Black
            ) {
                Icon(Icons.Default.FileOpen, contentDescription = "Import Driver ZIP")
            }
        },
        containerColor = BgDark,
        modifier = modifier
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .padding(horizontal = 16.dp)
        ) {
            // Hardware GPU Filter Bar
            Card(
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = SurfaceDark),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 10.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.Memory,
                        contentDescription = null,
                        tint = PrimaryNeon,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = detectedGpu.displayName,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 13.sp,
                            color = TextPrimary
                        )
                        Text(
                            text = if (showIncompatible) {
                                "Showing all ${filteredDrivers.size} drivers (including incompatible)"
                            } else {
                                "${filteredDrivers.size} compatible/unverified • $hiddenIncompatibleCount incompatible hidden"
                            },
                            fontSize = 11.sp,
                            color = TextSecondary
                        )
                    }
                    FilterChip(
                        selected = showIncompatible,
                        onClick = { showIncompatible = !showIncompatible },
                        label = {
                            Text(
                                text = if (showIncompatible) "Hide Incompatible" else "Show All (+$hiddenIncompatibleCount)",
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

            // Source Filter Chips
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.padding(bottom = 12.dp)
            ) {
                item {
                    FilterChip(
                        selected = selectedSourceId == "all",
                        onClick = { selectedSourceId = "all" },
                        label = { Text("All") },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = PrimaryNeon,
                            selectedLabelColor = Color.Black
                        )
                    )
                }
                items(sources) { source ->
                    FilterChip(
                        selected = selectedSourceId == source.id,
                        onClick = { selectedSourceId = source.id },
                        label = { Text(source.name) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = PrimaryNeon,
                            selectedLabelColor = Color.Black
                        )
                    )
                }
            }

            if (isLoading) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = PrimaryNeon)
                }
            } else if (filteredDrivers.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = if (hiddenIncompatibleCount > 0 && !showIncompatible) {
                                "No ${detectedGpu.gen.label}-compatible drivers in this source ($hiddenIncompatibleCount incompatible hidden)."
                            } else {
                                "No drivers found for this source."
                            },
                            color = TextSecondary,
                            fontSize = 13.sp
                        )
                        if (hiddenIncompatibleCount > 0 && !showIncompatible) {
                            Spacer(modifier = Modifier.height(8.dp))
                            OutlinedButton(onClick = { showIncompatible = true }) {
                                Text("Show Incompatible Drivers ($hiddenIncompatibleCount)", color = WarningOrange)
                            }
                        }
                    }
                }
            } else {
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    contentPadding = PaddingValues(bottom = 80.dp)
                ) {
                    items(filteredDrivers, key = { it.id }) { driver ->
                        DriverCatalogCard(
                            driver = driver,
                            detectedGpu = detectedGpu,
                            downloadProgress = downloadingMap[driver.id],
                            onDownload = { onDownloadDriver(driver) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun DriverCatalogCard(
    driver: DriverMetadata,
    detectedGpu: AdrenoGpu,
    downloadProgress: Int?,
    onDownload: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = SurfaceDark),
        modifier = modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = driver.name,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 15.sp,
                        color = TextPrimary
                    )
                    Text(
                        text = "${driver.author} • ${driver.version}",
                        fontSize = 12.sp,
                        color = TextSecondary
                    )
                }

                if (driver.isInstalled) {
                    FilledTonalButton(
                        onClick = {},
                        enabled = false,
                        colors = ButtonDefaults.filledTonalButtonColors(
                            disabledContainerColor = SuccessGreen.copy(alpha = 0.2f),
                            disabledContentColor = SuccessGreen
                        ),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Ready", fontSize = 12.sp)
                    }
                } else if (downloadProgress != null) {
                    CircularProgressIndicator(
                        progress = { downloadProgress / 100f },
                        color = PrimaryNeon,
                        modifier = Modifier.size(32.dp)
                    )
                } else {
                    Button(
                        onClick = onDownload,
                        colors = ButtonDefaults.buttonColors(containerColor = PrimaryNeon),
                        shape = RoundedCornerShape(8.dp),
                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp)
                    ) {
                        Icon(Icons.Default.Download, contentDescription = null, tint = Color.Black, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Get", color = Color.Black, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))
            GpuSupportBadges(driver = driver, detectedGpu = detectedGpu)

            if (driver.description.isNotBlank()) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = driver.description,
                    fontSize = 12.sp,
                    color = TextSecondary,
                    maxLines = 2
                )
            }
        }
    }
}
