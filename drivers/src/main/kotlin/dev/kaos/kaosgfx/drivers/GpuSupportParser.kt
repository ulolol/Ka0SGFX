package dev.kaos.kaosgfx.drivers

import kotlinx.serialization.Serializable

@Serializable
enum class SupportConfidence {
    EXPLICIT,
    TENTATIVE,
    UNVERIFIED
}

@Serializable
enum class DriverType(val label: String) {
    TURNIP("Mesa Turnip"),
    QUALCOMM_BLOB("Qualcomm Blob"),
    UNKNOWN("Driver")
}

enum class CompatibilityStatus {
    COMPATIBLE,
    TENTATIVE,
    UNVERIFIED,
    INCOMPATIBLE
}

@Serializable
data class GpuSupport(
    val supportedGens: Set<AdrenoGen> = emptySet(),
    val tentativeGens: Set<AdrenoGen> = emptySet(),
    val specificModels: Set<Int> = emptySet(),
    val confidence: SupportConfidence = SupportConfidence.UNVERIFIED,
    val note: String = ""
) {
    fun isCompatibleWith(gpu: AdrenoGpu): CompatibilityStatus {
        if (gpu.gen == AdrenoGen.UNKNOWN) return CompatibilityStatus.UNVERIFIED
        if (confidence == SupportConfidence.UNVERIFIED ||
            (supportedGens.isEmpty() && tentativeGens.isEmpty())
        ) {
            return CompatibilityStatus.UNVERIFIED
        }

        if (specificModels.isNotEmpty() && gpu.model != null) {
            return if (gpu.model in specificModels) {
                CompatibilityStatus.COMPATIBLE
            } else {
                CompatibilityStatus.INCOMPATIBLE
            }
        }

        if (gpu.gen in supportedGens) {
            return CompatibilityStatus.COMPATIBLE
        }
        if (gpu.gen in tentativeGens) {
            return CompatibilityStatus.TENTATIVE
        }
        return CompatibilityStatus.INCOMPATIBLE
    }

    val badgeLabels: List<String>
        get() {
            if (confidence == SupportConfidence.UNVERIFIED ||
                (supportedGens.isEmpty() && tentativeGens.isEmpty())
            ) {
                return listOf("Unverified GPU")
            }
            val labels = mutableListOf<String>()
            if (specificModels.isNotEmpty()) {
                val sorted = specificModels.sorted()
                labels.add("Adreno " + sorted.joinToString("/"))
            } else {
                for (gen in listOf(AdrenoGen.A6XX, AdrenoGen.A7XX, AdrenoGen.A8XX)) {
                    if (gen in supportedGens) {
                        labels.add(gen.label)
                    } else if (gen in tentativeGens) {
                        labels.add("${gen.label} (?)")
                    }
                }
            }
            return labels
        }
}

object GpuSupportParser {

    private val SPECIFIC_710_720_REGEX = Regex("""(?:^|[_\-\s(])710[-_]720(?:[_\-\s.)]|$)""", RegexOption.IGNORE_CASE)
    private val SPECIFIC_8G2_REGEX = Regex("""(?:^|[_\-\s(])(?:8g2|8\s*gen\s*2|a?740)(?:[_\-\s.)]|$)""", RegexOption.IGNORE_CASE)
    private val SPECIFIC_8G3_REGEX = Regex("""(?:^|[_\-\s(])(?:8g3|8\s*gen\s*3|a?750)(?:[_\-\s.)]|$)""", RegexOption.IGNORE_CASE)

    private val ASSET_A8XX_REGEX = Regex("""(?:a8xx|gen[_\-\s]?8|8[_\-\s]?elite|a8[12345]0|a829|a825)""", RegexOption.IGNORE_CASE)
    private val ASSET_A7XX_REGEX = Regex("""(?:a7xx|gen[_\-\s]?7|a7[12345]0)""", RegexOption.IGNORE_CASE)
    private val ASSET_A6XX_REGEX = Regex("""(?:a6xx|gen[_\-\s]?6|a6[12345689]0)""", RegexOption.IGNORE_CASE)

    private val MAYBE_GEN_REGEX = Regex(
        """(?:maybe|possibly|tentative|untested)[^.\n]{0,30}?\b(a[678]xx)\b""",
        RegexOption.IGNORE_CASE
    )
    private val GEN_MAYBE_REGEX = Regex(
        """\b(a[678]xx)\b[^.\n]{0,20}?\((?:maybe|untested|experimental)\)""",
        RegexOption.IGNORE_CASE
    )

