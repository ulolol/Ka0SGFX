package dev.kaos.kaosgfx.privileged

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.util.zip.ZipFile

data class RootStatus(
    val isRootAvailable: Boolean,
    val providerName: String = "",
    val activeCarrierPkg: String = "",
    val stockDriverProp: String = "",
    val viaShellBridge: Boolean = false
)

data class CarrierInfo(
    val packageName: String,
    val codePath: String,
    val libArm64Dir: String,
    val requiresOverlayMount: Boolean
)

class RootDriverManager(
    private val context: Context,
    private val shizukuServiceProvider: suspend () -> IPrivilegedService? = { null }
) {
    companion object {
        private const val TAG = "KaosGFX-Root"
        private const val KAOS_ROOT_DIR = "/data/adb/kaosgfx"
        private const val STOCK_PROP_FILE = "$KAOS_ROOT_DIR/stock_driver_prop"

        private val CANDIDATE_CARRIERS = listOf(
            "com.google.android.apps.wellbeing",
            "com.google.android.tts",
            "com.google.android.gms",
            "com.google.android.ext.services"
        )
    }

    data class ShellResult(val exitCode: Int, val stdout: String, val stderr: String) {
        val isSuccess: Boolean get() = exitCode == 0
        val combined: String get() = "$stdout\n$stderr".trim()
    }

    suspend fun execRoot(script: String): ShellResult = withContext(Dispatchers.IO) {
        // 1. Try direct `su -c` from our app process
        val direct = runDirectSu(script)
        if (direct.isSuccess) {
            return@withContext direct
        }

        // 2. Fallback: if user granted root to `com.android.shell` (UID 2000) and Shizuku service is active
        try {
            val svc = shizukuServiceProvider()
            if (svc != null && svc.hasShellRootAccess()) {
                val raw = svc.execRootScript(script)
                val firstNewline = raw.indexOf('\n')
                if (firstNewline > 0) {
                    val code = raw.substring(0, firstNewline).trim().toIntOrNull() ?: -1
                    val out = raw.substring(firstNewline + 1)
                    return@withContext ShellResult(code, out, "")
                } else {
                    val code = raw.trim().toIntOrNull() ?: -1
                    return@withContext ShellResult(code, "", "")
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Shell bridge root fallback failed: ${e.message}")
        }

        direct
    }

    private fun runDirectSu(script: String): ShellResult {
        return try {
            val proc = ProcessBuilder("su", "-c", script)
                .redirectErrorStream(false)
                .start()
            val stdout = proc.inputStream.bufferedReader().use { it.readText() }
            val stderr = proc.errorStream.bufferedReader().use { it.readText() }
            val exitCode = proc.waitFor()
            ShellResult(exitCode, stdout.trim(), stderr.trim())
        } catch (e: Exception) {
            ShellResult(-1, "", e.message ?: "su execution failed")
        }
    }

    suspend fun detectRootStatus(): RootStatus = withContext(Dispatchers.IO) {
        val probeScript = """
            id
            if [ -x /data/adb/ksud ]; then
                /data/adb/ksud -V 2>/dev/null || echo "KernelSU"
            elif command -v ksud >/dev/null 2>&1; then
                ksud -V 2>/dev/null
            elif command -v magisk >/dev/null 2>&1; then
                echo "Magisk $(magisk -v 2>/dev/null)"
            elif [ -x /data/adb/apd ]; then
                /data/adb/apd -V 2>/dev/null || echo "APatch"
            else
                su -v 2>/dev/null || echo "Root (su)"
            fi
            echo "DRV0=$(getprop ro.gfx.driver.0)"
            if [ -f $STOCK_PROP_FILE ]; then
                echo "STOCK=$(cat $STOCK_PROP_FILE)"
            fi
        """.trimIndent()

        val direct = runDirectSu(probeScript)
        if (direct.isSuccess && direct.stdout.contains("uid=0")) {
            return@withContext parseRootStatusOutput(direct.stdout, viaShell = false)
        }

        try {
            val svc = shizukuServiceProvider()
            if (svc != null && svc.hasShellRootAccess()) {
                val raw = svc.execRootScript(probeScript)
                val firstNewline = raw.indexOf('\n')
                val out = if (firstNewline > 0) raw.substring(firstNewline + 1) else raw
                if (out.contains("uid=0")) {
                    return@withContext parseRootStatusOutput(out, viaShell = true)
                }
            }
        } catch (_: Exception) {
        }

        RootStatus(isRootAvailable = false)
    }

    private fun parseRootStatusOutput(output: String, viaShell: Boolean): RootStatus {
        val lines = output.lines().map { it.trim() }.filter { it.isNotEmpty() }
        val providerLine = lines.drop(1).firstOrNull { !it.startsWith("DRV0=") && !it.startsWith("STOCK=") } ?: "Root"
        val provider = when {
            providerLine.contains("ksud", ignoreCase = true) -> {
                val ver = providerLine.substringAfter("ksud", "").trim()
                if (ver.isNotEmpty()) "KernelSU Next ($ver)" else "KernelSU Next"
            }
            providerLine.contains("KernelSU", ignoreCase = true) -> providerLine
            providerLine.contains("Magisk", ignoreCase = true) -> providerLine
            providerLine.contains("APatch", ignoreCase = true) || providerLine.contains("apd", ignoreCase = true) -> "APatch"
            else -> providerLine
        }
        val drv0 = lines.firstOrNull { it.startsWith("DRV0=") }?.substringAfter("DRV0=")?.trim().orEmpty()
        val stock = lines.firstOrNull { it.startsWith("STOCK=") }?.substringAfter("STOCK=")?.trim()
            ?.takeIf { it.isNotEmpty() } ?: drv0

        return RootStatus(
            isRootAvailable = true,
            providerName = if (viaShell) "$provider (via Shell)" else provider,
            activeCarrierPkg = drv0,
            stockDriverProp = stock,
            viaShellBridge = viaShell
        )
    }

    /**
     * Resolves a preinstalled system package that satisfies all 5 Android GraphicsEnvironment.chooseDriver
     * checks:
     * 1. FLAG_SYSTEM (MATCH_SYSTEM_ONLY)
     * 2. forceQueryable=true (visible to untrusted_app without <queries>)
     * 3. targetSdkVersion >= 26
     * 4. primaryCpuAbi == arm64-v8a
     * 5. applicationInfo.metaData != null
     */
    suspend fun resolveCarrierPackage(): Result<CarrierInfo> = withContext(Dispatchers.IO) {
        val pm = context.packageManager
        for (pkg in CANDIDATE_CARRIERS) {
            try {
                val pkgInfo = pm.getPackageInfo(
                    pkg,
                    android.content.pm.PackageManager.MATCH_SYSTEM_ONLY or
                        android.content.pm.PackageManager.GET_META_DATA
                )
                val appInfo = pkgInfo.applicationInfo ?: continue
                if (appInfo.metaData == null) continue
                val sourceDir = appInfo.sourceDir ?: continue
                val codePath = File(sourceDir).parentFile?.absolutePath ?: continue
                val nativeLibDir = appInfo.nativeLibraryDir ?: "$codePath/lib/arm64"
                if (!nativeLibDir.endsWith("arm64") && !nativeLibDir.contains("arm64")) continue

                val isOnDataPartition = codePath.startsWith("/data/")
                return@withContext Result.success(
                    CarrierInfo(
                        packageName = pkg,
                        codePath = codePath,
                        libArm64Dir = "$codePath/lib/arm64",
                        requiresOverlayMount = !isOnDataPartition
                    )
                )
            } catch (_: Exception) {
            }
        }

        // Fallback: scan all installed system packages for a qualifying carrier
        try {
            val installed = pm.getInstalledPackages(
                android.content.pm.PackageManager.MATCH_SYSTEM_ONLY or
                    android.content.pm.PackageManager.GET_META_DATA
            )
            val match = installed
                .mapNotNull { info ->
                    val app = info.applicationInfo ?: return@mapNotNull null
                    val src = app.sourceDir ?: return@mapNotNull null
                    val codePath = File(src).parentFile?.absolutePath ?: return@mapNotNull null
                    val nlib = app.nativeLibraryDir ?: ""
                    if (app.metaData != null && app.targetSdkVersion >= 26 && nlib.endsWith("/arm64")) {
                        CarrierInfo(
                            packageName = info.packageName,
                            codePath = codePath,
                            libArm64Dir = nlib,
                            requiresOverlayMount = !codePath.startsWith("/data/")
                        )
                    } else null
                }
                .sortedBy { it.requiresOverlayMount } // Prefer /data/app carriers first
                .firstOrNull()

            if (match != null) {
                return@withContext Result.success(match)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed scanning system packages for carrier", e)
        }

        Result.failure(IllegalStateException("No suitable system GraphicsEnvironment carrier package found"))
    }

    /**
     * Extracts the Vulkan driver `.so` from [driverZip] and applies it per-app via Root
     * WITHOUT modifying or reinstalling the target APK.
     */
    suspend fun applyRootDriver(
        packageName: String,
        driverZip: File,
        libraryNameHint: String,
        configJson: String,
        enableAngle: Boolean
    ): Result<CarrierInfo> = withContext(Dispatchers.IO) {
        val carrier = resolveCarrierPackage().getOrElse { return@withContext Result.failure(it) }

        // 1. Extract the .so from the AdrenoTools / Turnip .zip into shared staging or world-readable cache
        val sharedDir = File("/sdcard/Download/KaosGFX")
        val extractedSo = if (sharedDir.exists() || sharedDir.mkdirs()) {
            File(sharedDir, ".root_staged_${packageName}_vulkan.so")
        } else {
            File(context.cacheDir, "root_staged_${packageName}_vulkan.so")
        }
        try {
            extractVulkanSoFromZip(driverZip, libraryNameHint, extractedSo)
            extractedSo.setReadable(true, false)
        } catch (e: Exception) {
            return@withContext Result.failure(RuntimeException("Failed to extract Vulkan .so from ${driverZip.name}: ${e.message}", e))
        }

        // 2. Stage extracted .so to world-accessible path or copy directly via su from our app cache path
        val srcPath = extractedSo.absolutePath
        val angleAction = if (enableAngle) "angle" else "default"

        val script = """
            set -e
            mkdir -p "$KAOS_ROOT_DIR/drivers/$packageName/hal_override"
            mkdir -p "/data/local/tmp/kaosgfx/$packageName"
            mkdir -p "/sdcard/Download/KaosGFX/configs"

            # Save original stock ro.gfx.driver.0 once
            CUR_DRV0=$(getprop ro.gfx.driver.0)
            if [ ! -f "$STOCK_PROP_FILE" ] && [ -n "${'$'}CUR_DRV0" ] && [ "${'$'}CUR_DRV0" != "${carrier.packageName}" ]; then
                echo "${'$'}CUR_DRV0" > "$STOCK_PROP_FILE"
            fi

            # Copy driver .so into per-app root store
            cp "$srcPath" "$KAOS_ROOT_DIR/drivers/$packageName/vulkan.adreno.so"
            chmod 755 "$KAOS_ROOT_DIR/drivers/$packageName/vulkan.adreno.so"

            # Also prepare a live-gpu-driver compatible HAL override copy (same_process_hal_file)
            cp "$srcPath" "$KAOS_ROOT_DIR/drivers/$packageName/hal_override/vulkan.adreno.so"
            chmod 644 "$KAOS_ROOT_DIR/drivers/$packageName/hal_override/vulkan.adreno.so"
            chcon u:object_r:same_process_hal_file:s0 "$KAOS_ROOT_DIR/drivers/$packageName/hal_override/vulkan.adreno.so" 2>/dev/null || true

            # Prepare carrier lib/arm64 directory (with overlay mount if carrier is on read-only /system)
            if [ "${carrier.requiresOverlayMount}" = "true" ]; then
                OVERLAY_DIR="$KAOS_ROOT_DIR/carrier_overlay/${carrier.packageName}"
                mkdir -p "${'$'}OVERLAY_DIR/lib/arm64"
                cp -f "${carrier.codePath}/"*.apk "${'$'}OVERLAY_DIR/" 2>/dev/null || true
                if ! mount | grep -q "on ${carrier.codePath} "; then
                    chcon -R u:object_r:system_file:s0 "${'$'}OVERLAY_DIR" 2>/dev/null || true
                    mount --bind "${'$'}OVERLAY_DIR" "${carrier.codePath}"
                fi
            fi

            mkdir -p "${carrier.libArm64Dir}"
            VK_HW=$(getprop ro.hardware.vulkan)
            [ -z "${'$'}VK_HW" ] && VK_HW="adreno"
            BOARD_PLT=$(getprop ro.board.platform)

            cp -f "$srcPath" "${carrier.libArm64Dir}/vulkan.${'$'}VK_HW.so"
            chown system:system "${carrier.libArm64Dir}/vulkan.${'$'}VK_HW.so"
            chmod 755 "${carrier.libArm64Dir}/vulkan.${'$'}VK_HW.so"

            if [ -n "${'$'}BOARD_PLT" ] && [ "${'$'}BOARD_PLT" != "${'$'}VK_HW" ]; then
                cp -f "$srcPath" "${carrier.libArm64Dir}/vulkan.${'$'}BOARD_PLT.so"
                chown system:system "${carrier.libArm64Dir}/vulkan.${'$'}BOARD_PLT.so"
                chmod 755 "${carrier.libArm64Dir}/vulkan.${'$'}BOARD_PLT.so"
            fi

            if [ "${carrier.requiresOverlayMount}" = "true" ]; then
                chcon -R u:object_r:system_file:s0 "${carrier.libArm64Dir}" 2>/dev/null || true
            else
                chcon -R u:object_r:apk_data_file:s0 "${carrier.libArm64Dir}" 2>/dev/null || true
            fi

            # Point ro.gfx.driver.0 to the carrier package via resetprop
            if [ -x /data/adb/ksu/bin/resetprop ]; then
                /data/adb/ksu/bin/resetprop ro.gfx.driver.0 "${carrier.packageName}"
            elif [ -x /data/adb/ap/bin/resetprop ]; then
                /data/adb/ap/bin/resetprop ro.gfx.driver.0 "${carrier.packageName}"
            elif command -v magisk >/dev/null 2>&1; then
                magisk resetprop ro.gfx.driver.0 "${carrier.packageName}"
            elif command -v resetprop >/dev/null 2>&1; then
                resetprop ro.gfx.driver.0 "${carrier.packageName}"
            fi

            # Opt-in the target package in Settings.Global.updatable_driver_production_opt_in_apps
            CUR_OPT_IN=$(settings get global updatable_driver_production_opt_in_apps)
            [ "${'$'}CUR_OPT_IN" = "null" ] && CUR_OPT_IN=""
            NEW_OPT_IN=$(echo "${'$'}CUR_OPT_IN" | tr ',' '\n' | grep -v '^$' | grep -vxF "$packageName" | tr '\n' ',' | sed 's/,$//')
            if [ -z "${'$'}NEW_OPT_IN" ]; then
                NEW_OPT_IN="$packageName"
            else
                NEW_OPT_IN="${'$'}NEW_OPT_IN,$packageName"
            fi
            settings put global updatable_driver_production_opt_in_apps "${'$'}NEW_OPT_IN"

            # Ensure package is not in opt_out list
            CUR_OPT_OUT=$(settings get global updatable_driver_production_opt_out_apps)
            if [ "${'$'}CUR_OPT_OUT" != "null" ] && [ -n "${'$'}CUR_OPT_OUT" ]; then
                NEW_OPT_OUT=$(echo "${'$'}CUR_OPT_OUT" | tr ',' '\n' | grep -v '^$' | grep -vxF "$packageName" | tr '\n' ',' | sed 's/,$//')
                if [ -z "${'$'}NEW_OPT_OUT" ]; then
                    settings delete global updatable_driver_production_opt_out_apps
                else
                    settings put global updatable_driver_production_opt_out_apps "${'$'}NEW_OPT_OUT"
                fi
            fi

            # Force-stop target app so next launch unloads Zygote builtin driver and loads our custom driver
            am force-stop "$packageName"
            echo "ROOT_APPLY_OK"
        """.trimIndent()

        val res = execRoot(script)
        extractedSo.delete()

        if (!res.isSuccess || !res.stdout.contains("ROOT_APPLY_OK")) {
            return@withContext Result.failure(
                RuntimeException("Root driver injection failed (exit ${res.exitCode}): ${res.combined}")
            )
        }

        // Write config metadata and ANGLE setting
        writeRootDriverConfigs(packageName, configJson)
        updateAngleSettingRoot(packageName, if (angleAction == "angle") AngleDriverType.ANGLE else AngleDriverType.DEFAULT)

        Result.success(carrier)
    }

    /**
     * Removes the per-app Root driver override for [packageName] and restores stock driver behavior.
     */
    suspend fun removeRootDriver(packageName: String): Result<Unit> = withContext(Dispatchers.IO) {
        val carrier = resolveCarrierPackage().getOrNull()
        val carrierLibDir = carrier?.libArm64Dir ?: ""

        val script = """
            # Remove package from updatable_driver_production_opt_in_apps
            CUR_OPT_IN=$(settings get global updatable_driver_production_opt_in_apps)
            [ "${'$'}CUR_OPT_IN" = "null" ] && CUR_OPT_IN=""
            NEW_OPT_IN=$(echo "${'$'}CUR_OPT_IN" | tr ',' '\n' | grep -v '^$' | grep -vxF "$packageName" | tr '\n' ',' | sed 's/,$//')
            if [ -z "${'$'}NEW_OPT_IN" ]; then
                settings delete global updatable_driver_production_opt_in_apps
            else
                settings put global updatable_driver_production_opt_in_apps "${'$'}NEW_OPT_IN"
            fi

            # Remove per-app files
            rm -rf "$KAOS_ROOT_DIR/drivers/$packageName"
            rm -rf "/data/local/tmp/kaosgfx/$packageName"
            rm -f "/sdcard/Download/KaosGFX/configs/$packageName.json"
            rm -rf "/sdcard/Android/data/$packageName/files/kaosgfx"

            # If no apps remain opted in, clean carrier dir and restore stock ro.gfx.driver.0
            if [ -z "${'$'}NEW_OPT_IN" ]; then
                if [ -n "$carrierLibDir" ]; then
                    rm -f "$carrierLibDir"/vulkan.*.so 2>/dev/null || true
                fi
                if [ -f "$STOCK_PROP_FILE" ]; then
                    STOCK_DRV=$(cat "$STOCK_PROP_FILE")
                    if [ -n "${'$'}STOCK_DRV" ]; then
                        if [ -x /data/adb/ksu/bin/resetprop ]; then
                            /data/adb/ksu/bin/resetprop ro.gfx.driver.0 "${'$'}STOCK_DRV"
                        elif [ -x /data/adb/ap/bin/resetprop ]; then
                            /data/adb/ap/bin/resetprop ro.gfx.driver.0 "${'$'}STOCK_DRV"
                        elif command -v magisk >/dev/null 2>&1; then
                            magisk resetprop ro.gfx.driver.0 "${'$'}STOCK_DRV"
                        elif command -v resetprop >/dev/null 2>&1; then
                            resetprop ro.gfx.driver.0 "${'$'}STOCK_DRV"
                        fi
                    fi
                fi
            else
                # Another app is still opted in: ensure its staged driver is active in carrierLibDir
                LAST_PKG=$(echo "${'$'}NEW_OPT_IN" | tr ',' '\n' | tail -n 1)
                if [ -n "${'$'}LAST_PKG" ] && [ -f "$KAOS_ROOT_DIR/drivers/${'$'}LAST_PKG/vulkan.adreno.so" ] && [ -n "$carrierLibDir" ]; then
                    VK_HW=$(getprop ro.hardware.vulkan)
                    [ -z "${'$'}VK_HW" ] && VK_HW="adreno"
                    cp -f "$KAOS_ROOT_DIR/drivers/${'$'}LAST_PKG/vulkan.adreno.so" "$carrierLibDir/vulkan.${'$'}VK_HW.so"
                    chown system:system "$carrierLibDir/vulkan.${'$'}VK_HW.so"
                    chmod 755 "$carrierLibDir/vulkan.${'$'}VK_HW.so"
                    chcon u:object_r:apk_data_file:s0 "$carrierLibDir/vulkan.${'$'}VK_HW.so" 2>/dev/null || true
                fi
            fi

            am force-stop "$packageName"
            echo "ROOT_REMOVE_OK"
        """.trimIndent()

        val res = execRoot(script)
        updateAngleSettingRoot(packageName, AngleDriverType.DEFAULT)

        if (res.isSuccess) {
            Result.success(Unit)
        } else {
            Result.failure(RuntimeException("Failed removing root driver for $packageName: ${res.combined}"))
        }
    }

    suspend fun getOptedInRootPackages(): Set<String> = withContext(Dispatchers.IO) {
        try {
            val raw = android.provider.Settings.Global.getString(
                context.contentResolver,
                "updatable_driver_production_opt_in_apps"
            )
            if (!raw.isNullOrBlank() && raw != "null") {
                return@withContext raw.split(",").map { it.trim() }.filter { it.isNotEmpty() }.toSet()
            }
        } catch (_: Exception) {
        }
        val res = execRoot("settings get global updatable_driver_production_opt_in_apps")
        if (!res.isSuccess || res.stdout == "null" || res.stdout.isBlank()) return@withContext emptySet()
        res.stdout.split(",").map { it.trim() }.filter { it.isNotEmpty() }.toSet()
    }

    suspend fun updateAngleSettingRoot(packageName: String, driverType: AngleDriverType): Result<Unit> = withContext(Dispatchers.IO) {
        val getRes = execRoot(
            """
            echo "PKGS=$(settings get global angle_gl_driver_selection_pkgs)"
            echo "VALS=$(settings get global angle_gl_driver_selection_values)"
            """.trimIndent()
        )
        if (!getRes.isSuccess) return@withContext Result.failure(RuntimeException(getRes.combined))

        val lines = getRes.stdout.lines()
        val rawPkgs = lines.firstOrNull { it.startsWith("PKGS=") }?.substringAfter("PKGS=")?.trim().orEmpty()
        val rawVals = lines.firstOrNull { it.startsWith("VALS=") }?.substringAfter("VALS=")?.trim().orEmpty()

        val currentPkgs = if (rawPkgs == "null" || rawPkgs.isBlank()) mutableListOf() else rawPkgs.split(",").filter { it.isNotBlank() }.toMutableList()
        val currentVals = if (rawVals == "null" || rawVals.isBlank()) mutableListOf() else rawVals.split(",").filter { it.isNotBlank() }.toMutableList()

        val index = currentPkgs.indexOf(packageName)
        if (index >= 0) {
            if (driverType == AngleDriverType.DEFAULT) {
                currentPkgs.removeAt(index)
                if (index < currentVals.size) currentVals.removeAt(index)
            } else {
                if (index < currentVals.size) currentVals[index] = driverType.value else currentVals.add(driverType.value)
            }
        } else if (driverType != AngleDriverType.DEFAULT) {
            currentPkgs.add(packageName)
            currentVals.add(driverType.value)
        }

        val setScript = buildString {
            if (currentPkgs.isEmpty()) {
                appendLine("settings delete global angle_gl_driver_selection_pkgs")
                appendLine("settings delete global angle_gl_driver_selection_values")
            } else {
                appendLine("settings put global angle_gl_driver_selection_pkgs '${currentPkgs.joinToString(",")}'")
                appendLine("settings put global angle_gl_driver_selection_values '${currentVals.joinToString(",")}'")
            }
        }
        val setRes = execRoot(setScript)
        if (setRes.isSuccess) Result.success(Unit) else Result.failure(RuntimeException(setRes.combined))
    }

    suspend fun getDriverConfigRoot(packageName: String): String = withContext(Dispatchers.IO) {
        val localDownloadCfg = File("/sdcard/Download/KaosGFX/configs/$packageName.json")
        if (localDownloadCfg.exists() && localDownloadCfg.canRead()) {
            return@withContext localDownloadCfg.readText()
        }
        val tmpCfg = File("/data/local/tmp/kaosgfx/$packageName/kaosgfx_driver_config.json")
        if (tmpCfg.exists() && tmpCfg.canRead()) {
            return@withContext tmpCfg.readText()
        }
        val res = execRoot("cat '/data/local/tmp/kaosgfx/$packageName/kaosgfx_driver_config.json' 2>/dev/null || cat '/sdcard/Download/KaosGFX/configs/$packageName.json' 2>/dev/null")
        if (res.isSuccess) res.stdout else ""
    }

    suspend fun getPipelineLogsRoot(packageName: String, maxLines: Int = 150): String = withContext(Dispatchers.IO) {
        val takeCount = if (maxLines in 10..500) maxLines else 150
        val script = """
            PID=$(pidof "$packageName" 2>/dev/null | awk '{print ${'$'}1}')
            OUT=$(logcat -d -s KaosGFX-Bootstrap KaosGFX-Bridge KaosGFX-PLTHook Turnip freedreno vulkan Adreno MESA TU GraphicsEnvironment | tail -n $takeCount)
            if [ -n "${'$'}OUT" ]; then
                echo "${'$'}OUT"
            elif [ -n "${'$'}PID" ]; then
                logcat -d --pid="${'$'}PID" | tail -n $takeCount
            else
                echo "No pipeline logs found in logcat buffer."
            fi
        """.trimIndent()
        val res = execRoot(script)
        if (res.isSuccess && res.stdout.isNotBlank()) res.stdout else "No pipeline logs available."
    }

    suspend fun getPackageProcessIdRoot(packageName: String): Int = withContext(Dispatchers.IO) {
        val res = execRoot("pidof '$packageName' 2>/dev/null")
        if (!res.isSuccess) return@withContext -1
        res.stdout.trim().split("\\s+".toRegex()).firstOrNull()?.toIntOrNull() ?: -1
    }

    private suspend fun writeRootDriverConfigs(packageName: String, configJson: String) {
        try {
            val dlDir = File("/sdcard/Download/KaosGFX/configs").apply { mkdirs() }
            File(dlDir, "$packageName.json").writeText(configJson)
        } catch (_: Exception) {
        }
        val escaped = configJson.replace("'", "'\\''")
        execRoot(
            """
            mkdir -p "/data/local/tmp/kaosgfx/$packageName"
            mkdir -p "/sdcard/Download/KaosGFX/configs"
            printf '%s' '$escaped' > "/data/local/tmp/kaosgfx/$packageName/kaosgfx_driver_config.json"
            printf '%s' '$escaped' > "/sdcard/Download/KaosGFX/configs/$packageName.json"
            chmod -R 777 "/data/local/tmp/kaosgfx/$packageName"
            """.trimIndent()
        )
    }

    private fun extractVulkanSoFromZip(driverZip: File, libraryNameHint: String, destFile: File) {
        ZipFile(driverZip).use { zip ->
            var targetLibName = libraryNameHint

            // Check meta.json inside the zip first
            val metaEntry = zip.getEntry("meta.json")
            if (metaEntry != null) {
                try {
                    val metaStr = zip.getInputStream(metaEntry).bufferedReader().use { it.readText() }
                    val json = JSONObject(metaStr)
                    val metaLib = json.optString("libraryName", "")
                    if (metaLib.isNotBlank()) {
                        targetLibName = metaLib
                    }
                } catch (_: Exception) {
                }
            }

            val entries = zip.entries().asSequence().toList()
            val chosenEntry = entries.firstOrNull { !it.isDirectory && it.name.substringAfterLast('/') == targetLibName }
                ?: entries.firstOrNull { !it.isDirectory && it.name.endsWith(".so", ignoreCase = true) && it.name.contains("vulkan", ignoreCase = true) }
                ?: entries.firstOrNull { !it.isDirectory && it.name.endsWith(".so", ignoreCase = true) }
                ?: throw IllegalArgumentException("No Vulkan .so library found inside ${driverZip.name}")

            zip.getInputStream(chosenEntry).use { input ->
                destFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
        }
    }
}
