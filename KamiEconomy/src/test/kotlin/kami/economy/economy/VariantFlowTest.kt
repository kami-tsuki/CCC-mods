package kami.economy.economy

import kami.economy.Config
import kami.economy.Data
import kami.economy.Market
import kami.economy.Order
import kami.economy.Settings
import kami.economy.StarterGood
import kami.economy.Stock
import kami.economy.Traded
import kami.economy.net.Quote
import kami.economy.net.QuoteReq
import kami.economy.net.Quotes
import kami.libs.economy.ServerBank
import java.lang.reflect.InvocationTargetException
import java.util.UUID
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val CORE = "test:ingot"
private const val BLOCK = "test:block"
private const val NUGGET = "test:nugget"

private fun ironVariants() = mapOf(BLOCK to Variant(CORE, 9, 1), NUGGET to Variant(CORE, 1, 9))

private fun prepare(till: Till, lot: Int? = 16, goods: List<StarterGood> = emptyList()) {
    Config.s = Settings(lots = lot?.let { mapOf(CORE to it) }.orEmpty(), starterGoods = goods)
    Market.data = Data()
    Variants.map = ironVariants()
    Stocks.index { emptyList() }
    TestDb.open()
    ServerBank.load()
    Ledger.wallet = till
}

private fun release() {
    Market::class.java.getDeclaredField("attached").apply { isAccessible = true }.set(Market, false)
    Variants.map = emptyMap()
    Config.s = Settings()
    Stocks.index { emptyList() }
    TestDb.close()
}

private fun delivered(who: UUID) = Market.data.pendingDelivery[who.toString()].orEmpty().filter { it.qty > 0 }.map { it.item to it.qty }

class VariantDeriveTest {
    private val none = { _: String -> false }

    @Test
    fun ironChainPicksIngotWhateverTheStarters() {
        val pairs = listOf("iron_nugget" to "iron_ingot", "iron_ingot" to "iron_block")
        val expected = mapOf("iron_nugget" to Variant("iron_ingot", 1, 9), "iron_block" to Variant("iron_ingot", 9, 1))
        assertEquals(expected, Variants.derive(pairs) { true })
        assertEquals(expected, Variants.derive(pairs, none))
        assertEquals(expected, Variants.derive(pairs) { it == "iron_ingot" })
        assertEquals(expected, Variants.derive(pairs.reversed()) { true })
    }

    @Test
    fun starterNuggetOrBlockStillLosesToTheMiddleWhenIngotIsAStarter() {
        val pairs = listOf("n" to "i", "i" to "b")
        assertEquals("i", Variants.derive(pairs) { it == "i" }.getValue("n").core)
        assertEquals(setOf("n", "b"), Variants.derive(pairs) { it == "i" || it == "b" }.keys)
    }

    @Test
    fun plainPairsKeepTheSmallItemAsCore() {
        assertEquals(mapOf("coal_block" to Variant("coal", 9, 1)), Variants.derive(listOf("coal" to "coal_block"), none))
        assertEquals(mapOf("coal_block" to Variant("coal", 9, 1)), Variants.derive(listOf("coal" to "coal_block")) { it == "coal" })
        assertEquals(mapOf("raw_iron_block" to Variant("raw_iron", 9, 1)), Variants.derive(listOf("raw_iron" to "raw_iron_block")) { true })
        assertEquals(mapOf("hay_block" to Variant("wheat", 9, 1)), Variants.derive(listOf("wheat" to "hay_block"), none))
    }

    @Test
    fun aStarterBigItemBecomesTheCoreOfAPlainPairUnlessItIsBlockLike() {
        assertEquals(mapOf("a" to Variant("b", 1, 9)), Variants.derive(listOf("a" to "b")) { it == "b" })
        assertEquals(mapOf("coal_block" to Variant("coal", 9, 1)), Variants.derive(listOf("coal" to "coal_block")) { it == "coal_block" })
        val xp = listOf("create:experience_nugget" to "create:experience_block")
        assertEquals(mapOf("create:experience_block" to Variant("create:experience_nugget", 9, 1)), Variants.derive(xp) { it == "create:experience_block" })
    }

