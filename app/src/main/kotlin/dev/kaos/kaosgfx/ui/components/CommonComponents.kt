package dev.kaos.kaosgfx.ui.components

import android.os.Build
import android.os.Environment
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Folder
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
import dev.kaos.kaosgfx.privileged.ShizukuState
import dev.kaos.kaosgfx.ui.theme.*

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
            "Shizuku Permission Required",
            "KaosGFX needs Shizuku privilege to install patched APKs without root.",
            "Grant Permission"
        )
        ShizukuState.UNAVAILABLE -> Tuple5(
            ErrorRed.copy(alpha = 0.15f),
            Icons.Default.Warning,
            "Shizuku Not Running",
            "Please start the Shizuku app or ensure adb service is active.",
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
                    text = "Grant All Files access to save patched APKs to Downloads/KaosGFX.",
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
fun StatusBadge(
    isPatched: Boolean,
    isDenylisted: Boolean,
    modifier: Modifier = Modifier
) {
    val (label, bg, fg) = when {
        isDenylisted -> Triple("Anti-Cheat Protected", ErrorRed.copy(alpha = 0.2f), ErrorRed)
        isPatched -> Triple("Custom Turnip Active", PrimaryNeon.copy(alpha = 0.2f), PrimaryNeon)
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
