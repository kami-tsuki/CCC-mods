package kami.economy.economy

import kami.economy.Config
import kami.economy.Data
import kami.economy.Market
import kami.economy.Settings
import kami.economy.world.KeptCoins
import kami.economy.world.acceptsCoins
import kami.economy.world.claimant
import kami.economy.world.coinUseAllowed
import kami.libs.db.Db
import java.util.UUID
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CoinRulesTest {
    @BeforeTest
    fun setUp() {
        Config.s = Settings()
        Market.data = Data()
        TestDb.open()
        KeptCoins.load()
    }

    @AfterTest
    fun tearDown() {
        KeptCoins.unload()
        TestDb.close()
    }

    @Test
    fun onlyAllowlistedSlotsAcceptCoins() {
        val slot = "net.minecraft.world.inventory.Slot"
        val gate = "kami.libs.menu.GridMenu\$Gate"
        assertTrue(acceptsCoins(true, slot, null))
        assertFalse(acceptsCoins(false, slot, null))
        assertTrue(acceptsCoins(false, "dev.ithundxr.createnumismatics.content.coins.SlotInputMergingCoinBag", null))
        assertTrue(acceptsCoins(false, "dev.ithundxr.createnumismatics.content.coins.SlotDiscreteCoinBag", null))
        assertTrue(acceptsCoins(false, gate, "kami.essentials.trade.TradeMenu"))
        assertFalse(acceptsCoins(false, gate, "kami.essentials.inv.ViewMenu"))
    }

    @Test
    fun keptCoinsSurviveSavePointAndReopen() {
        val id = UUID.randomUUID()
        KeptCoins.add(id, "numismatics:cog", 70)
        KeptCoins.add(id, "numismatics:spur", 3)
        assertEquals(20, KeptCoins.take(id, "numismatics:cog", 20))
        TestDb.restart()
        KeptCoins.load()
        assertEquals(mapOf("numismatics:cog" to 50, "numismatics:spur" to 3), KeptCoins.of(id))
        assertEquals(3, KeptCoins.take(id, "numismatics:spur", 10))
        assertEquals(0, KeptCoins.take(id, "numismatics:spur", 1))
        TestDb.restart()
        KeptCoins.load()
        assertEquals(mapOf("numismatics:cog" to 50), KeptCoins.of(id))
    }

    @Test
    fun coinsOnlyUsableOnAllowedBlockEntities() {
        assertTrue(coinUseAllowed("minecraft:stone", false))
        assertTrue(coinUseAllowed("numismatics:vendor", true))
        assertTrue(coinUseAllowed("create:stock_ticker", true))
        assertFalse(coinUseAllowed("farmersdelight:cutting_board", true))
        assertFalse(coinUseAllowed("create:white_table_cloth", true))
    }

    @Test
    fun keptCoinsAreWrittenImmediately() {
        val id = UUID.randomUUID()
        fun stored(): Int? {
            Db.flush()
            return Db.read { one("SELECT count FROM eco_kept_coins WHERE uuid = ?", id.toString()) { it.int("count") } }
        }
        KeptCoins.add(id, "numismatics:cog", 5)
        assertEquals(5, stored())
        KeptCoins.take(id, "numismatics:cog", 5)
        assertNull(stored())
    }

    @Test
    fun droppedCoinsGoToTheBlockOwnerFirst() {
        val (a, b, c) = List(3) { UUID.randomUUID() }
        assertEquals(a, claimant(a, b, c))
        assertEquals(b, claimant(null, b, c))
        assertEquals(c, claimant(null, null, c))
        assertNull(claimant(null, null, null))
    }
}