    @Test
    fun nuggetIngotPairOnlyMakesTheIngotTheCore() {
        assertEquals(mapOf("gold_nugget" to Variant("gold_ingot", 1, 9)), Variants.derive(listOf("gold_nugget" to "gold_ingot"), none))
        assertEquals(mapOf("gold_nugget" to Variant("gold_ingot", 1, 9)), Variants.derive(listOf("gold_nugget" to "gold_ingot")) { true })
    }

    @Test
    fun fourChainUsesPowersOfNineAroundTheMiddle() {
        val chain = listOf("a" to "b", "b" to "c", "c" to "d")
        assertEquals(mapOf("a" to Variant("b", 1, 9), "c" to Variant("b", 9, 1), "d" to Variant("b", 81, 1)), Variants.derive(chain, none))
        assertEquals(mapOf("a" to Variant("c", 1, 81), "b" to Variant("c", 1, 9), "d" to Variant("c", 9, 1)), Variants.derive(chain) { it == "c" })
        val named = listOf("x_nugget" to "x_ingot", "x_ingot" to "x_block", "x_block" to "x_compressed")
        assertEquals("x_ingot", Variants.derive(named, none).getValue("x_block").core)
    }

    @Test
    fun variantsReachAtMostThreeStepsFromTheCore() {
        val chain = ('a'..'g').zipWithNext { x, y -> "$x" to "$y" }
        val map = Variants.derive(chain + ("g" to "h"), none)
        assertEquals(setOf("a", "b", "c", "e", "f", "g"), map.keys)
        assertTrue(map.values.all { it.core == "d" })
        assertEquals(Variant("d", 1, 729), map.getValue("a"))
        assertEquals(Variant("d", 729, 1), map.getValue("g"))
    }

    @Test
    fun ambiguousPairsAreDroppedAndTheRestSurvives() {
        assertEquals(emptyMap(), Variants.derive(listOf("a" to "b", "c" to "b"), none))
        assertEquals(emptyMap(), Variants.derive(listOf("a" to "b", "a" to "c"), none))
        assertEquals(mapOf("c" to Variant("b", 9, 1)), Variants.derive(listOf("a" to "b", "x" to "b", "b" to "c"), none))
    }

    @Test
    fun independentChainsCoexistAndDegenerateInputIsSafe() {
        val map = Variants.derive(listOf("iron" to "iron_block", "gold" to "gold_block"), none)
        assertEquals(mapOf("iron_block" to Variant("iron", 9, 1), "gold_block" to Variant("gold", 9, 1)), map)
        assertEquals(emptyMap(), Variants.derive(emptyList(), none))
        assertEquals(emptyMap(), Variants.derive(listOf("a" to "a"), none))
        assertEquals(emptyMap(), Variants.derive(listOf("a" to "b", "b" to "a"), none))
    }
}

class VariantMathTest {
    @AfterTest
    fun reset() {
        Variants.map = emptyMap()
    }

    @Test
    fun coreQtyIsExactOrNull() {
        Variants.map = ironVariants() + ("super" to Variant(CORE, 81, 1))
        assertEquals(18, Variants.coreQty(BLOCK, 2))
        assertEquals(0, Variants.coreQty(BLOCK, 0))
        assertEquals(2, Variants.coreQty(NUGGET, 18))
        assertEquals(0, Variants.coreQty(NUGGET, 0))
        assertNull(Variants.coreQty(NUGGET, 10))
        assertNull(Variants.coreQty(NUGGET, 1))
        assertEquals(10, Variants.coreQty(CORE, 10))
        assertEquals(5, Variants.coreQty("unknown", 5))
    }

