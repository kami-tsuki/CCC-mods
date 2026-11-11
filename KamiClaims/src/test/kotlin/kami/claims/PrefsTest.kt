package kami.claims

import kami.claims.client.Prefs
import kami.libs.ui.settings.SettingsStore
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PrefsTest {
    private val base = Files.createTempDirectory("claims-prefs")
    private val file = base.resolve("settings.json")

    @AfterTest
    fun tearDown() { base.toFile().deleteRecursively() }

    @Test
    fun finishedTourStaysFinishedAfterRestart() {
        val store = SettingsStore(file)
        val first = store.register("claims", Prefs.serializer(), Prefs())
        assertFalse(first.value.tourDone)
        first.value.tourDone = true
        store.save()
        val second = SettingsStore(file).register("claims", Prefs.serializer(), Prefs())
        assertTrue(second.value.tourDone)
        assertEquals(300, second.value.borderDensity)
    }

    @Test
    fun legacyFileIsImportedOnce() {
        val legacy = base.resolve("client.json")
        Files.writeString(legacy, """{"tourDone": true, "borderDensity": 120}""")
        val imported = SettingsStore(file).register("claims", Prefs.serializer(), Prefs(), legacy)
        assertTrue(imported.value.tourDone)
        assertEquals(120, imported.value.borderDensity)
        assertFalse(Files.exists(legacy))
        assertEquals(120, SettingsStore(file).register("claims", Prefs.serializer(), Prefs()).value.borderDensity)
    }

    @Test
    fun undecodableLegacyFileStaysUntouched() {
        val legacy = base.resolve("client.json")
        Files.writeString(legacy, """{"tourDone": "maybe", "borderDensity": "many"}""")
        val section = SettingsStore(file).register("claims", Prefs.serializer(), Prefs(), legacy)
        assertEquals(300, section.value.borderDensity)
        assertTrue(Files.exists(legacy))
        assertFalse(Files.exists(base.resolve("client.json.bak")))
    }
}
