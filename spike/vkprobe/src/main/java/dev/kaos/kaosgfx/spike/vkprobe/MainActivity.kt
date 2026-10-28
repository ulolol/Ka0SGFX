package dev.kaos.kaosgfx.spike.vkprobe

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipInputStream

class MainActivity : Activity() {

    companion object {
        private const val TAG = "VKPROBE_UI"

        init {
            System.loadLibrary("vkprobe")
        }
    }

    private external fun runProbe(
        hookLibDir: String,
        customDriverDir: String,
        customDriverName: String
    ): String

    private external fun runStandardVulkanProbe(): String

    private lateinit var statusCard: LinearLayout
    private lateinit var driverTitleText: TextView
    private lateinit var driverDetailsText: TextView
    private lateinit var verdictBadge: TextView
    private lateinit var configDetailsText: TextView
    private lateinit var rawLogText: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#080B10"))
            setPadding(32, 48, 32, 48)
        }

        // Header
        val headerText = TextView(this).apply {
            text = "⚡ KaosGFX Vulkan Pipeline Probe"
            textSize = 20f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.parseColor("#00E5FF"))
        }
        root.addView(headerText)

        // Status Card
        statusCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#121824"))
            setPadding(28, 28, 28, 28)
            val params = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, 24, 0, 24) }
            layoutParams = params
        }

        verdictBadge = TextView(this).apply {
            text = "Probing Vulkan Driver..."
            textSize = 14f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.YELLOW)
            setPadding(0, 0, 0, 12)
        }
        statusCard.addView(verdictBadge)

        driverTitleText = TextView(this).apply {
            text = "Device: Initializing..."
            textSize = 16f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.WHITE)
        }
        statusCard.addView(driverTitleText)

        driverDetailsText = TextView(this).apply {
            text = "Driver: Loading..."
            textSize = 13f
            setTextColor(Color.parseColor("#A0ABC0"))
            setPadding(0, 8, 0, 12)
        }
        statusCard.addView(driverDetailsText)

        configDetailsText = TextView(this).apply {
            text = "Staged Config: Checking..."
            textSize = 12f
            setTextColor(Color.parseColor("#718096"))
        }
        statusCard.addView(configDetailsText)

        root.addView(statusCard)

        // Action Buttons Row
        val buttonsRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            val params = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, 0, 0, 24) }
            layoutParams = params
        }

        val refreshBtn = Button(this).apply {
            text = "🔄 Re-scan Active Driver"
            setBackgroundColor(Color.parseColor("#00E5FF"))
            setTextColor(Color.BLACK)
            setOnClickListener { runVulkanScan() }
        }
        buttonsRow.addView(refreshBtn)

        root.addView(buttonsRow)

        // Scrollable Log Output
        val scrollView = ScrollView(this).apply {
            val params = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
            layoutParams = params
            setBackgroundColor(Color.parseColor("#0A0D14"))
        }

        rawLogText = TextView(this).apply {
            textSize = 11f
            setPadding(20, 20, 20, 20)
            setTextColor(Color.parseColor("#CBD5E0"))
            typeface = Typeface.MONOSPACE
            text = "Logcat output initialized...\n"
        }
        scrollView.addView(rawLogText)
        root.addView(scrollView)

        setContentView(root)

        // Initial scan
        runVulkanScan()
    }

    private fun runVulkanScan() {
        verdictBadge.text = "🔍 Probing Vulkan driver in-process..."
        verdictBadge.setTextColor(Color.YELLOW)

        Thread {
            try {
                // Read staged KaosGFX config
                val config = readActiveConfig()

                // Refresh hook state if injected TurnipAppComponentFactory is present
                try {
                    val factoryClz = Class.forName("dev.kaos.kaosgfx.bootstrap.TurnipAppComponentFactory")
                    val refreshMethod = factoryClz.getMethod("nativeRefreshHooks")
                    refreshMethod.invoke(null)
                    Log.i(TAG, "Explicitly called nativeRefreshHooks before runStandardVulkanProbe")
                } catch (t: Throwable) {
                    Log.i(TAG, "Factory refresh note: ${t.message}")
                }

                val probeResult = runStandardVulkanProbe()

                runOnUiThread {
                    updateUiWithProbeResult(config, probeResult)
                }
            } catch (e: Throwable) {
                Log.e(TAG, "Error running probe", e)
                runOnUiThread {
                    verdictBadge.text = "❌ Probe Error: ${e.message}"
                    verdictBadge.setTextColor(Color.RED)
                    rawLogText.append("\nException during probe: ${e.message}\n${Log.getStackTraceString(e)}")
                }
            }
        }.start()
    }

    private fun updateUiWithProbeResult(config: DriverConfig?, result: String) {
        rawLogText.text = "=== LIVE VULKAN PROBE RESULT ===\n$result\n"

        // Parse probe lines
        var deviceName = "Unknown Device"
        var driverName = "Unknown Driver"
        var driverInfo = "Unknown Info"
        var driverId = ""
        var apiVersion = "Unknown API"

        for (line in result.lines()) {
            val trimmed = line.trim()
            when {
                trimmed.startsWith("Device [0]:") -> deviceName = trimmed.substringAfter("Device [0]:").trim()
                trimmed.startsWith("Driver Name:") -> driverName = trimmed.substringAfter("Driver Name:").trim()
                trimmed.startsWith("Driver Info:") -> driverInfo = trimmed.substringAfter("Driver Info:").trim()
                trimmed.startsWith("Driver ID:") -> driverId = trimmed.substringAfter("Driver ID:").trim()
                trimmed.startsWith("API Version:") -> apiVersion = trimmed.substringAfter("API Version:").trim()
            }
        }

        driverTitleText.text = "🎮 Device: $deviceName"
        driverDetailsText.text = "🔧 Driver: $driverName ($driverInfo)\n📊 API Version: $apiVersion  •  Driver ID: $driverId"

        val isTurnip = driverName.contains("turnip", ignoreCase = true) ||
                driverInfo.contains("mesa", ignoreCase = true) ||
                driverId == "18"

        if (isTurnip) {
            verdictBadge.text = "🟢 CUSTOM TURNIP DRIVER ACTIVE"
            verdictBadge.setTextColor(Color.parseColor("#00E676"))
        } else {
            verdictBadge.text = "⚪ STOCK SYSTEM DRIVER ACTIVE (Qualcomm Adreno)"
            verdictBadge.setTextColor(Color.parseColor("#E0E0E0"))
        }

        if (config != null) {
            val title = config.driverTitle.ifBlank { config.driverId.ifBlank { "Custom Staged Driver" } }
            configDetailsText.text = "📁 KaosGFX Config: $title\n🎯 Target Binary: ${config.driverName}\n📂 Staged Dir: ${config.driverDir}"
        } else {
            configDetailsText.text = "📁 KaosGFX Config: None detected (Stock Mode)"
        }
    }

    private fun readActiveConfig(): DriverConfig? {
        val candidateFiles = listOf(
            File("/data/local/tmp/kaosgfx/$packageName/kaosgfx_driver_config.json"),
            File("/sdcard/Android/data/$packageName/files/kaosgfx/kaosgfx_driver_config.json"),
            File(getExternalFilesDir("kaosgfx"), "kaosgfx_driver_config.json"),
            File(filesDir, "kaosgfx_driver_config.json")
        )

        for (file in candidateFiles) {
            if (file != null && file.exists() && file.canRead()) {
                try {
                    val jsonStr = file.readText()
                    val obj = JSONObject(jsonStr)
                    return DriverConfig(
                        driverDir = obj.optString("driverDir", ""),
                        driverName = obj.optString("driverName", ""),
                        driverId = obj.optString("driverId", ""),
                        driverTitle = obj.optString("driverTitle", "")
                    )
                } catch (e: Exception) {
                    Log.w(TAG, "Config parse error: ${e.message}")
                }
            }
        }
        return null
    }

    data class DriverConfig(
        val driverDir: String,
        val driverName: String,
        val driverId: String,
        val driverTitle: String
    )
}