    @Test
    fun coreQtyOverflowIsNull() {
        Variants.map = ironVariants() + ("super" to Variant(CORE, 81, 1))
        assertNull(Variants.coreQty(BLOCK, 300_000_000))
        assertNull(Variants.coreQty("super", 30_000_000))
        assertEquals(238_609_294, Variants.coreQty(NUGGET, 2_147_483_646))
        assertNull(Variants.coreQty(NUGGET, Int.MAX_VALUE))
        assertEquals(2_147_483_646, Variants.coreQty(BLOCK, 238_609_294))
    }

    @Test
    fun lotKeepsWholeCoreItems() {
        Variants.map = ironVariants()
        mapOf(16 to (16 to 144), 64 to (64 to 576), 18 to (2 to 162), 1 to (1 to 9), 9 to (1 to 81), 12 to (4 to 108)).forEach { (coreLot, lots) ->
            assertEquals(lots.first, Variants.lot(BLOCK, coreLot), "block lot for $coreLot")
            assertEquals(lots.second, Variants.lot(NUGGET, coreLot), "nugget lot for $coreLot")
            assertEquals(0, Variants.coreQty(BLOCK, Variants.lot(BLOCK, coreLot))!! % coreLot)
            assertEquals(0, Variants.coreQty(NUGGET, Variants.lot(NUGGET, coreLot))!! % coreLot)
        }
        assertEquals(16, Variants.lot(CORE, 16))
    }

    @Test
    fun scaleGivesTheLotPriceAndTheItemPriceRelationHolds() {
        Variants.map = ironVariants()
        assertEquals(900, Variants.scale(BLOCK, 100, 16))
        assertEquals(100, Variants.scale(NUGGET, 100, 16))
        assertEquals(100, Variants.scale(CORE, 100, 16))
        assertEquals(0, Variants.scale(BLOCK, 0, 16))
        assertEquals(300, Variants.scale(BLOCK, 100, 12))
        listOf(1L, 7L, 100L, 1234L, 99_999L).forEach { p ->
            val perCore = p.toDouble() / 16
            val block = Variants.scale(BLOCK, p, 16).toDouble() / Variants.lot(BLOCK, 16)
            val nugget = Variants.scale(NUGGET, p, 16).toDouble() / Variants.lot(NUGGET, 16)
            assertEquals(perCore * 9, block, 1.0 / Variants.lot(BLOCK, 16))
            assertEquals(perCore / 9, nugget, 1.0 / Variants.lot(NUGGET, 16))
        }
    }

    @Test
    fun scaleCoversLongerChains() {
        Variants.map = mapOf("super" to Variant(CORE, 81, 1), "tiny" to Variant(CORE, 1, 81))
        assertEquals(810, Variants.scale("super", 10, 16))
        assertEquals(10, Variants.scale("tiny", 10, 16))
        assertEquals(16 * 81, Variants.lot("tiny", 16))
    }

    @Test
    fun familyIsSortedSmallestFirstAndCoreLookupWorks() {
        Variants.map = ironVariants() + ("super" to Variant(CORE, 81, 1)) + ("other" to Variant("elsewhere", 9, 1))
        assertEquals(listOf(NUGGET, BLOCK, "super"), Variants.family(CORE).map { it.first })
        assertEquals(listOf("other"), Variants.family("elsewhere").map { it.first })
        assertTrue(Variants.family("none").isEmpty())
        assertEquals(CORE, Variants.core(BLOCK))
        assertEquals(CORE, Variants.core(CORE))
        assertNull(Variants.of(CORE))
    }

    @Test
    fun wholeItemSplitOfANonMultipleHeldAmount() {
        Variants.map = ironVariants()
        val held = 150
        val lot = Variants.lot(NUGGET, 16)
        val taken = held - held % lot
        assertEquals(144, taken)
        assertEquals(16, Variants.coreQty(NUGGET, taken))
        assertEquals(6, held - taken)
        assertNull(Variants.coreQty(NUGGET, held))
    }
}

class VariantLedgerTest {
    private val buyer = UUID.randomUUID()
    private val seller = UUID.randomUUID()
    private val till = Till()

    @AfterTest
    fun tearDown() = release()

