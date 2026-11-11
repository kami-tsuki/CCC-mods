package kami.libs.ui.settings

import kotlinx.serialization.Serializable
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SettingsStoreTest {
    @Serializable
    data class Demo(var scale: Float = 1f, var name: String = "a")

    private lateinit var dir: Path
    private lateinit var file: Path

    @BeforeTest
    fun setUp() {
        dir = Files.createTempDirectory("kami-settings")
        file = dir.resolve("client.json")
    }

    @AfterTest
    fun tearDown() {
        dir.toFile().deleteRecursively()
    }

    @Test
    fun `missing file gives defaults`() {
        assertEquals(Demo(), SettingsStore(file).register("ui", Demo.serializer(), Demo()).value)
    }

    @Test
    fun `values round trip`() {
        val store = SettingsStore(file)
        store.register("ui", Demo.serializer(), Demo()).value = Demo(1.5f, "b")
        store.save()
        assertEquals(Demo(1.5f, "b"), SettingsStore(file).register("ui", Demo.serializer(), Demo()).value)
    }

    @Test
    fun `unknown keys and sections survive a save`() {
        Files.writeString(file, """{"version":2,"ui":{"scale":2.0,"future":1},"mods.other":{"x":5}}""")
        val store = SettingsStore(file)
        val s = store.register("ui", Demo.serializer(), Demo())
        assertEquals(Demo(2f, "a"), s.value)
        store.save()
        assertTrue("mods.other" in Files.readString(file))
    }

    @Test
    fun `broken file falls back to defaults and keeps a copy`() {
        Files.writeString(file, "{nope")
        assertEquals(Demo(), SettingsStore(file).register("ui", Demo.serializer(), Demo()).value)
        assertTrue(Files.exists(dir.resolve("client.json.bak")))
    }

    @Test
    fun `legacy file is imported once and moved aside`() {
        val legacy = dir.resolve("old.json")
        Files.writeString(legacy, """{"scale":1.25,"name":"legacy"}""")
        val store = SettingsStore(file)
        assertEquals(Demo(1.25f, "legacy"), store.register("ui", Demo.serializer(), Demo(), legacy).value)
        assertFalse(Files.exists(legacy))
        assertTrue(Files.exists(dir.resolve("old.json.bak")))
        assertEquals(Demo(1.25f, "legacy"), SettingsStore(file).register("ui", Demo.serializer(), Demo(), legacy).value)
    }
}
