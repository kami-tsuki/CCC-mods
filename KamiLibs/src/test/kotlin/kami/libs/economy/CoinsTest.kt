package kami.libs.economy

import kami.libs.db.Db
import kami.libs.db.SavePoint
import java.nio.file.Files
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals

class CoinsTest {
    @Test
    fun sessionsSupplyAndPersistence() {
        val root = Files.createTempDirectory("kami-coins")
        try {
            val file = root.resolve("kami").resolve("kami.db")
            Db.open(file)
            Coins.load()
            val player = PlayerParty(UUID.randomUUID())
            val owner = PlayerParty(UUID.randomUUID())
            Coins.minted(CoinSource.TERMINAL, player, 100)
            Coins.minted(CoinSource.TERMINAL, player, 20)
            Coins.burned(CoinSource.TERMINAL, player, 30)
            Coins.moved(CoinSource.VENDOR, owner, 64, "1,2,3")
            Coins.moved(CoinSource.VENDOR, owner, 16, "1,2,3")
            Coins.burned(CoinSource.DEPOSITOR, null, 10)
            assertEquals(80L, Coins.supply)
            SavePoint.run()
            Db.flush()
            val legs = Journal.query(Journal.Filter(party = player), 0).rows.associate { it.flow to it.delta }
            assertEquals(mapOf(Flow.COIN_WITHDRAW to -120L, Flow.COIN_DEPOSIT to 30L), legs)
            val vendor = Journal.query(Journal.Filter(party = owner), 0).rows.single()
            assertEquals(Flow.VENDOR to 80L, vendor.flow to vendor.delta)
            assertEquals("vendor 1,2,3 ×2", vendor.note)
            Db.close()
            Db.open(file)
            Coins.load()
            assertEquals(80L, Coins.supply)
            assertEquals(listOf(80L, 120L, 40L), Db.read { one("SELECT supply, minted, burned FROM libs_coin_supply") { listOf(it.long("supply"), it.long("minted"), it.long("burned")) } })
        } finally {
            Db.close()
            root.toFile().deleteRecursively()
        }
    }
}
