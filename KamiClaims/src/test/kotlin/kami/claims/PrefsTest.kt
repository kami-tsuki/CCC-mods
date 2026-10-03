package kami.claims

import kami.claims.client.Prefs
import kami.libs.config.Configs
import kami.libs.config.KamiConfig
import kami.libs.config.Section
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PrefsTest {
    private val base = Files.createTempDirectory("claims-prefs")
    private val sections = listOf(Section("client.json", "Client", mapOf("tourDone" to "Tour finished.")))

    private fun config() = KamiConfig("claims", Prefs(), sections, reloadable = false)

    @BeforeTest
    fun setUp() { Configs.base = base }

    @AfterTest
    fun tearDown() { base.toFile().deleteRecursively(); Configs.base = null }

    @Test
    fun finishedTourStaysFinishedAfterRestart() {
        val first = config()
        assertTrue(first.load())
        assertFalse(first.value.tourDone)
        first.value.tourDone = true
        first.save(first.value)
        val second = config()
        assertTrue(second.load())
        assertTrue(second.value.tourDone)
        assertEquals(300, second.value.borderDensity)
    }
}