    @Test
    fun buyingBlocksFromAPlayerOrderPaysNineIngotsPerLot() {
        prepare(till)
        Matching.insertSell(CORE, Order(1, seller.toString(), 15, 144, lot = 16))
        assertEquals(135, Variants.scale(BLOCK, 15, 16))
        assertEquals(BuyResult.Ok(144, 135), Ledger.buy(buyer.toString(), CORE, 144, variant = BLOCK))
        assertEquals(1_000_000 - 135, till.funds)
        assertEquals(listOf(BLOCK to 16), delivered(buyer))
        assertTrue(Matching.bookFor(CORE).sells.isEmpty())
        assertEquals(144L, Market.data.traded.getValue(CORE).bought)
        assertNull(Market.data.traded[BLOCK])
    }

    @Test
    fun buyingBlocksFromServerStockDrainsCoreStockAndCostsTheSameAsIngots() {
        val good = StarterGood(CORE, 20, 16, 32, 100, 100)
        prepare(till, goods = listOf(good))
        val plan = Matching.plan(CORE, 144)
        val result = Ledger.buy(buyer.toString(), CORE, 144, variant = BLOCK)
        assertEquals(BuyResult.Ok(144, plan.totalSpurs), result)
        assertEquals(1_000_000 - plan.totalSpurs, till.funds)
        assertEquals(listOf(BLOCK to 16), delivered(buyer))
        assertEquals(91, Stocks.stock(CORE)!!.supply)
        assertEquals(109, Stocks.stock(CORE)!!.demand)
        release()
        val direct = Till()
        prepare(direct, goods = listOf(good))
        assertEquals(BuyResult.Ok(144, plan.totalSpurs), Ledger.buy(buyer.toString(), CORE, 144))
        assertEquals(till.funds, direct.funds)
        assertEquals(listOf(CORE to 144), delivered(buyer))
    }

    @Test
    fun buyingNuggetsDeliversNineNuggetsPerIngot() {
        prepare(till)
        Matching.insertSell(CORE, Order(1, seller.toString(), 15, 16, lot = 16))
        assertEquals(15, Variants.scale(NUGGET, 15, 16))
        assertEquals(BuyResult.Ok(16, 15), Ledger.buy(buyer.toString(), CORE, 32, variant = NUGGET))
        assertEquals(listOf(NUGGET to 144), delivered(buyer))
        assertEquals(1_000_000 - 15, till.funds)
    }

    @Test
    fun partialNuggetFillLosesNoValue() {
        prepare(till, lot = 1)
        Matching.insertSell(CORE, Order(1, seller.toString(), 4, 5, lot = 1))
        val result = Ledger.buy(buyer.toString(), CORE, 12, variant = NUGGET) as BuyResult.Ok
        assertEquals(5, result.filled)
        assertEquals(20, result.spent)
        val items = delivered(buyer)
        val ingotValue = items.sumOf { (item, qty) -> if (item == NUGGET) qty / 9 else qty }
        assertEquals(result.filled, ingotValue)
        assertEquals(0, items.sumOf { (item, qty) -> if (item == NUGGET) qty % 9 else 0 })
        assertEquals(listOf(NUGGET to 45), items)
    }

    @Test
    fun partialBlockFillDeliversWholeBlocksPlusLeftoverIngots() {
        prepare(till, lot = 1)
        Matching.insertSell(CORE, Order(1, seller.toString(), 5, 20, lot = 1))
        assertEquals(BuyResult.Ok(20, 100), Ledger.buy(buyer.toString(), CORE, 27, variant = BLOCK))
        assertEquals(listOf(BLOCK to 2, CORE to 2), delivered(buyer))
        assertEquals(20, delivered(buyer).sumOf { (item, qty) -> if (item == BLOCK) qty * 9 else qty })
        assertEquals(1_000_000 - 100, till.funds)
    }

