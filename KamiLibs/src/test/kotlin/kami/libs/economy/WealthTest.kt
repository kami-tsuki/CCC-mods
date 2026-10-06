package kami.libs.economy

import kami.libs.db.Db
import kami.libs.db.SavePoint
import kami.libs.util.Time
import java.nio.file.Files
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals

class WealthTest {
    @Test
    fun math() {
        assertEquals(0.0, Wealth.gini(listOf(5, 5)))
        assertEquals(0.75, Wealth.gini(listOf(0, 0, 0, 10)), 1e-9)
        assertEquals(0.0, Wealth.gini(emptyList()))
        assertEquals(7L, Wealth.median(listOf(9, 1, 7)))
        assertEquals(5L, Wealth.median(listOf(10, 0)))
        assertEquals(Long.MAX_VALUE, Wealth.total(listOf(Long.MAX_VALUE, 5L)))
        assertEquals(Long.MIN_VALUE, Wealth.total(listOf(Long.MIN_VALUE, -5L)))
        assertEquals(
            listOf(Triple(0L, 0L, 1), Triple(1L, 9L, 2), Triple(10L, 99L, 1), Triple(100L, 999L, 1)),
            Wealth.brackets(listOf(0, 5, 9, 10, 150)).map { Triple(it.min, it.max, it.count) }
        )
    }

    @Test
    fun snapshotPersists() {
        val root = Files.createTempDirectory("kami-wealth")
        try {
            val file = root.resolve("kami").resolve("kami.db")
            Db.open(file)
            ServerBank.load()
            Wealth.load()
            val a = PlayerParty(UUID.randomUUID())
            Wealth.distribution("players") { listOf(Wealth.Holder(a.key, "A", 300), Wealth.Holder(PlayerParty(UUID.randomUUID()).key, "B", 100)) }
            Wealth.distribution("countries") { listOf(Wealth.Holder("c:x", "X", 500), Wealth.Holder("c:y", "Y", -50)) }
            Wealth.source("escrow.test") { 40 }
            Wealth.source("bank") { ServerBank.balance }
            ServerBank.pay(a, 25, Flow.REWARD)
            val day = System.currentTimeMillis() / Time.DAY_MS
            Wealth.record(day)
            SavePoint.run()
            Db.close()
            Db.open(file)
            Wealth.load()
            val back = Wealth.history(30).single()
            assertEquals(listOf(400L, 500L, -50L, 40L, -25L), listOf(back.players, back.countries, back.countriesNegative, back.escrow, back.bank))
            assertEquals(listOf("X", "A", "B", "Y"), back.top.map { it.name })
            assertEquals(mapOf(Flow.REWARD to 25L), back.minted)
            assertEquals(200L, back.median)
        } finally {
            Db.close()
            root.toFile().deleteRecursively()
        }
    }
}
