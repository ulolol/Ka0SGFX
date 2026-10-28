package dev.kaos.kaosgfx.ui.screens

import android.graphics.drawable.BitmapDrawable
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.SportsEsports
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.kaos.kaosgfx.domain.GameApp
import dev.kaos.kaosgfx.drivers.DriverMetadata
import dev.kaos.kaosgfx.ui.components.StatusBadge
import dev.kaos.kaosgfx.ui.theme.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GameDetailSheet(
    game: GameApp,
    availableDrivers: List<DriverMetadata>,
    onDismiss: () -> Unit,
    onPatchGame: (GameApp, DriverMetadata, Boolean) -> Unit,
    onUnpatchGame: (GameApp) -> Unit
) {
    var selectedDriver by remember(availableDrivers) {
        mutableStateOf(availableDrivers.firstOrNull { it.isInstalled } ?: availableDrivers.firstOrNull())
    }
    var enableAngle by remember { mutableStateOf(false) }

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
            if (game.isDenylisted) {
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
                                text = game.denylistReason ?: "This title features strict server-side or kernel integrity checks. Modification is blocked to safeguard your account.",
                                color = TextPrimary,
                                fontSize = 12.sp
                            )
                        }
                    }
                }
                Spacer(modifier = Modifier.height(20.dp))
            } else {
                // Driver Selection
                Text(
                    text = "Graphics Driver",
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 14.sp,
                    color = TextPrimary
                )
                Spacer(modifier = Modifier.height(8.dp))

                if (availableDrivers.isEmpty()) {
                    Text(
                        text = "No drivers installed yet. Visit the Catalog tab to download Turnip drivers.",
                        color = WarningOrange,
                        fontSize = 13.sp
                    )
                } else {
                    availableDrivers.forEach { driver ->
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
                                Column {
                                    Text(driver.name, fontWeight = FontWeight.Medium, fontSize = 14.sp, color = TextPrimary)
                                    Text("Author: ${driver.author} • Target: ${driver.libraryName}", fontSize = 11.sp, color = TextSecondary)
                                }
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

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

                Spacer(modifier = Modifier.height(24.dp))

                // Action Buttons
                Button(
                    onClick = {
                        val drv = selectedDriver
                        if (drv != null) {
                            onPatchGame(game, drv, enableAngle)
                        }
                    },
                    enabled = selectedDriver != null && !game.isDenylisted,
                    colors = ButtonDefaults.buttonColors(containerColor = PrimaryNeon),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(50.dp)
                ) {
                    Text(
                        if (game.isPatched) "Update Custom Driver" else "Patch & Enable Custom Driver",
                        color = Color.Black,
                        fontWeight = FontWeight.Bold,
                        fontSize = 15.sp
                    )
                }

                if (game.isPatched) {
                    Spacer(modifier = Modifier.height(12.dp))
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
            }
        }
    }
}