    @Test
    fun partialBlockFillBelowOneBlockDeliversOnlyIngots() {
        prepare(till, lot = 1)
        Matching.insertSell(CORE, Order(1, seller.toString(), 5, 8, lot = 1))
        assertEquals(BuyResult.Ok(8, 40), Ledger.buy(buyer.toString(), CORE, 18, variant = BLOCK))
        assertEquals(listOf(CORE to 8), delivered(buyer))
    }

    @Test
    fun aVariantOfAnotherCoreDeliversTheCoreItem() {
        prepare(till)
        Variants.map = ironVariants() + ("test:other_block" to Variant("test:other", 9, 1))
        Matching.insertSell(CORE, Order(1, seller.toString(), 15, 16, lot = 16))
        assertEquals(BuyResult.Ok(16, 15), Ledger.buy(buyer.toString(), CORE, 16, variant = "test:other_block"))
        assertEquals(listOf(CORE to 16), delivered(buyer))
    }

    @Test
    fun buyWithoutFundsOrStockLeavesNothingQueued() {
        till.funds = 10
        prepare(till)
        Matching.insertSell(CORE, Order(1, seller.toString(), 15, 144, lot = 16))
        assertEquals(BuyResult.InsufficientFunds, Ledger.buy(buyer.toString(), CORE, 144, variant = BLOCK))
        assertTrue(delivered(buyer).isEmpty())
        assertEquals(144, Matching.bookFor(CORE).sells.single().amount)
        till.funds = 1000
        assertEquals(BuyResult.NothingAvailable, Ledger.buy(buyer.toString(), "test:empty", 16, variant = BLOCK))
    }

    @Test
    fun sellingBlocksIntoServerDemandCountsCoreUnits() {
        prepare(till, goods = listOf(StarterGood(CORE, 20, 16, 32, 100, 100, sellCap = 20)))
        val plan = Matching.sellPlan(CORE, 144, seller.toString())
        assertEquals(144, plan.filled)
        val result = Ledger.sellNow(seller.toString(), CORE, 144)
        assertEquals(SellResult.Sold(144, plan.net), result)
        assertEquals(plan.net, till.deposits.sumOf { it.second.toLong() })
        assertEquals(91, Stocks.stock(CORE)!!.demand)
        assertEquals(109, Stocks.stock(CORE)!!.supply)
        assertEquals(11, Stocks.capLeft(seller.toString(), CORE))
        assertEquals(144L, Market.data.traded.getValue(CORE).sold)
        assertNull(Market.data.stocks[BLOCK])
    }

    @Test
    fun sellCapLimitsABlockSaleToTheWholeCoreLotsLeft() {
        prepare(till, goods = listOf(StarterGood(CORE, 20, 16, 32, 100, 100, sellCap = 5)))
        val result = Ledger.sellNow(seller.toString(), CORE, 144) as SellResult.Sold
        assertEquals(80, result.filled)
        assertEquals(0, Stocks.capLeft(seller.toString(), CORE))
        assertEquals(8, result.filled / 9)
        assertEquals(8, result.filled % 9)
        assertEquals(SellResult.Full, Ledger.sellNow(seller.toString(), CORE, 144))
    }

    @Test
    fun sellingIntoAPlayerBidDeliversCoreItemsToTheBidder() {
        prepare(till, lot = 16)
        Matching.insertBid(CORE, Order(2, buyer.toString(), 20, 144, lot = 16))
        val result = Ledger.sellNow(seller.toString(), CORE, 144) as SellResult.Sold
        assertEquals(144, result.filled)
        assertEquals(listOf(CORE to 144), delivered(buyer))
        assertTrue(Matching.bookFor(CORE).buys.isEmpty())
    }
}

class VariantRecoveryTest {
    private val buyer = UUID.randomUUID()
    private val seller = UUID.randomUUID()
    private val till = Till(50)

    @AfterTest
    fun tearDown() = release()

    private fun replay(intent: Intent) {
        prepare(till)
        TestDb.wal(intent)
        repeat(3) { TestDb.restart(); Ledger.recover() }
    }

