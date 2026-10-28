package dev.kaos.kaosgfx.drivers

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DriverManagerTest {

    @Test
    fun testBuildConfigJson() {
        val driver = DriverMetadata(
            id = "test_turnip_25",
            name = "Turnip Test 25",
            version = "25.0",
            author = "Tester",
            downloadUrl = "https://example.com/driver.zip",
            fileName = "driver.zip",
            libraryName = "libvulkan_freedreno.so"
        )

        // Test manual config format
        val targetPkg = "com.example.game"
        val expectedDir = "/data/user/0/$targetPkg/files/turnip/${driver.id}"

        val config = DriverConfig(
            driverDir = expectedDir,
            driverName = driver.libraryName,
            enableAngle = true
        )

        assertEquals(expectedDir, config.driverDir)
        assertEquals("libvulkan_freedreno.so", config.driverName)
        assertTrue(config.enableAngle)
    }

    @Test
    fun testDriverModelsSerialization() {
        val driver = DriverMetadata(
            id = "banners_turnip",
            name = "Banners Turnip",
            version = "v25.0.0",
            author = "The412Banner",
            downloadUrl = "https://github.com/...",
            fileName = "turnip.zip",
            libraryName = "libvulkan_freedreno.so"
        )

        val jsonFormat = kotlinx.serialization.json.Json { encodeDefaults = true }
        val json = jsonFormat.encodeToString(DriverMetadata.serializer(), driver)
        assertTrue(json.contains("banners_turnip"))
        assertTrue(json.contains("libvulkan_freedreno.so"))

        val decoded = jsonFormat.decodeFromString(DriverMetadata.serializer(), json)
        assertEquals(driver.id, decoded.id)
        assertEquals(driver.libraryName, decoded.libraryName)
    }
}
