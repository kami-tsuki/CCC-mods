package kami.libs.config

import kotlinx.serialization.Serializable
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WorldStoreTest {
    @Serializable
    data class Demo(val count: Int = 0, val name: String = "kami")

    private lateinit var dir: Path
    private lateinit var path: Path

    private fun store() = WorldStore(Demo.serializer(), { Demo() })

    @BeforeTest
    fun setUp() {
        dir = Files.createTempDirectory("kami-world-store")
        path = dir.resolve("data.json")
    }

    @AfterTest
    fun tearDown() {
        dir.toFile().deleteRecursively()
    }

    @Test
    fun loadsDefaultWhenMissing() {
        val s = store()
        assertEquals(Demo(), s.load(path))
        assertFalse(s.dirty)
    }

    @Test
    fun savesOnlyWhenDirty() {
        val s = store()
        s.load(path)
        s.save()
        assertFalse(Files.exists(path))
        s.data = Demo(1, "a")
        s.changed()
        s.save()
        assertTrue(Files.exists(path))
        assertFalse(s.dirty)
    }

    @Test
    fun savesWhenForced() {
        val s = store()
        s.load(path)
        s.save(force = true)
        assertTrue(Files.exists(path))
    }

    @Test
    fun roundTripsAcrossReload() {
        val s = store()
        s.load(path)
        s.data = Demo(5, "hi")
        s.changed()
        s.save()
        val s2 = store()
        val loaded = s2.load(path)
        assertEquals(Demo(5, "hi"), loaded)
    }

    @Test
    fun writesBakOnSecondSave() {
        val s = store()
        s.load(path)
        s.data = Demo(1)
        s.changed()
        s.save()
        s.data = Demo(2)
        s.changed()
        s.save()
        assertTrue(Files.exists(path.resolveSibling("data.json.bak")))
        assertFalse(Files.exists(path.resolveSibling("data.json.tmp")))
    }

    @Test
    fun corruptFileMovedToBadAndDefaultReturned() {
        Files.writeString(path, "{ not json")
        val s = store()
        var failed = false
        val data = s.load(path) { failed = true }
        assertTrue(failed)
        assertEquals(Demo(), data)
        assertTrue(Files.exists(path.resolveSibling("data.json.bad")))
        assertFalse(Files.exists(path))
    }

    @Test
    fun corruptPrimaryWithValidBakRecoversBackup() {
        Files.writeString(path.resolveSibling("data.json.bak"), """{"count":7,"name":"bak"}""")
        Files.writeString(path, "{ not json")
        val s = store()
        var failed = false
        val data = s.load(path) { failed = true }
        assertTrue(failed)
        assertEquals(Demo(7, "bak"), data)
        assertTrue(s.dirty)
        assertTrue(Files.exists(path.resolveSibling("data.json.bad")))
    }

    @Test
    fun corruptPrimaryWithCorruptBakReturnsDefault() {
        Files.writeString(path.resolveSibling("data.json.bak"), "{ also not json")
        Files.writeString(path, "{ not json")
        val s = store()
        var failed = false
        val data = s.load(path) { failed = true }
        assertTrue(failed)
        assertEquals(Demo(), data)
        assertFalse(s.dirty)
    }

    @Test
    fun missingPrimaryWithBakRecoversBackup() {
        Files.writeString(path.resolveSibling("data.json.bak"), """{"count":7,"name":"bak"}""")
        val s = store()
        val data = s.load(path)
        assertEquals(Demo(7, "bak"), data)
        assertTrue(s.dirty)
    }
}
