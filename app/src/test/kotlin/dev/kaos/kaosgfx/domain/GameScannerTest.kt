package dev.kaos.kaosgfx.domain

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GameScannerTest {

    @Test
    fun testCompetitiveGameDenylistProtection() {
        val genshin = "com.miHoYo.GenshinImpact"
        val pubg = "com.tencent.ig"
        val codm = "com.activision.callofduty.shooter"
        val wildrift = "com.riotgames.league.wildrift"
        val regularGame = "com.retro.emulator"

        assertTrue("Genshin Impact must be denylisted", GameScanner.DENYLIST.containsKey(genshin))
        assertTrue("PUBG Mobile must be denylisted", GameScanner.DENYLIST.containsKey(pubg))
        assertTrue("CoD Mobile must be denylisted", GameScanner.DENYLIST.containsKey(codm))
        assertTrue("Wild Rift must be denylisted", GameScanner.DENYLIST.containsKey(wildrift))
        assertFalse("Regular offline game should not be denylisted", GameScanner.DENYLIST.containsKey(regularGame))
    }
}
