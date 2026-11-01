package dev.kaos.kaosgfx.ui.components

import android.os.Build
import android.os.Environment
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import dev.kaos.kaosgfx.drivers.AdrenoGpu
import dev.kaos.kaosgfx.drivers.CompatibilityStatus
import dev.kaos.kaosgfx.drivers.DriverMetadata
import dev.kaos.kaosgfx.drivers.DriverType
import dev.kaos.kaosgfx.privileged.ModePreference
import dev.kaos.kaosgfx.privileged.OperatingMode
import dev.kaos.kaosgfx.privileged.RootStatus
import dev.kaos.kaosgfx.privileged.ShizukuState
import dev.kaos.kaosgfx.ui.theme.*

@Composable
fun PrivilegeModeBanner(
    operatingMode: OperatingMode,
    rootStatus: RootStatus,
    shizukuState: ShizukuState,
    modePreference: ModePreference,
    onChangeModePreference: (ModePreference) -> Unit,
    onRequestShizukuPermission: () -> Unit,
    modifier: Modifier = Modifier
) {
    when (operatingMode) {
        OperatingMode.ROOT -> {
            Card(
                modifier = modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = PrimaryNeon.copy(alpha = 0.12f)),
                border = androidx.compose.foundation.BorderStroke(1.dp, PrimaryNeon.copy(alpha = 0.35f))
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.Security,
                            contentDescription = null,
                            tint = PrimaryNeon,
                            modifier = Modifier.size(24.dp)
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Root Mode Active • Zero APK Patch",
                                fontWeight = FontWeight.Bold,
                                fontSize = 14.sp,
                                color = PrimaryNeon
                            )
                            Text(
                                text = "${rootStatus.providerName.ifBlank { "Root" }} • Original APKs & signatures stay 100% untouched",
                                fontSize = 11.sp,
                                color = TextSecondary
                            )
                        }
                        if (shizukuState == ShizukuState.READY) {
                            TextButton(
                                onClick = { onChangeModePreference(ModePreference.SHIZUKU) },
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                            ) {
                                Text("Use Shizuku", fontSize = 11.sp, color = PrimaryBlue)
                            }
                        }
                    }
                }
            }
        }

        OperatingMode.SHIZUKU -> {
            if (rootStatus.isRootAvailable) {
                Card(
                    modifier = modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(containerColor = PrimaryBlue.copy(alpha = 0.14f))
                ) {
                    Row(
                        modifier = Modifier.padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.CheckCircle,
                            contentDescription = null,
                            tint = PrimaryBlue,
                            modifier = Modifier.size(24.dp)
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Shizuku Mode Active (APK Patching)",
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 13.sp,
                                color = TextPrimary
                            )
                            Text(
                                text = "Root (${rootStatus.providerName}) is also available for Zero-Patch mode.",
                                fontSize = 11.sp,
                                color = TextSecondary
                            )
                        }
                        Button(
                            onClick = { onChangeModePreference(ModePreference.ROOT) },
                            colors = ButtonDefaults.buttonColors(containerColor = PrimaryNeon),
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                        ) {
                            Text("Switch to Root", color = Color.Black, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }

        OperatingMode.UNAVAILABLE -> {
            ShizukuBanner(
                state = shizukuState,
                onRequestPermission = onRequestShizukuPermission,
                modifier = modifier
            )
        }
    }
}

@Composable
fun ShizukuBanner(
    state: ShizukuState,
    onRequestPermission: () -> Unit,
    modifier: Modifier = Modifier
) {
    if (state == ShizukuState.READY) return

    val (bgColor, icon, title, desc, actionText) = when (state) {
        ShizukuState.PERMISSION_REQUIRED -> Tuple5(
            WarningOrange.copy(alpha = 0.15f),
            Icons.Default.Warning,
            "Root or Shizuku Permission Required",
            "Grant Root access (for Zero-Patch mode) or Shizuku permission (for Non-Root APK patching).",
            "Grant Shizuku"
        )
        ShizukuState.UNAVAILABLE -> Tuple5(
            ErrorRed.copy(alpha = 0.15f),
            Icons.Default.Warning,
            "Privilege Engine Not Connected",
            "Grant Root in KernelSU/Magisk (Zero APK Patch) or start Shizuku (Non-Root APK Patch).",
            null
        )
        ShizukuState.READY -> Tuple5(
            SuccessGreen.copy(alpha = 0.15f),
            Icons.Default.CheckCircle,
            "Shizuku Connected",
            "Ready for privileged operations.",
            null
        )
    }

    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = bgColor)
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = if (state == ShizukuState.PERMISSION_REQUIRED) WarningOrange else ErrorRed,
                modifier = Modifier.size(28.dp)
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 14.sp,
                    color = TextPrimary
                )
                Text(
                    text = desc,
                    fontSize = 12.sp,
                    color = TextSecondary
                )
            }
            if (actionText != null) {
                Spacer(modifier = Modifier.width(8.dp))
                Button(
                    onClick = onRequestPermission,
                    colors = ButtonDefaults.buttonColors(containerColor = WarningOrange)
                ) {
                    Text(actionText, color = Color.Black, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

@Composable
fun StorageBanner(
    onRequestPermission: () -> Unit,
    modifier: Modifier = Modifier
) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && Environment.isExternalStorageManager()) return

    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = PrimaryBlue.copy(alpha = 0.15f))
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Default.Folder,
                contentDescription = null,
                tint = PrimaryNeon,
                modifier = Modifier.size(28.dp)
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Downloads Storage Access",
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 14.sp,
                    color = TextPrimary
                )
                Text(
                    text = "Grant All Files access to store driver archives in Downloads/KaosGFX.",
                    fontSize = 12.sp,
                    color = TextSecondary
                )
            }
            Spacer(modifier = Modifier.width(8.dp))
            Button(
                onClick = onRequestPermission,
                colors = ButtonDefaults.buttonColors(containerColor = PrimaryNeon)
            ) {
                Text("Grant", color = Color.Black, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
fun SimpleBadge(
    text: String,
    color: Color,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .background(color.copy(alpha = 0.15f), RoundedCornerShape(6.dp))
            .padding(horizontal = 8.dp, vertical = 4.dp)
    ) {
        Text(text = text, color = color, fontSize = 11.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
fun GpuSupportBadges(
    driver: DriverMetadata,
    detectedGpu: AdrenoGpu,
    modifier: Modifier = Modifier
) {
    val compat = driver.gpuSupport.isCompatibleWith(detectedGpu)
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Driver type badge (Mesa Turnip vs Qualcomm Blob)
        val typeColor = if (driver.driverType == DriverType.QUALCOMM_BLOB) PrimaryBlue else PrimaryNeon
        SimpleBadge(text = driver.driverType.label, color = typeColor)

        // GPU generation badges
        val labels = driver.gpuSupport.badgeLabels
        val badgeColor = when (compat) {
            CompatibilityStatus.COMPATIBLE -> SuccessGreen
            CompatibilityStatus.TENTATIVE -> WarningOrange
            CompatibilityStatus.UNVERIFIED -> TextSecondary
            CompatibilityStatus.INCOMPATIBLE -> ErrorRed
        }
        labels.forEach { label ->
            SimpleBadge(text = label, color = badgeColor)
        }
        if (compat == CompatibilityStatus.INCOMPATIBLE) {
            SimpleBadge(text = "Incompatible with ${detectedGpu.gen.label}", color = ErrorRed)
        }
    }
}

@Composable
fun StatusBadge(
    isPatched: Boolean,
    isDenylisted: Boolean,
    isRootActive: Boolean = false,
    modifier: Modifier = Modifier
) {
    val (label, bg, fg) = when {
        isRootActive -> Triple("Root Driver Active (Zero Patch)", PrimaryNeon.copy(alpha = 0.2f), PrimaryNeon)
        isPatched -> Triple("Custom Driver Active (Patched)", PrimaryBlue.copy(alpha = 0.2f), PrimaryBlue)
        isDenylisted -> Triple("Anti-Cheat Protected", ErrorRed.copy(alpha = 0.2f), ErrorRed)
        else -> Triple("Stock Driver", SurfaceVariantDark, TextSecondary)
    }

    Box(
        modifier = modifier
            .background(bg, RoundedCornerShape(6.dp))
            .padding(horizontal = 8.dp, vertical = 4.dp)
    ) {
        Text(text = label, color = fg, fontSize = 11.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
fun ProgressModal(
    step: String,
    progress: Float,
    onDismiss: () -> Unit
) {
    Dialog(onDismissRequest = {}) {
        Card(
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = SurfaceDark),
            modifier = Modifier.padding(16.dp)
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                CircularProgressIndicator(
                    progress = { progress },
                    color = PrimaryNeon,
                    trackColor = SurfaceVariantDark,
                    modifier = Modifier.size(54.dp)
                )
                Spacer(modifier = Modifier.height(20.dp))
                Text(
                    text = "Applying Graphics Pipeline",
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp,
                    color = TextPrimary
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = step,
                    fontSize = 13.sp,
                    color = TextSecondary
                )
            }
        }
    }
}

data class Tuple5<A, B, C, D, E>(val a: A, val b: B, val c: C, val d: D, val e: E)