    private val EXPLICIT_A8XX_BODY_REGEX = Regex(
        """(?:\ba8xx\b|\badreno\s*8[1-9]\d\b|\ba8[12345]0\b|\ba829\b|\ba825\b|\bgen\s*8\b|turnip/gen8|\b8\s*elite\b)""",
        RegexOption.IGNORE_CASE
    )
    private val EXPLICIT_A7XX_BODY_REGEX = Regex(
        """(?:\ba7xx\b|\badreno\s*7[1-9]\d\b|\ba7[12345]0\b|\bgen\s*7\b|\b8\s*gen\s*[123]\b|\b7\+?\s*gen\s*[23]\b|\b8s\s*gen\s*3\b)""",
        RegexOption.IGNORE_CASE
    )
    private val EXPLICIT_A6XX_BODY_REGEX = Regex(
        """(?:\ba6xx\b|\badreno\s*6[1-9]\d\b|\ba6[12345689]0\b|\bgen\s*6\b)""",
        RegexOption.IGNORE_CASE
    )

    private val SPECIFIC_MODELS_LIST_REGEX = Regex(
        """\b(?:a|adreno\s*)?(840|830|829|825|810|750|740|735|732|730|725|720|710|660|650|640|630|620|619|618|610)\b""",
        RegexOption.IGNORE_CASE
    )

    fun inferDriverType(
        assetName: String,
        releaseName: String,
        bodyText: String,
        libraryName: String = ""
    ): DriverType {
        val combined = "$assetName $releaseName $libraryName".lowercase()
        return when {
            libraryName.equals("vkgsl.so", ignoreCase = true) -> DriverType.QUALCOMM_BLOB
            combined.contains("qualcomm") ||
                (combined.contains("qcom") && !combined.contains("turnip")) ||
                (combined.contains("adreno") && !combined.contains("turnip") && !combined.contains("mesa") &&
                    Regex("""v?\d{3}(\.\d+)?""").containsMatchIn(assetName.lowercase())) -> DriverType.QUALCOMM_BLOB
            combined.contains("turnip") || combined.contains("mesa") || combined.contains("freedreno") ||
                libraryName.contains("freedreno", ignoreCase = true) -> DriverType.TURNIP
            bodyText.contains("turnip", ignoreCase = true) || bodyText.contains("mesa", ignoreCase = true) -> DriverType.TURNIP
            bodyText.contains("qualcomm", ignoreCase = true) -> DriverType.QUALCOMM_BLOB
            else -> DriverType.TURNIP
        }
    }

