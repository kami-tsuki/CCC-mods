package kami.economy.economy

import kami.economy.Config
import kami.economy.Data
import kami.economy.Market
import kami.economy.Order
import kami.economy.Settings
import kami.libs.claims.ClaimsApi
import kami.libs.claims.Relation
import java.nio.file.Files
import java.util.UUID
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GuardsTest {
    private class Purse : Wallet {
        var funds = 10_000L
        val deposits = mutableListOf<Int>()
        override fun balance(id: UUID) = funds
        override fun deduct(id: UUID, amount: Int): Int = amount.also { funds -= it }
        override fun deposit(id: UUID, amount: Int): Boolean { deposits += amount; return true }
    }

    private val buyer = UUID.randomUUID()
    private val taxed = UUID.randomUUID()
    private val ally = UUID.randomUUID()
    private val enemy = UUID.randomUUID()
    private val sameCountry = UUID.randomUUID()
    private val neutral = UUID.randomUUID()
    private val purse = Purse()

    private fun setup(): Fakes.Claims {
        Config.s = Settings()
        Market.data = Data()
        Ledger.attach(Files.createTempFile("kami_economy", ".wal"))
        Ledger.wallet = purse
        return Fakes.claims().apply {
            home += mapOf(buyer to "aurelia", sameCountry to "aurelia", taxed to "ostland", ally to "verdania", enemy to "karst", neutral to "nordmark")
            relations["aurelia" to "verdania"] = Relation.ALLIED
            relations["aurelia" to "karst"] = Relation.EMBARGO
            tariffs["aurelia" to "ostland"] = 15
            ClaimsApi.register(this)
        }
    }

    @AfterTest
    fun clear() = ClaimsApi.register(null)

    @Test
    fun tariffThatDoesNotFitTheTreasuryIsRefundedToThePayer() {
        val claims = setup().apply { creditRoom = 5 }
        Matching.insertSell("test:item", Order(1, taxed.toString(), 7, 100))
        assertTrue(Ledger.buy(buyer.toString(), "test:item", 20) is BuyResult.Ok)
        assertEquals(listOf("aurelia" to 5L), claims.credits)
        assertEquals(listOf(16), purse.deposits.filter { it == 16 })
    }

    @Test
    fun tradeXpIgnoresSameCountryAlliedAndEmbargoedPartners() {
        setup()
        val me = buyer.toString()
        assertFalse(Ledger.earnsXp(me, sameCountry.toString()))
        assertFalse(Ledger.earnsXp(me, ally.toString()))
        assertTrue(Ledger.earnsXp(me, neutral.toString()))
        assertTrue(Ledger.earnsXp(me, ""))
    }

    @Test
    fun embargoedCountriesCannotBidOrBuyNowAtAuctions() {
        setup()
        Auctions.insert(1, enemy.toString(), "", "x", 10, 50)
        assertEquals(BidResult.Embargoed("karst"), Ledger.auctionBid(buyer.toString(), 1, 20))
        assertEquals(BuyNowResult.Embargoed("karst"), Ledger.auctionBuyNow(buyer.toString(), 1))
        Auctions.insert(2, neutral.toString(), "", "x", 10, null)
        assertEquals(BidResult.Ok, Ledger.auctionBid(buyer.toString(), 2, 20))
    }
}
