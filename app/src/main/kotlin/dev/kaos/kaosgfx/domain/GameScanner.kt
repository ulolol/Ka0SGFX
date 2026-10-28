package dev.kaos.kaosgfx.domain

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

data class GameApp(
    val packageName: String,
    val appName: String,
    val versionName: String,
    val icon: Drawable?,
    val baseApkPath: String,
    val splitApkPaths: List<String>,
    val isPatched: Boolean,
    val isDenylisted: Boolean,
    val denylistReason: String? = null,
    val isGameCategory: Boolean = true
)

class GameScanner(private val context: Context) {

    companion object {
        private const val BOOTSTRAP_FACTORY = "dev.kaos.kaosgfx.bootstrap.TurnipAppComponentFactory"

        val DENYLIST = mapOf(
            "com.miHoYo.GenshinImpact" to "Competitive / Anti-Cheat: Genshin Impact",
            "com.HoYoverse.hkrpgoversea" to "Competitive / Anti-Cheat: Honkai: Star Rail",
            "com.HoYoverse.Nap" to "Competitive / Anti-Cheat: Zenless Zone Zero",
            "com.tencent.tmgp.pubgmhd" to "Competitive / Anti-Cheat: PUBG Mobile",
            "com.tencent.ig" to "Competitive / Anti-Cheat: PUBG Mobile",
            "com.activision.callofduty.shooter" to "Competitive / Anti-Cheat: Call of Duty Mobile",
            "com.activision.callofduty.warzone" to "Competitive / Anti-Cheat: Warzone Mobile",
            "com.riotgames.league.wildrift" to "Competitive / Anti-Cheat: League of Legends: Wild Rift",
            "com.riotgames.valorant" to "Competitive / Anti-Cheat: Valorant Mobile",
            "com.mobile.legends" to "Competitive / Anti-Cheat: Mobile Legends: Bang Bang",
            "com.dts.freefireth" to "Competitive / Anti-Cheat: Free Fire",
            "com.dts.freefiremax" to "Competitive / Anti-Cheat: Free Fire MAX",
            "com.epicgames.fortnite" to "Competitive / Anti-Cheat: Fortnite"
        )
    }

    suspend fun scanGames(includeAllApps: Boolean = false): List<GameApp> = withContext(Dispatchers.IO) {
        val pm = context.packageManager
        val packages = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.getInstalledPackages(PackageManager.PackageInfoFlags.of(0))
        } else {
            pm.getInstalledPackages(0)
        }

        val games = mutableListOf<GameApp>()

        for (pkg in packages) {
            val appInfo = pkg.applicationInfo ?: continue
            val pkgName = pkg.packageName

            // Skip own app
            if (pkgName == context.packageName) continue

            // Determine if it's a game or user app
            val isGame = (appInfo.category == ApplicationInfo.CATEGORY_GAME) ||
                    (appInfo.flags and ApplicationInfo.FLAG_IS_GAME) != 0

            val isSystemApp = (appInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0
            if (isSystemApp && !includeAllApps) continue
            if (!isGame && !includeAllApps && !pkgName.contains("vkprobe")) continue

            val appName = try {
                appInfo.loadLabel(pm).toString()
            } catch (e: Exception) {
                pkgName
            }

            val icon = try {
                appInfo.loadIcon(pm)
            } catch (e: Exception) {
                null
            }

            val isPatched = appInfo.appComponentFactory == BOOTSTRAP_FACTORY
            val isDenylisted = DENYLIST.containsKey(pkgName)
            val denylistReason = DENYLIST[pkgName]

            val baseApkPath = appInfo.sourceDir ?: ""
            val splitPaths = appInfo.splitSourceDirs?.toList() ?: emptyList()

            games.add(
                GameApp(
                    packageName = pkgName,
                    appName = appName,
                    versionName = pkg.versionName ?: "1.0",
                    icon = icon,
                    baseApkPath = baseApkPath,
                    splitApkPaths = splitPaths,
                    isPatched = isPatched,
                    isDenylisted = isDenylisted,
                    denylistReason = denylistReason,
                    isGameCategory = isGame
                )
            )
        }

        games.sortedWith(
            compareByDescending<GameApp> { it.isPatched }
                .thenBy { it.isDenylisted }
                .thenBy { it.appName.lowercase() }
        )
    }
}
