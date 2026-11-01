package dev.kaos.kaosgfx.drivers

import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.GLES20
import android.os.Build
import kotlinx.serialization.Serializable
import java.io.File

@Serializable
enum class AdrenoGen(val label: String) {
    A6XX("A6xx"),
    A7XX("A7xx"),
    A8XX("A8xx"),
    UNKNOWN("Unknown");

    companion object {
        fun fromModelNumber(model: Int?): AdrenoGen = when (model) {
            in 600..699 -> A6XX
            in 700..799 -> A7XX
            in 800..899 -> A8XX
            else -> UNKNOWN
        }
    }
}

@Serializable
data class AdrenoGpu(
    val model: Int?,
    val gen: AdrenoGen,
    val rawRenderer: String,
    val socModel: String = "",
    val pageSizeKb: Int = 4
) {
    val displayName: String
        get() = when {
            model != null && gen != AdrenoGen.UNKNOWN -> "Qualcomm Adreno $model (${gen.label})"
            rawRenderer.isNotBlank() -> rawRenderer
            else -> "Qualcomm Adreno (${gen.label})"
        }
}

object GpuDetector {

    private val MODEL_REGEX = Regex("""(?i)adreno[^\d]*(\d{3})\b|(?i)\badreno(\d{3})""")

    private val SOC_FALLBACK_MAP = mapOf(
        "SM8750" to 830,
        "SM8845" to 825,
        "SM8735" to 825,
        "SM8650" to 750,
        "SM8635" to 735,
        "SM8550" to 740,
        "SM7475" to 725,
        "SM8475" to 730,
        "SM8450" to 730,
        "SM8350" to 660,
        "SM8250" to 650,
        "SM8150" to 640
    )

    @Volatile
    private var cachedGpu: AdrenoGpu? = null

    fun detect(): AdrenoGpu {
        cachedGpu?.let { return it }

        val soc = getSocModel()
        val pageSizeKb = detectPageSizeKb()

        // 1. Primary: sysfs kgsl gpu_model (readable without GL context)
        val sysfsRaw = readSysfsGpuModel()
        if (!sysfsRaw.isNullOrBlank()) {
            val parsed = parseGpuString(sysfsRaw, soc, pageSizeKb)
            if (parsed.gen != AdrenoGen.UNKNOWN) {
                cachedGpu = parsed
                return parsed
            }
        }

        // 2. Secondary: 1x1 Pbuffer EGL GL_RENDERER query
        val eglRaw = queryGlRenderer()
        if (!eglRaw.isNullOrBlank()) {
            val parsed = parseGpuString(eglRaw, soc, pageSizeKb)
            if (parsed.gen != AdrenoGen.UNKNOWN) {
                cachedGpu = parsed
                return parsed
            }
        }

        // 3. Tertiary: SoC model lookup table
        val normalizedSoc = soc.uppercase().trim()
        val fallbackModel = SOC_FALLBACK_MAP.entries.firstOrNull { normalizedSoc.contains(it.key) }?.value
        val result = AdrenoGpu(
            model = fallbackModel,
            gen = AdrenoGen.fromModelNumber(fallbackModel),
            rawRenderer = sysfsRaw ?: eglRaw ?: "Adreno (${soc.ifBlank { "Unknown SoC" }})",
            socModel = soc,
            pageSizeKb = pageSizeKb
        )
        cachedGpu = result
        return result
    }

    fun parseGpuString(raw: String, socModel: String = "", pageSizeKb: Int = 4): AdrenoGpu {
        val match = MODEL_REGEX.find(raw)
        val digits = match?.groupValues?.drop(1)?.firstOrNull { it.isNotEmpty() }?.toIntOrNull()
        val gen = AdrenoGen.fromModelNumber(digits)
        return AdrenoGpu(
            model = digits,
            gen = gen,
            rawRenderer = raw.trim(),
            socModel = socModel,
            pageSizeKb = pageSizeKb
        )
    }

    private fun readSysfsGpuModel(): String? {
        val candidates = listOf(
            "/sys/class/kgsl/kgsl-3d0/gpu_model",
            "/sys/devices/platform/kgsl-3d0.0/kgsl/kgsl-3d0/gpu_model"
        )
        for (path in candidates) {
            try {
                val file = File(path)
                if (file.exists() && file.canRead()) {
                    val text = file.readText().trim()
                    if (text.isNotEmpty()) return text
                }
            } catch (_: Exception) {
            }
        }
        return null
    }

    private fun queryGlRenderer(): String? {
        var display = EGL14.EGL_NO_DISPLAY
        var surface = EGL14.EGL_NO_SURFACE
        var context = EGL14.EGL_NO_CONTEXT
        return try {
            display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
            if (display == EGL14.EGL_NO_DISPLAY) return null
            val version = IntArray(2)
            if (!EGL14.eglInitialize(display, version, 0, version, 1)) return null

            val configAttribs = intArrayOf(
                EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT,
                EGL14.EGL_NONE
            )
            val configs = arrayOfNulls<EGLConfig>(1)
            val numConfigs = IntArray(1)
            if (!EGL14.eglChooseConfig(display, configAttribs, 0, configs, 0, 1, numConfigs, 0) || numConfigs[0] == 0) {
                return null
            }
            val cfg = configs[0] ?: return null

            val ctxAttribs = intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE)
            context = EGL14.eglCreateContext(display, cfg, EGL14.EGL_NO_CONTEXT, ctxAttribs, 0)
            if (context == EGL14.EGL_NO_CONTEXT) return null

            val pbufAttribs = intArrayOf(EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE)
            surface = EGL14.eglCreatePbufferSurface(display, cfg, pbufAttribs, 0)
            if (surface == EGL14.EGL_NO_SURFACE) return null

            if (!EGL14.eglMakeCurrent(display, surface, surface, context)) return null
            GLES20.glGetString(GLES20.GL_RENDERER)
        } catch (_: Throwable) {
            null
        } finally {
            try {
                if (display != EGL14.EGL_NO_DISPLAY) {
                    EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
                    if (surface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, surface)
                    if (context != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(display, context)
                    EGL14.eglTerminate(display)
                }
            } catch (_: Throwable) {
            }
        }
    }

    private fun getSocModel(): String {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val soc = Build.SOC_MODEL
            if (!soc.isNullOrBlank() && soc != Build.UNKNOWN) return soc
        }
        return try {
            val proc = ProcessBuilder("getprop", "ro.soc.model").start()
            proc.inputStream.bufferedReader().readText().trim()
        } catch (_: Exception) {
            ""
        }
    }

    private fun detectPageSizeKb(): Int {
        return try {
            val pageSize = android.system.Os.sysconf(android.system.OsConstants._SC_PAGESIZE)
            if (pageSize > 0) (pageSize / 1024).toInt() else 4
        } catch (_: Throwable) {
            4
        }
    }
}
