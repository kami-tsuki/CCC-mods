package kami.economy.economy

import kami.economy.Config
import kami.economy.Data
import kami.economy.Market
import kami.economy.Order
import kami.economy.Settings
import kami.libs.claims.ClaimInfo
import kami.libs.claims.ClaimsApi
import kami.libs.claims.ClaimsProvider
import kami.libs.claims.Citizenship
import kami.libs.claims.Relation
import kami.libs.claims.TreasuryKind
import kami.libs.economy.CleanStep
import java.nio.file.Files
import java.util.UUID
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TradeTest {
    private class FakeClaims : ClaimsProvider {
        val home = mutableMapOf<UUID, String>()
        val relations = mutableMapOf<Pair<String, String>, Relation>()
        val tariffs = mutableMapOf<Pair<String, String>, Int>()
        val credits = mutableListOf<Triple<String, Long, TreasuryKind>>()
        override fun at(dim: String, x: Int, z: Int): ClaimInfo? = null
        override fun isBanished(player: UUID, country: String) = false
        override fun countryOf(player: UUID) = home[player]
        override fun citizenship(player: UUID): Citizenship? = null
        override fun relation(a: String, b: String) = relations[a to b] ?: relations[b to a] ?: Relation.NEUTRAL
        override fun tariff(buyerCountry: String, sellerCountry: String) = tariffs[buyerCountry to sellerCountry] ?: 0
        override fun credit(country: String, amount: Long, kind: TreasuryKind): Long { credits += Triple(country, amount, kind); return amount }
    }

    private class Purse(var funds: Long) : Wallet {
        val deposits = mutableMapOf<UUID, Long>()
        override fun balance(id: UUID) = funds
        override fun deduct(id: UUID, amount: Int): Int = minOf(amount.toLong(), funds).toInt().also { funds -= it }
        override fun deposit(id: UUID, amount: Int): Boolean { deposits.merge(id, amount.toLong(), Long::plus); return true }
    }

    private val buyer = UUID.randomUUID()
    private val neutral = UUID.randomUUID()
    private val ally = UUID.randomUUID()
    private val province = UUID.randomUUID()
    private val taxed = UUID.randomUUID()
    private val enemy = UUID.randomUUID()
    private val item = "test:item"

    private fun setup(): FakeClaims {
        Config.s = Settings()
        Market.data = Data()
        Ledger.attach(Files.createTempFile("kami_economy", ".wal"))
        return FakeClaims().apply {
            home += mapOf(buyer to "aurelia", neutral to "nordmark", ally to "verdania", province to "vale", taxed to "ostland", enemy to "karst")
            relations[("aurelia" to "verdania")] = Relation.ALLIED
            relations[("aurelia" to "vale")] = Relation.FAMILY
            relations[("aurelia" to "karst")] = Relation.EMBARGO
            tariffs[("aurelia" to "ostland")] = 15
            tariffs[("aurelia" to "karst")] = 50
            ClaimsApi.register(this)
        }
    }

    @AfterTest
    fun clear() = ClaimsApi.register(null)

    @Test
    fun rateResolutionMatrix() {
        setup()
        fun terms(seller: UUID) = Trade.terms(buyer.toString(), seller.toString())
        assertEquals(listOf(10), terms(neutral).rates)
        assertEquals(listOf(8), terms(ally).rates)
        assertEquals(listOf(8), terms(province).rates)
        assertEquals(listOf(10, 15), terms(taxed).rates)
        assertTrue(terms(enemy).blocked)
        assertEquals(0, terms(enemy).tariffPct)
        assertFalse(Trade.canTrade(buyer.toString(), enemy.toString()))
        assertTrue(Trade.canTrade(buyer.toString(), UUID.randomUUID().toString()))
        ClaimsApi.register(null)
        assertEquals(listOf(10), terms(taxed).rates)
        assertFalse(terms(enemy).blocked)
    }

    @Test
    fun tariffKeepsTheStepCleanAndGoesToTheBuyersCountry() {
        val claims = setup()
        assertEquals(20, CleanStep.step(7, listOf(10, 15)))
        Matching.insertSell(item, Order(1, taxed.toString(), 7, 100))
        val plan = Matching.plan(item, 30, buyer.toString())
        assertEquals(20, plan.filled)
        val fill = plan.fills.single()
        assertEquals(14, fill.tax)
        assertEquals(21, fill.tariff)
        assertEquals(161, plan.totalSpurs)
        val purse = Purse(1000).also { Ledger.wallet = it }
        assertTrue(Ledger.buy(buyer.toString(), item, 20) is BuyResult.Ok)
        assertEquals(839, purse.funds)
        assertEquals(126, purse.deposits[taxed])
        assertEquals(listOf(Triple("aurelia", 21L, TreasuryKind.TARIFF)), claims.credits)
    }

    @Test
    fun alliesPayTheLowerRateAndEmbargoBlocksMatching() {
        setup()
        Matching.insertSell(item, Order(1, enemy.toString(), 5, 10))
        Matching.insertSell(item, Order(2, ally.toString(), 25, 4))
        val plan = Matching.plan(item, 20, buyer.toString())
        assertEquals(4, plan.filled)
        assertEquals(8, plan.fills.single().tax)
        assertEquals(0, plan.fills.single().tariff)
        Market.book(item).sells.removeAll { it.id == 2L }
        assertEquals(0, Matching.plan(item, 10, buyer.toString()).filled)
        assertTrue(Ledger.buy(buyer.toString(), item, 10) is BuyResult.NothingAvailable)
    }

    @Test
    fun marketStockAlwaysPaysTheBaseRate() {
        setup()
        assertEquals(Config.s.taxPct, Trade.terms(buyer.toString(), "").taxPct)
    }

    @Test
    fun sellingIntoABidPaysTariffToTheBuyersCountry() {
        val claims = setup()
        Matching.insertBid(item, Order(1, buyer.toString(), 7, 100))
        val purse = Purse(0).also { Ledger.wallet = it }
        val result = Ledger.sellNow(taxed.toString(), item, 30)
        assertEquals(SellResult.Sold(20, 105), result)
        assertEquals(105L, purse.deposits[taxed])
        assertEquals(listOf(Triple("aurelia", 21L, TreasuryKind.TARIFF)), claims.credits)
    }

    @Test
    fun sellPlanFillsNeverLoseASpurForAnyRateCombination() {
        setup()
        Matching.insertBid(item, Order(1, ally.toString(), 6, 90))
        val allyPlan = Matching.sellPlan(buyer.toString(), item, 90)
        assertEquals(90, allyPlan.filled)
        assertEquals(allyPlan.gross, allyPlan.net + allyPlan.tax + allyPlan.tariff)

        Market.data = kami.economy.Data()
        Matching.insertBid(item, Order(2, buyer.toString(), 7, 90))
        val taxedPlan = Matching.sellPlan(taxed.toString(), item, 90)
        assertEquals(90, taxedPlan.filled)
        assertEquals(taxedPlan.gross, taxedPlan.net + taxedPlan.tax + taxedPlan.tariff)
    }

    @Test
    fun sellPlanFullyFillsAnOrderWithANonCleanRemainder() {
        setup()
        Matching.insertBid(item, Order(1, ally.toString(), 6, 90))
        val plan = Matching.sellPlan(buyer.toString(), item, 90)
        assertEquals(90, plan.filled)
        val fill = plan.fills.single()
        assertEquals(90, fill.qty)
        assertEquals(plan.gross, plan.net + plan.tax + plan.tariff)
    }
}
