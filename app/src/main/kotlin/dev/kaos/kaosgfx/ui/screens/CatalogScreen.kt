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
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.kaos.kaosgfx.drivers.DriverMetadata
import dev.kaos.kaosgfx.drivers.DriverSource
import dev.kaos.kaosgfx.ui.theme.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CatalogScreen(
    drivers: List<DriverMetadata>,
    sources: List<DriverSource>,
    isLoading: Boolean,
    downloadingMap: Map<String, Int>, // driverId -> percent
    onRefresh: () -> Unit,
    onDownloadDriver: (DriverMetadata) -> Unit,
    onImportZip: (Uri) -> Unit,
    modifier: Modifier = Modifier
) {
    var selectedSourceId by remember { mutableStateOf<String?>("all") }

    val filePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) {
            onImportZip(uri)
        }
    }

    val filteredDrivers = remember(drivers, selectedSourceId) {
        if (selectedSourceId == null || selectedSourceId == "all") drivers
        else drivers.filter { it.id.startsWith(selectedSourceId!!) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Driver Catalog", fontWeight = FontWeight.Bold, fontSize = 20.sp, color = PrimaryNeon)
                        Text("Mesa Turnip & Adreno Drivers", fontSize = 12.sp, color = TextSecondary)
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
            // Filter Chips
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
                    Text("No drivers found for this source.", color = TextSecondary)
                }
            } else {
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    contentPadding = PaddingValues(bottom = 80.dp)
                ) {
                    items(filteredDrivers, key = { it.id }) { driver ->
                        DriverCatalogCard(
                            driver = driver,
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