    @Test
    fun blockBuyReplayDeliversBlocksOnce() {
        val fills = listOf(LedgerFill(1, seller.toString(), 144, 15, lot = 16))
        replay(Intent(8, IntentType.BUY, LedgerState.PENDING, actor = buyer.toString(), item = CORE, qty = 144, netSpurs = 135, fills = fills, preBalance = 185, variant = BLOCK, variantMul = 9, variantDiv = 1))
        assertEquals(listOf(BLOCK to 16), delivered(buyer))
        assertEquals(50, till.funds)
        assertEquals(1, till.deposits.size)
    }

    @Test
    fun blockBuyReplayWithLeftoverDeliversBlocksAndIngots() {
        val fills = listOf(LedgerFill(1, seller.toString(), 20, 5, lot = 1))
        replay(Intent(9, IntentType.BUY, LedgerState.PENDING, actor = buyer.toString(), item = CORE, qty = 20, netSpurs = 100, fills = fills, preBalance = 150, variant = BLOCK, variantMul = 9, variantDiv = 1))
        assertEquals(listOf(BLOCK to 2, CORE to 2), delivered(buyer))
    }

    @Test
    fun nuggetBuyReplayDeliversNuggets() {
        val fills = listOf(LedgerFill(1, seller.toString(), 16, 15, lot = 16))
        replay(Intent(10, IntentType.BUY, LedgerState.PENDING, actor = buyer.toString(), item = CORE, qty = 16, netSpurs = 15, fills = fills, preBalance = 65, variant = NUGGET, variantMul = 1, variantDiv = 9))
        assertEquals(listOf(NUGGET to 144), delivered(buyer))
    }

    @Test
    fun legacyIntentWithoutVariantDeliversTheCoreItem() {
        val fills = listOf(LedgerFill(1, seller.toString(), 16, 15, lot = 16))
        replay(Intent(11, IntentType.BUY, LedgerState.PENDING, actor = buyer.toString(), item = CORE, qty = 16, netSpurs = 15, fills = fills, preBalance = 65))
        assertEquals(listOf(CORE to 16), delivered(buyer))
    }

    @Test
    fun replayAfterTheDeliveryWasAlreadyQueuedDoesNotDuplicateIt() {
        prepare(till)
        val fills = listOf(LedgerFill(1, seller.toString(), 144, 15, lot = 16))
        val intent = Intent(12, IntentType.BUY, LedgerState.DEBITED, actor = buyer.toString(), item = CORE, qty = 144, netSpurs = 135, fills = fills, preBalance = 185, variant = BLOCK, variantMul = 9, variantDiv = 1)
        Market.queueDelivery(buyer.toString(), BLOCK, 16, "12:d")
        TestDb.wal(intent)
        repeat(2) { TestDb.restart(); Ledger.recover() }
        assertFalse(delivered(buyer).any { it.first == CORE })
        assertEquals(1, delivered(buyer).count { it.first == BLOCK })
    }
}

class VariantFoldTest {
    private val seller = UUID.randomUUID()
    private val buyer = UUID.randomUUID()
    private val till = Till()
    private val goods = listOf(
        StarterGood(CORE, 20, 16, 32, 100, 100),
        StarterGood(BLOCK, 180, 16, 32, 50, 50),
        StarterGood(NUGGET, 2, 16, 32, 30, -1)
    )

    @AfterTest
    fun tearDown() {
        release()
    }

    private fun fold() {
        val attached = Market::class.java.getDeclaredField("attached").apply { isAccessible = true }
        attached.set(Market, true)
        val m = Market::class.java.getDeclaredMethod("foldVariants").apply { isAccessible = true }
        try { m.invoke(Market) } catch (e: InvocationTargetException) { throw RuntimeException(e.cause?.message, null) }
    }

    private fun seed(coreStock: Stock = Stock(100, 100)) {
        prepare(till, goods = goods)
        Market.data.stocks[CORE] = coreStock
    }

