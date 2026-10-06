package kami.libs.economy

import kami.libs.LibSettings
import kami.libs.db.Db
import kami.libs.db.SavePoint
import java.nio.file.Files
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals

class CoinWatchTest {
    private val s = LibSettings()

    private fun snap(minted: Long, burned: Long, vararg days: Pair<Pair<Long, CoinSource>, LongArray>) = Coins.Snapshot(minted, burned, days.toMap())

    @Test
    fun alertLevels() {
        assertEquals(CoinAlert.CRITICAL, CoinWatch.assess(snap(100, 150), 10, s).first)
        assertEquals(CoinAlert.OK, CoinWatch.assess(snap(20_000, 15_000, (10L to CoinSource.TERMINAL) to longArrayOf(20_000, 15_000)), 10, s).first)
        val ratio = CoinWatch.assess(snap(50_000, 18_000, (8L to CoinSource.KAMI) to longArrayOf(46_000, 0), (9L to CoinSource.TERMINAL) to longArrayOf(4_000, 18_000)), 10, s)
        assertEquals(CoinAlert.WARNING to "kami_libs.coins.reason.deposit_ratio", ratio)
        assertEquals(CoinAlert.OK, CoinWatch.assess(snap(5_000, 3_000, (9L to CoinSource.TERMINAL) to longArrayOf(1_000, 3_000)), 10, s).first)
        assertEquals(CoinAlert.WARNING to "kami_libs.coins.reason.detector_failing", CoinWatch.assess(snap(5_000, 3_000), 10, s, failing = true))
    }

    @Test
    fun playerAggregation() {
        val root = Files.createTempDirectory("kami-coinwatch")
        try {
            Db.open(root.resolve("kami").resolve("kami.db"))
            Coins.load()
            val big = PlayerParty(UUID.randomUUID())
            val small = PlayerParty(UUID.randomUUID())
            Coins.burned(CoinSource.TERMINAL, big, 80_000)
            Coins.minted(CoinSource.TERMINAL, big, 10_000)
            Coins.minted(CoinSource.TERMINAL, small, 500)
            Coins.burned(CoinSource.DEPOSITOR, small, 9_999)
            SavePoint.run()
            Db.flush()
            val page = CoinWatch.players(null, 0)
            assertEquals(2L, page.total)
            val top = page.rows.first()
            assertEquals(listOf<Any>(big.uuid.toString(), 80_000L, 10_000L, 70_000L, true), listOf(top.uuid, top.deposited, top.withdrawn, top.net, top.flagged))
            val low = page.rows.last()
            assertEquals(listOf<Any>(0L, 500L, false), listOf(low.deposited, low.withdrawn, low.flagged))
        } finally {
            Db.close()
            root.toFile().deleteRecursively()
        }
    }
}
