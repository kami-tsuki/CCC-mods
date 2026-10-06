package kami.libs.config

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class KamiConfigTest {
    @Serializable
    data class Job(val pay: Int = 1)

    @Serializable
    data class Demo(val speed: Int = 5, val name: String = "kami", val jobs: Map<String, Job> = mapOf("miner" to Job()), val extra: Boolean = false)

    private lateinit var base: Path

    private val sections = listOf(
        Section("general.json", "General", mapOf("speed" to "How fast.", "name" to "Display name.")),
        Section("jobs.json", "Jobs", mapOf("jobs" to "All jobs.", "jobs.*.pay" to "Daily pay."))
    )

    private fun config(legacy: String? = null, migrations: List<ConfigMigration> = emptyList()) =
        KamiConfig("demo", Demo(), sections, legacy, sane = { it.copy(speed = it.speed.coerceIn(1, 10)) }, migrations = migrations)

    private fun read(file: String) = Files.readString(base.resolve("kami/demo/$file"))

    @BeforeTest
    fun setUp() {
        base = Files.createTempDirectory("kami-config")
        Configs.base = base
    }

    @AfterTest
    fun tearDown() {
        base.toFile().deleteRecursively()
        Configs.base = null
    }

    @Test
    fun writesSplitDocumentedFiles() {
        assertTrue(config().load())
        val general = read("general.json")
        assertTrue(general.startsWith("// General\n// Save, then run /demo reload to apply.\n"))
        assertTrue("// How fast." in general && "\"speed\": 5" in general)
        assertTrue("\"extra\": false" in general)
        assertFalse("jobs" in general)
        val jobs = read("jobs.json")
        assertTrue("// All jobs." in jobs && "// Daily pay." in jobs)
    }

    @Test
    fun readsEditsWithCommentsAndFixesValues() {
        config().load()
        Files.writeString(base.resolve("kami/demo/general.json"), read("general.json").replace("\"speed\": 5", "\"speed\": 99,\n    \"unknown\": 1"))
        val c = config()
        assertTrue(c.load())
        assertEquals(10, c.value.speed)
        assertTrue("\"speed\": 10" in read("general.json"))
        assertTrue("\"unknown\": 1" in read("general.json"))
    }

    @Test
    fun brokenFileKeepsValuesAndIsNotOverwritten() {
        val c = config()
        c.load()
        Files.writeString(base.resolve("kami/demo/general.json"), "{ \"speed\": 7 ")
        c.value = Demo(speed = 3)
        assertFalse(c.load())
        assertEquals(3, c.value.speed)
        assertTrue(c.problem!!.startsWith("general.json:"))
        assertEquals("{ \"speed\": 7 ", read("general.json"))
    }

    @Test
    fun migratesLegacyFlatFile() {
        Files.createDirectories(base)
        Files.writeString(base.resolve("old_demo.json"), """{"speed": 8, "jobs": {"miner": {"pay": 4}}, "coins": {}}""")
        val c = config("old_demo.json")
        assertTrue(c.load())
        assertEquals(8, c.value.speed)
        assertEquals(4, c.value.jobs.getValue("miner").pay)
        assertFalse(Files.exists(base.resolve("old_demo.json")))
        assertTrue(Files.exists(base.resolve("kami/demo/old_demo.json.old")))
        assertTrue("\"pay\": 4" in read("jobs.json"))
    }

    @Test
    fun reloadReportsEveryMod() {
        config().load()
        val results = Configs.reload("demo")
        assertTrue(results.isNotEmpty() && results.all { it.first == "demo" && it.second.isSuccess })
        assertTrue(Configs.reload("missing").isEmpty())
    }

    private val rename = listOf(ConfigMigration(1) { o -> JsonObject(o - "title" + ("name" to o.getValue("title"))) })

    private fun old() =
        Files.writeString(base.resolve("kami/demo/general.json").also { Files.createDirectories(it.parent) }, "{ \"title\": \"old\", \"speed\": 2, \"mystery\": 1 }")

    @Test
    fun migrationBacksUpAppliesAndBumpsVersion() {
        old()
        val c = config(migrations = rename)
        assertTrue(c.load())
        assertEquals("old", c.value.name)
        assertTrue("\"configVersion\": 1" in read("general.json"))
        assertTrue("\"mystery\": 1" in read("general.json"))
        assertTrue("\"title\": \"old\"" in read(".backup/general.json.v0.json"))
    }

    @Test
    fun newerVersionIsLoadedButNotRewritten() {
        val text = "{ \"configVersion\": 5, \"speed\": 2 }"
        Files.createDirectories(base.resolve("kami/demo"))
        Files.writeString(base.resolve("kami/demo/general.json"), text)
        val c = config(migrations = rename)
        assertTrue(c.load())
        assertEquals(2, c.value.speed)
        c.save(Demo(speed = 9))
        assertEquals(text, read("general.json"))
    }
}