    @Test
    fun stockDeltasFoldScaledByTheVariantFactor() {
        seed()
        Market.data.stocks[BLOCK] = Stock(60, 40)
        Market.data.stocks[NUGGET] = Stock(48, -1)
        fold()
        assertEquals(100 + 90 + 2, Market.data.stocks.getValue(CORE).supply)
        assertEquals(10, Market.data.stocks.getValue(CORE).demand)
        assertNull(Market.data.stocks[BLOCK])
        assertNull(Market.data.stocks[NUGGET])
    }

    @Test
    fun infiniteSidesAreSkipped() {
        seed(Stock(100, -1))
        Market.data.stocks[BLOCK] = Stock(60, 40)
        Market.data.stocks[NUGGET] = Stock(30, -1)
        fold()
        assertEquals(190, Market.data.stocks.getValue(CORE).supply)
        assertEquals(-1, Market.data.stocks.getValue(CORE).demand)
        seed(Stock(-1, 100))
        Market.data.stocks[BLOCK] = Stock(60, 50)
        fold()
        assertEquals(-1, Market.data.stocks.getValue(CORE).supply)
    }

    @Test
    fun foldNeverDrivesCoreStockBelowZeroAndRoundsDown() {
        seed(Stock(100, 20))
        Market.data.stocks[BLOCK] = Stock(50, 0)
        Market.data.stocks[NUGGET] = Stock(31, -1)
        fold()
        assertEquals(0, Market.data.stocks.getValue(CORE).demand)
        assertEquals(100, Market.data.stocks.getValue(CORE).supply)
    }

    @Test
    fun tradedTotalsMergeIntoTheCore() {
        seed()
        Market.data.traded[CORE] = Traded(5, 1)
        Market.data.traded[BLOCK] = Traded(10, 4)
        Market.data.traded[NUGGET] = Traded(90, 18)
        fold()
        assertEquals(105L, Market.data.traded.getValue(CORE).sold)
        assertEquals(39L, Market.data.traded.getValue(CORE).bought)
        assertNull(Market.data.traded[BLOCK])
        assertNull(Market.data.traded[NUGGET])
    }

    @Test
    fun openOrdersOnVariantBooksAreCancelledRefundedAndTheBookRemoved() {
        seed()
        Matching.insertSell(BLOCK, Order(1, seller.toString(), 150, 32, lot = 16))
        Matching.insertBid(BLOCK, Order(2, buyer.toString(), 10, 32, lot = 16))
        Matching.insertSell(CORE, Order(3, seller.toString(), 20, 16, lot = 16))
        fold()
        assertFalse(Market.data.books.containsKey(BLOCK))
        assertEquals(listOf(BLOCK to 32), delivered(seller))
        assertEquals(listOf(buyer to 20), till.deposits)
        assertEquals(16, Matching.bookFor(CORE).sells.single().amount)
    }

    @Test
    fun anEmptyVariantBookIsStillRemoved() {
        seed()
        Market.book(NUGGET)
        fold()
        assertFalse(Market.data.books.containsKey(NUGGET))
    }

    @Test
    fun secondRunChangesNothing() {
        seed()
        Matching.insertSell(BLOCK, Order(1, seller.toString(), 150, 32, lot = 16))
        Matching.insertBid(BLOCK, Order(2, buyer.toString(), 10, 32, lot = 16))
        Market.data.stocks[BLOCK] = Stock(60, 40)
        Market.data.stocks[NUGGET] = Stock(48, -1)
        Market.data.traded[BLOCK] = Traded(10, 4)
        fold()
        val stock = Market.data.stocks.getValue(CORE).let { it.supply to it.demand }
        val traded = Market.data.traded.getValue(CORE).let { it.sold to it.bought }
        val deliveries = delivered(seller)
        val deposits = till.deposits.toList()
        fold()
        assertEquals(stock, Market.data.stocks.getValue(CORE).let { it.supply to it.demand })
        assertEquals(traded, Market.data.traded.getValue(CORE).let { it.sold to it.bought })
        assertEquals(deliveries, delivered(seller))
        assertEquals(deposits, till.deposits)
    }

