package dev.kaos.kaosgfx.ui.screens

import android.os.Build
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.kaos.kaosgfx.drivers.AdrenoGpu
import dev.kaos.kaosgfx.privileged.ModePreference
import dev.kaos.kaosgfx.privileged.OperatingMode
import dev.kaos.kaosgfx.privileged.PrivilegedClient
import dev.kaos.kaosgfx.privileged.RootStatus
import dev.kaos.kaosgfx.privileged.ShizukuState
import dev.kaos.kaosgfx.ui.theme.*
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DiagnosticsScreen(
    detectedGpu: AdrenoGpu,
    operatingMode: OperatingMode,
    rootStatus: RootStatus,
    shizukuState: ShizukuState,
    modePreference: ModePreference,
    onChangeModePreference: (ModePreference) -> Unit,
    privilegedClient: PrivilegedClient,
    modifier: Modifier = Modifier
) {
    var probeOutput by remember { mutableStateOf<String?>(null) }
    var isProbing by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Hardware Diagnostics", fontWeight = FontWeight.Bold, fontSize = 20.sp, color = PrimaryNeon)
                        Text("Live GPU Architecture & Root/Shizuku Engine", fontSize = 12.sp, color = TextSecondary)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = BgDark)
            )
        },
        containerColor = BgDark,
        modifier = modifier
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState())
        ) {
            // Live Hardware Specs Card
            Card(
                shape = RoundedCornerShape(14.dp),
                colors = CardDefaults.cardColors(containerColor = SurfaceDark),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Memory, contentDescription = null, tint = PrimaryNeon)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Detected Hardware", fontWeight = FontWeight.Bold, fontSize = 15.sp, color = TextPrimary)
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                    DiagRow("Device", "${Build.MANUFACTURER} ${Build.MODEL} (${Build.DEVICE})")
                    DiagRow("Hardware / Board", "${Build.HARDWARE} / ${Build.BOARD}")
                    DiagRow("SoC Model", detectedGpu.socModel.ifBlank { if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) Build.SOC_MODEL else Build.HARDWARE })
                    DiagRow("GPU Model", detectedGpu.displayName)
                    DiagRow("GPU Generation", detectedGpu.gen.label)
                    DiagRow("Raw KGSL / GL Renderer", detectedGpu.rawRenderer)
                    DiagRow("Android OS", "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
                    DiagRow("Kernel Page Size", "${detectedGpu.pageSizeKb * 1024} bytes (${detectedGpu.pageSizeKb} KB)")
                    DiagRow("ABI Support", Build.SUPPORTED_ABIS.joinToString(", "))
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            // Root & Shizuku Mode Selector Card
            Card(
                shape = RoundedCornerShape(14.dp),
                colors = CardDefaults.cardColors(containerColor = SurfaceDark),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Security, contentDescription = null, tint = PrimaryNeon)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Privilege Engine & Mode", fontWeight = FontWeight.Bold, fontSize = 15.sp, color = TextPrimary)
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                    DiagRow("Active Operating Mode", operatingMode.label)
                    DiagRow(
                        "Root Access",
                        if (rootStatus.isRootAvailable) "Available (${rootStatus.providerName})" else "Not Detected / Not Granted"
                    )
                    if (rootStatus.isRootAvailable) {
                        DiagRow("Active ro.gfx.driver.0", rootStatus.activeCarrierPkg.ifBlank { "Default" })
                        DiagRow("Stock Driver Prop", rootStatus.stockDriverProp.ifBlank { "Default" })
                    }
                    DiagRow(
                        "Shizuku Status",
                        when (shizukuState) {
                            ShizukuState.READY -> "Connected & Ready (UID 2000)"
                            ShizukuState.PERMISSION_REQUIRED -> "Permission Required"
                            ShizukuState.UNAVAILABLE -> "Unavailable (Service Stopped)"
                        }
                    )
                    DiagRow(
                        "Driver Injection Strategy",
                        if (operatingMode == OperatingMode.ROOT) {
                            "Zero-Patch GraphicsEnvironment Carrier + Linker Namespace Unload"
                        } else {
                            "In-Process Bootstrap (TurnipAppComponentFactory + PLT Hook)"
                        }
                    )

                    Spacer(modifier = Modifier.height(12.dp))
                    Text("Preferred Operating Mode", fontSize = 12.sp, color = TextSecondary)
                    Spacer(modifier = Modifier.height(6.dp))
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        ModePreference.entries.forEach { pref ->
                            FilterChip(
                                selected = modePreference == pref,
                                onClick = { onChangeModePreference(pref) },
                                label = { Text(pref.label, fontSize = 11.sp) },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = PrimaryNeon,
                                    selectedLabelColor = Color.Black
                                )
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            // Vulkan Probe Output
            if (probeOutput != null) {
                Card(
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(containerColor = SurfaceVariantDark),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Text("Live Subsystem Probe Output:", fontWeight = FontWeight.SemiBold, fontSize = 13.sp, color = PrimaryNeon)
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = probeOutput!!,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp,
                            color = TextPrimary
                        )
                    }
                }
                Spacer(modifier = Modifier.height(14.dp))
            }

            Button(
                onClick = {
                    scope.launch {
                        isProbing = true
                        val refreshedMode = privilegedClient.refreshCapabilities()
                        val rStat = privilegedClient.rootStatus.value
                        val carrier = privilegedClient.rootDriverManager.resolveCarrierPackage().getOrNull()
                        val optedIn = if (rStat.isRootAvailable) {
                            privilegedClient.rootDriverManager.getOptedInRootPackages()
                        } else emptySet()

                        probeOutput = buildString {
                            appendLine("Physical Hardware & Privilege Probe:")
                            appendLine("• SoC: ${detectedGpu.socModel} (${Build.BOARD})")
                            appendLine("• GPU: ${detectedGpu.displayName} [raw: ${detectedGpu.rawRenderer}]")
                            appendLine("• Kernel Page Size: ${detectedGpu.pageSizeKb} KB")
                            appendLine("• Active Mode: ${refreshedMode.label}")
                            if (rStat.isRootAvailable) {
                                appendLine("• Root Provider: ${rStat.providerName}")
                                appendLine("• Carrier Package: ${carrier?.packageName ?: "None"} (${carrier?.libArm64Dir ?: "N/A"})")
                                appendLine("• ro.gfx.driver.0: ${rStat.activeCarrierPkg}")
                                appendLine("• Opted-in Zero-Patch Apps: ${if (optedIn.isEmpty()) "(none)" else optedIn.joinToString(", ")}")
                            } else {
                                appendLine("• Root Provider: Not granted")
                            }
                            appendLine("• Shizuku State: ${privilegedClient.shizukuState.value}")
                        }.trimEnd()
                        isProbing = false
                    }
                },
                enabled = !isProbing,
                colors = ButtonDefaults.buttonColors(containerColor = PrimaryNeon),
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
            ) {
                Icon(Icons.Default.BugReport, contentDescription = null, tint = Color.Black)
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    if (isProbing) "Probing Subsystem..." else "Run Live Hardware & Root Probe",
                    color = Color.Black,
                    fontWeight = FontWeight.Bold
                )
            }

            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}

@Composable
private fun DiagRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(text = label, fontSize = 13.sp, color = TextSecondary, modifier = Modifier.weight(0.42f))
        Text(text = value, fontSize = 13.sp, color = TextPrimary, fontWeight = FontWeight.Medium, modifier = Modifier.weight(0.58f))
    }
}
