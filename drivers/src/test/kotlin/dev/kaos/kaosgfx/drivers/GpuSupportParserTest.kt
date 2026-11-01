package dev.kaos.kaosgfx.drivers

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GpuSupportParserTest {

    private val adreno825 = AdrenoGpu(
        rawRenderer = "Adreno825v1",
        model = 825,
        gen = AdrenoGen.A8XX,
        socModel = "SM8845",
        pageSizeKb = 4
    )

    private val adreno750 = AdrenoGpu(
        rawRenderer = "Adreno (TM) 750",
        model = 750,
        gen = AdrenoGen.A7XX,
        socModel = "SM8650",
        pageSizeKb = 4
    )

    private val adreno720 = AdrenoGpu(
        rawRenderer = "Adreno (TM) 720",
        model = 720,
        gen = AdrenoGen.A7XX,
        socModel = "SM7550",
        pageSizeKb = 4
    )

    private val adreno650 = AdrenoGpu(
        rawRenderer = "Adreno (TM) 650",
        model = 650,
        gen = AdrenoGen.A6XX,
        socModel = "SM8250",
        pageSizeKb = 4
    )

    @Test
    fun `parseGpuString detects Adreno825v1 and standard Adreno strings`() {
        val gpu825 = GpuDetector.parseGpuString("Adreno825v1")
        assertEquals(825, gpu825.model)
        assertEquals(AdrenoGen.A8XX, gpu825.gen)

        val gpu750 = GpuDetector.parseGpuString("Adreno (TM) 750")
        assertEquals(750, gpu750.model)
        assertEquals(AdrenoGen.A7XX, gpu750.gen)

        val gpu650 = GpuDetector.parseGpuString("Qualcomm Adreno 650")
        assertEquals(650, gpu650.model)
        assertEquals(AdrenoGen.A6XX, gpu650.gen)
    }

    @Test
    fun `Banners Turnip multi-asset release disambiguates standard vs A8xx vs 710-720 vs 8G2`() {
        val siblings = listOf(
            "Turnip-v27.0.0-20261101.zip",
            "Turnip-v27.0.0-20261101-A8xx.zip",
            "Turnip-v27.0.0-20261101-710-720-Test.zip",
            "Turnip-v27.0.0-20261101-8G2-OneUI.zip"
        )
        val body = "Compatible with Adreno 6xx, 7xx, and 8xx (see asset table)"

        val standard = GpuSupportParser.parse(
            assetName = "Turnip-v27.0.0-20261101.zip",
            releaseName = "Turnip v27.0.0-20261101",
            bodyText = body,
            siblingAssetNames = siblings
        )
        assertEquals(setOf(AdrenoGen.A6XX, AdrenoGen.A7XX), standard.supportedGens)
        assertEquals(CompatibilityStatus.INCOMPATIBLE, standard.isCompatibleWith(adreno825))
        assertEquals(CompatibilityStatus.COMPATIBLE, standard.isCompatibleWith(adreno750))
        assertEquals(CompatibilityStatus.COMPATIBLE, standard.isCompatibleWith(adreno650))

        val a8xx = GpuSupportParser.parse(
            assetName = "Turnip-v27.0.0-20261101-A8xx.zip",
            releaseName = "Turnip v27.0.0-20261101",
            bodyText = body,
            siblingAssetNames = siblings
        )
        assertEquals(setOf(AdrenoGen.A8XX), a8xx.supportedGens)
        assertEquals(CompatibilityStatus.COMPATIBLE, a8xx.isCompatibleWith(adreno825))
        assertEquals(CompatibilityStatus.INCOMPATIBLE, a8xx.isCompatibleWith(adreno750))

        val a710 = GpuSupportParser.parse(
            assetName = "Turnip-v27.0.0-20261101-710-720-Test.zip",
            releaseName = "Turnip v27.0.0-20261101",
            bodyText = body,
            siblingAssetNames = siblings
        )
        assertEquals(setOf(710, 720), a710.specificModels)
        assertEquals(CompatibilityStatus.COMPATIBLE, a710.isCompatibleWith(adreno720))
        assertEquals(CompatibilityStatus.INCOMPATIBLE, a710.isCompatibleWith(adreno750))
        assertEquals(CompatibilityStatus.INCOMPATIBLE, a710.isCompatibleWith(adreno825))

        val a8g2 = GpuSupportParser.parse(
            assetName = "Turnip-v27.0.0-20261101-8G2-OneUI.zip",
            releaseName = "Turnip v27.0.0-20261101",
            bodyText = body,
            siblingAssetNames = siblings
        )
        assertEquals(setOf(740), a8g2.specificModels)
        assertEquals(CompatibilityStatus.INCOMPATIBLE, a8g2.isCompatibleWith(adreno750))
    }

    @Test
    fun `StevenMXZ Gen8 V38 parses A8xx and specific models`() {
        val support = GpuSupportParser.parse(
            assetName = "Turnip_Gen8_V38.zip",
            releaseName = "Mesa Turnip driver v27.0.0-R1 (Gen 8 V38)",
            tagName = "v27.0.0-R1",
            bodyText = "Turnip driver built from latest mesa commit for A8xx GPUs ( a840, a830, a829, a825, 810)."
        )
        assertEquals(setOf(AdrenoGen.A8XX), support.supportedGens)
        assertTrue(825 in support.specificModels)
        assertEquals(CompatibilityStatus.COMPATIBLE, support.isCompatibleWith(adreno825))
        assertEquals(CompatibilityStatus.INCOMPATIBLE, support.isCompatibleWith(adreno750))
    }

    @Test
    fun `MrPurple Unified release parses A6xx A7xx A8xx`() {
        val support = GpuSupportParser.parse(
            assetName = "turnip_mrpurple_T30-toasted.adpkg.zip",
            releaseName = "Turnip MrPurple T30",
            bodyText = "Unified: A6XX, A7XX, A8XX"
        )
        assertEquals(setOf(AdrenoGen.A6XX, AdrenoGen.A7XX, AdrenoGen.A8XX), support.supportedGens)
        assertEquals(CompatibilityStatus.COMPATIBLE, support.isCompatibleWith(adreno825))
        assertEquals(CompatibilityStatus.COMPATIBLE, support.isCompatibleWith(adreno750))
        assertEquals(CompatibilityStatus.COMPATIBLE, support.isCompatibleWith(adreno650))
    }

    @Test
    fun `K11MCH1 Qualcomm blob with Maybe a6xx parses supported A7XX and tentative A6XX`() {
        val support = GpuSupportParser.parse(
            assetName = "Qualcomm_v840.adpkg.zip",
            releaseName = "Qualcomm v840",
            bodyText = "For a7xx. Maybe a6xx."
        )
        assertEquals(setOf(AdrenoGen.A7XX), support.supportedGens)
        assertEquals(setOf(AdrenoGen.A6XX), support.tentativeGens)
        assertEquals(CompatibilityStatus.COMPATIBLE, support.isCompatibleWith(adreno750))
        assertEquals(CompatibilityStatus.TENTATIVE, support.isCompatibleWith(adreno650))
        assertEquals(CompatibilityStatus.INCOMPATIBLE, support.isCompatibleWith(adreno825))
    }
}