    @Test
    fun watchedVariantsBecomeWatchedCores() {
        seed()
        Watchlist.load()
        Watchlist.add("p", BLOCK)
        Watchlist.add("p", NUGGET)
        Watchlist.add("q", CORE)
        Watchlist.add("q", BLOCK)
        fold()
        assertEquals(setOf(CORE), Watchlist.of("p").toSet())
        assertEquals(setOf(CORE), Watchlist.of("q").toSet())
    }
}

class VariantQuoteTest {
    private val me = UUID.randomUUID().toString()
    private val seller = UUID.randomUUID().toString()
    private val till = Till()

    @AfterTest
    fun tearDown() = release()

    private fun quote(mode: String, item: String, qty: Int, price: Int = 0, funds: Long = 1_000_000): Quote? {
        return Quotes.of(me, funds, QuoteReq(mode, item, qty, price))
    }

    private val good = StarterGood(CORE, 20, 16, 32, 100, 100)

    @Test
    fun blockBuyQuoteIsInBlocksAndPricedPerBlockLot() {
        prepare(till, goods = listOf(good))
        val core = quote("buy", CORE, 144)!!
        val q = quote("buy", BLOCK, 16)!!
        assertEquals(BLOCK, q.item)
        assertEquals(16, q.qty)
        assertEquals(16, q.filled)
        assertEquals(0, q.leftover)
        assertEquals(0, q.returned)
        assertEquals(16, q.step)
        assertEquals(core.total, q.total)
        assertEquals(Variants.scale(BLOCK, core.avg.toLong(), 16).toInt(), q.avg)
        assertEquals(Variants.scale(BLOCK, core.worst.toLong(), 16).toInt(), q.worst)
        assertEquals(core.avg * 9, q.avg)
    }

    @Test
    fun nuggetBuyQuoteIsInNuggetsWithTheCoreLotPrice() {
        prepare(till, goods = listOf(good))
        val core = quote("buy", CORE, 16)!!
        val q = quote("buy", NUGGET, 144)!!
        assertEquals(144, q.filled)
        assertEquals(144, q.step)
        assertEquals(0, q.leftover)
        assertEquals(core.total, q.total)
        assertEquals(core.avg, q.avg)
    }

    @Test
    fun misalignedVariantQuantityIsRoundedDownToTheLotAndFlagged() {
        prepare(till, goods = listOf(good))
        val q = quote("buy", BLOCK, 20)!!
        assertEquals(20, q.qty)
        assertEquals(16, q.filled)
        assertEquals(4, q.returned)
        assertEquals("lot", q.reason)
        quote("buy", BLOCK, 10)!!.let { assertEquals(0, it.filled); assertEquals(10, it.returned); assertEquals("lot", it.reason) }
        quote("buy", NUGGET, 100)!!.let { assertEquals(0, it.filled); assertEquals(100, it.returned); assertEquals("lot", it.reason) }
    }

    @Test
    fun quoteReportsLeftoverCoreItemsOfAPartialFill() {
        prepare(till, lot = 1)
        Matching.insertSell(CORE, Order(1, seller, 5, 20, lot = 1))
        val q = quote("buy", BLOCK, 3)!!
        assertEquals(2, q.filled)
        assertEquals(2, q.leftover)
        assertEquals(1, q.returned)
        assertEquals(100, q.total)
    }

    @Test
    fun sellMarketQuoteForBlocksMatchesTheCoreNet() {
        prepare(till, goods = listOf(good))
        val core = quote("sell_market", CORE, 144)!!
        val q = quote("sell_market", BLOCK, 16)!!
        assertEquals(16, q.filled)
        assertEquals(core.total, q.total)
        assertEquals(Variants.scale(BLOCK, core.avg.toLong(), 16).toInt(), q.avg)
    }

    @Test
    fun coreQuoteIsUntouchedByVariantHandling() {
        prepare(till, goods = listOf(good))
        val q = quote("buy", CORE, 32)!!
        assertEquals(CORE, q.item)
        assertEquals(32, q.filled)
        assertEquals(0, q.leftover)
    }

}
