package kami.libs.config

import kotlinx.serialization.Serializable
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ConfigFolderTest {
    @Serializable
    data class Demo(val count: Int = 1)

    private lateinit var dir: Path
    private lateinit var folder: ConfigFolder
    private val docs = mapOf("count" to "How many.")

    @BeforeTest
    fun setUp() {
        dir = Files.createTempDirectory("kami-config-folder")
        folder = ConfigFolder(dir.resolve("research"), "Reload to apply.")
        folder.startLoad()
    }

    @AfterTest
    fun tearDown() {
        dir.toFile().deleteRecursively()
    }

    private fun single() = folder.file("main.json", Demo.serializer(), Demo(), docs, "Main file.")

    @Test
    fun `missing file is written with header and docs`() {
        assertEquals(Demo(), single())
        val text = Files.readString(dir.resolve("research/main.json"))
        assertTrue(text.startsWith("// Main file.\n// Reload to apply.\n"))
        assertTrue("// How many." in text)
    }

    @Test
    fun `existing file is read`() {
        Files.createDirectories(dir.resolve("research"))
        Files.writeString(dir.resolve("research/main.json"), "{ \"count\": 7 } // note")
        assertEquals(7, single().count)
    }

    @Test
    fun `broken file keeps the last good value and reports a problem`() {
        Files.createDirectories(dir.resolve("research"))
        Files.writeString(dir.resolve("research/main.json"), "{ \"count\": 7 }")
        single()
        Files.writeString(dir.resolve("research/main.json"), "{ nope")
        folder.startLoad()
        assertEquals(7, single().count)
        assertEquals(1, folder.problems.size)
        assertTrue(folder.problems.single().startsWith("main.json"))
    }

    @Test
    fun `subfolder writes defaults and reads every json file`() {
        val defaults = mapOf("a" to Demo(1), "b" to Demo(2))
        assertEquals(defaults, folder.files("groups", Demo.serializer(), defaults, docs, "Group."))
        Files.writeString(dir.resolve("research/groups/c.json"), "{ \"count\": 3 }")
        Files.writeString(dir.resolve("research/groups/d.json"), "broken")
        Files.writeString(dir.resolve("research/groups/notes.txt"), "ignored")
        folder.startLoad()
        val loaded = folder.files("groups", Demo.serializer(), defaults, docs, "Group.")
        assertEquals(listOf("a", "b", "c"), loaded.keys.toList())
        assertEquals("groups/d.json", folder.problems.single().substringBefore(":"))
    }
}
