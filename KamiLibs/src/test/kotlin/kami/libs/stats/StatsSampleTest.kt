package kami.libs.stats

import kami.libs.db.Db
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

class StatsSampleTest {
    private lateinit var world: Path

    @BeforeTest
    fun setUp() {
        world = Files.createTempDirectory("kami-stats")
        Db.open(world.resolve("kami").resolve("kami.db"))
        Stats.load()
    }

    @AfterTest
    fun tearDown() {
        Db.close()
        world.toFile().deleteRecursively()
    }

    private fun samples() = Db.read(fresh = true) { one("SELECT COUNT(*) c FROM libs_event e JOIN libs_key k ON k.id = e.k WHERE k.key = ?", "sample.test") { it.int("c") } }

    @Test
    fun theFirstTickSamplesGaugesAtOnceAndThenWaitsForTheInterval() {
        Stats.gauge("sample.test") { mapOf("" to 5L) }
        Stats.tick()
        assertEquals(1, samples())
        Stats.tick()
        assertEquals(1, samples())
    }
}
