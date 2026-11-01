package dev.kaos.kaosgfx.ui.screens

import android.graphics.drawable.BitmapDrawable
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SportsEsports
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.kaos.kaosgfx.domain.GameApp
import dev.kaos.kaosgfx.drivers.AdrenoGpu
import dev.kaos.kaosgfx.privileged.ModePreference
import dev.kaos.kaosgfx.privileged.OperatingMode
import dev.kaos.kaosgfx.privileged.RootStatus
import dev.kaos.kaosgfx.privileged.ShizukuState
import dev.kaos.kaosgfx.ui.components.PrivilegeModeBanner
import dev.kaos.kaosgfx.ui.components.StatusBadge
import dev.kaos.kaosgfx.ui.components.StorageBanner
import dev.kaos.kaosgfx.ui.theme.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GamesScreen(
    games: List<GameApp>,
    isLoading: Boolean,
    detectedGpu: AdrenoGpu,
    operatingMode: OperatingMode,
    rootStatus: RootStatus,
    shizukuState: ShizukuState,
    modePreference: ModePreference,
    onChangeModePreference: (ModePreference) -> Unit,
    includeSystemApps: Boolean = false,
    onToggleSystemApps: ((Boolean) -> Unit)? = null,
    onRefresh: () -> Unit,
    onRequestShizuku: () -> Unit,
    onRequestStorage: () -> Unit,
    onSelectGame: (GameApp) -> Unit,
    modifier: Modifier = Modifier
) {
    var searchQuery by rememberSaveable { mutableStateOf("") }

    val filteredGames = remember(games, searchQuery) {
        if (searchQuery.isBlank()) games
        else games.filter {
            it.appName.contains(searchQuery, ignoreCase = true) ||
                    it.packageName.contains(searchQuery, ignoreCase = true)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("KaosGFX", fontWeight = FontWeight.Bold, fontSize = 20.sp, color = PrimaryNeon)
                        Text(
                            text = "${detectedGpu.displayName} • ${operatingMode.label}",
                            fontSize = 12.sp,
                            color = TextSecondary
                        )
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
        containerColor = BgDark,
        modifier = modifier
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .padding(horizontal = 16.dp)
        ) {
            PrivilegeModeBanner(
                operatingMode = operatingMode,
                rootStatus = rootStatus,
                shizukuState = shizukuState,
                modePreference = modePreference,
                onChangeModePreference = onChangeModePreference,
                onRequestShizukuPermission = onRequestShizuku,
                modifier = Modifier.padding(bottom = 12.dp)
            )

            StorageBanner(
                onRequestPermission = onRequestStorage,
                modifier = Modifier.padding(bottom = 12.dp)
            )

            // Search Bar
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                placeholder = { Text("Search games & apps...", color = TextSecondary, fontSize = 14.sp) },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, tint = TextSecondary) },
                singleLine = true,
                shape = RoundedCornerShape(12.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedContainerColor = SurfaceDark,
                    unfocusedContainerColor = SurfaceDark,
                    focusedBorderColor = PrimaryNeon,
                    unfocusedBorderColor = SurfaceVariantDark,
                    focusedTextColor = TextPrimary,
                    unfocusedTextColor = TextPrimary
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 8.dp)
            )

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 10.dp)
            ) {
                Text(
                    text = if (operatingMode == OperatingMode.ROOT) {
                        "${filteredGames.size} apps (Zero-Patch Root Ready)"
                    } else {
                        "${filteredGames.size} patchable apps"
                    },
                    fontSize = 12.sp,
                    color = TextSecondary,
                    fontWeight = FontWeight.Medium
                )
                if (onToggleSystemApps != null) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "System Apps",
                            fontSize = 12.sp,
                            color = if (includeSystemApps) PrimaryNeon else TextSecondary
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Switch(
                            checked = includeSystemApps,
                            onCheckedChange = onToggleSystemApps,
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = PrimaryNeon,
                                checkedTrackColor = PrimaryBlue
                            ),
                            modifier = Modifier.scale(0.8f)
                        )
                    }
                }
            }

            if (isLoading) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = PrimaryNeon)
                }
            } else if (filteredGames.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Default.SportsEsports, contentDescription = null, tint = TextSecondary, modifier = Modifier.size(64.dp))
                        Spacer(modifier = Modifier.height(12.dp))
                        Text("No installed games found", color = TextSecondary, fontSize = 15.sp)
                    }
                }
            } else {
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    contentPadding = PaddingValues(bottom = 24.dp)
                ) {
                    items(filteredGames, key = { it.packageName }) { game ->
                        GameCard(game = game, onClick = { onSelectGame(game) })
                    }
                }
            }
        }
    }
}

@Composable
fun GameCard(
    game: GameApp,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = SurfaceDark),
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // App Icon
            val bmp = (game.icon as? BitmapDrawable)?.bitmap
            if (bmp != null) {
                Image(
                    bitmap = bmp.asImageBitmap(),
                    contentDescription = null,
                    modifier = Modifier
                        .size(48.dp)
                        .clip(RoundedCornerShape(10.dp))
                )
            } else {
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .background(SurfaceVariantDark, RoundedCornerShape(10.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Default.SportsEsports, contentDescription = null, tint = PrimaryNeon)
                }
            }

            Spacer(modifier = Modifier.width(14.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = game.appName,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 15.sp,
                    color = TextPrimary
                )
                Text(
                    text = game.packageName,
                    fontSize = 12.sp,
                    color = TextSecondary
                )
                Spacer(modifier = Modifier.height(6.dp))
                StatusBadge(
                    isPatched = game.isPatched,
                    isDenylisted = game.isDenylisted,
                    isRootActive = game.isRootActive
                )
            }
        }
    }
}