    /**
     * Parses GPU generation support for a specific release asset.
     *
     * Priority:
     * 1. Asset filename (most specific when a single release contains multiple build variants like
     *    `Turnip-v27.0.0-A8xx.zip`, `Turnip-v27.0.0-710-720-Test.zip`, `Turnip-v27.0.0-8G2-OneUI.zip`).
     * 2. Sibling asset disambiguation (e.g. if a release has both `*-A8xx.zip` and standard `Turnip-*.zip`,
     *    the standard `Turnip-*.zip` is for A6xx + A7xx).
     * 3. Release title / tag name.
     * 4. Release body markdown (handling `Unified: A6XX, A7XX, A8XX`, `For a7xx. Maybe a6xx.`, etc.).
     */
    fun parse(
        assetName: String,
        releaseName: String = "",
        tagName: String = "",
        bodyText: String = "",
        sourceRepo: String = "",
        siblingAssetNames: List<String> = emptyList()
    ): GpuSupport {
        val cleanAsset = assetName.substringBeforeLast(".zip")

        // 1. Check narrow model-specific asset filenames first
        if (SPECIFIC_710_720_REGEX.containsMatchIn(cleanAsset)) {
            return GpuSupport(
                supportedGens = setOf(AdrenoGen.A7XX),
                specificModels = setOf(710, 720),
                confidence = SupportConfidence.EXPLICIT,
                note = "Adreno 710 / 720 specific build"
            )
        }

        if (SPECIFIC_8G2_REGEX.containsMatchIn(cleanAsset)) {
            return GpuSupport(
                supportedGens = setOf(AdrenoGen.A7XX),
                specificModels = setOf(740),
                confidence = SupportConfidence.EXPLICIT,
                note = "Snapdragon 8 Gen 2 (Adreno 740) build"
            )
        }

        // 2. Check explicit generation tags in the asset filename itself
        val assetGens = mutableSetOf<AdrenoGen>()
        if (ASSET_A8XX_REGEX.containsMatchIn(cleanAsset)) assetGens.add(AdrenoGen.A8XX)
        if (ASSET_A7XX_REGEX.containsMatchIn(cleanAsset)) assetGens.add(AdrenoGen.A7XX)
        if (ASSET_A6XX_REGEX.containsMatchIn(cleanAsset)) assetGens.add(AdrenoGen.A6XX)

        if (assetGens.isNotEmpty()) {
            // Also check if release body enumerates specific A8xx models (e.g., StevenMXZ Gen8 builds)
            val specificModels = if (assetGens == setOf(AdrenoGen.A8XX)) {
                extractSpecificGen8ModelsIfRestricted(bodyText)
            } else {
                emptySet()
            }
            return GpuSupport(
                supportedGens = assetGens,
                specificModels = specificModels,
                confidence = SupportConfidence.EXPLICIT,
                note = "Matched from asset filename ($assetName)"
            )
        }

        // 3. Sibling asset disambiguation:
        // In repos like Banners-Turnip, a single release often publishes:
        // - Turnip-v27.0.0-20261101.zip (Standard: A6xx & A7xx)
        // - Turnip-v27.0.0-20261101-A8xx.zip (A8xx)
        // - Turnip-v27.0.0-20261101-710-720-Test.zip (710/720)
        // When a sibling asset has an explicit A8xx tag and this asset does not, this standard asset
        // targets A6xx + A7xx (not A8xx, even though the release body mentions A8xx in its Downloads table).
        val hasA8xxSibling = siblingAssetNames.any { sibling ->
            !sibling.equals(assetName, ignoreCase = true) &&
                ASSET_A8XX_REGEX.containsMatchIn(sibling.substringBeforeLast(".zip"))
        }
        if (hasA8xxSibling) {
            return GpuSupport(
                supportedGens = setOf(AdrenoGen.A6XX, AdrenoGen.A7XX),
                confidence = SupportConfidence.EXPLICIT,
                note = "Standard A6xx/A7xx build (separate A8xx asset exists in release)"
            )
        }

        // 4. Check release title + tag name + body text
        val combinedText = "$releaseName\n$tagName\n$bodyText"

        val tentativeGens = mutableSetOf<AdrenoGen>()
        for (match in MAYBE_GEN_REGEX.findAll(combinedText)) {
            parseGenToken(match.groupValues[1])?.let { tentativeGens.add(it) }
        }
        for (match in GEN_MAYBE_REGEX.findAll(combinedText)) {
            parseGenToken(match.groupValues[1])?.let { tentativeGens.add(it) }
        }

        // Strip out the "maybe a6xx" segments before scanning for confirmed generations
        val textWithoutMaybe = combinedText
            .replace(MAYBE_GEN_REGEX, " ")
            .replace(GEN_MAYBE_REGEX, " ")

        val supportedGens = mutableSetOf<AdrenoGen>()
        if (EXPLICIT_A8XX_BODY_REGEX.containsMatchIn(textWithoutMaybe)) {
            supportedGens.add(AdrenoGen.A8XX)
        }
        if (EXPLICIT_A7XX_BODY_REGEX.containsMatchIn(textWithoutMaybe)) {
            supportedGens.add(AdrenoGen.A7XX)
        }
        if (EXPLICIT_A6XX_BODY_REGEX.containsMatchIn(textWithoutMaybe)) {
            supportedGens.add(AdrenoGen.A6XX)
        }

        // Remove any gen from tentative if it was also confirmed as supported
        tentativeGens.removeAll(supportedGens)

        if (supportedGens.isNotEmpty() || tentativeGens.isNotEmpty()) {
            val confidence = if (supportedGens.isNotEmpty()) {
                SupportConfidence.EXPLICIT
            } else {
                SupportConfidence.TENTATIVE
            }
            return GpuSupport(
                supportedGens = supportedGens,
                tentativeGens = tentativeGens,
                confidence = confidence,
                note = "Matched from release metadata"
            )
        }

        // 5. Fallback for older pre-2026 Turnip releases (Mesa 24.x / 25.x) before A8xx existed
        if (isLegacyPreGen8Turnip(assetName, releaseName, tagName)) {
            return GpuSupport(
                supportedGens = setOf(AdrenoGen.A6XX, AdrenoGen.A7XX),
                confidence = SupportConfidence.TENTATIVE,
                note = "Pre-Mesa 26 Turnip build (A6xx/A7xx)"
            )
        }

        return GpuSupport(
            confidence = SupportConfidence.UNVERIFIED,
            note = "GPU generation not specified in release metadata"
        )
    }

    private fun parseGenToken(token: String): AdrenoGen? {
        return when (token.lowercase()) {
            "a6xx" -> AdrenoGen.A6XX
            "a7xx" -> AdrenoGen.A7XX
            "a8xx" -> AdrenoGen.A8XX
            else -> null
        }
    }

    private fun extractSpecificGen8ModelsIfRestricted(bodyText: String): Set<Int> {
        if (bodyText.isBlank()) return emptySet()
        // Only restrict specificModels if body explicitly lists a parenthetical set like "( a840, a830, a829, a825, 810)"
        val parenMatch = Regex("""\([^)]*\b8[12345]\d\b[^)]*\)""", RegexOption.IGNORE_CASE).find(bodyText)
            ?: return emptySet()
        val models = SPECIFIC_MODELS_LIST_REGEX.findAll(parenMatch.value)
            .mapNotNull { it.groupValues[1].toIntOrNull() }
            .filter { it in 800..899 }
            .toSet()
        return models
    }

    private fun isLegacyPreGen8Turnip(assetName: String, releaseName: String, tagName: String): Boolean {
        val combined = "$assetName $releaseName $tagName".lowercase()
        if (!combined.contains("turnip") && !combined.contains("mesa")) return false
        return Regex("""\bv?(?:23|24|25)\.\d+\.\d+\b""").containsMatchIn(combined)
    }
}
