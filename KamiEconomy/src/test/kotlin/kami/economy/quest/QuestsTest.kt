package kami.economy.quest

import kami.economy.Config
import kami.economy.Data
import kami.economy.Market
import kami.economy.QuestSettings
import kami.economy.Settings
import kami.economy.StarterGood
import kami.economy.Traded
import kami.economy.economy.FillLinePlan
import kami.economy.economy.Fakes
import kami.economy.economy.Ledger
import kami.economy.economy.SellResult
import kami.economy.economy.Stocks
import kami.economy.economy.TestDb
import kami.economy.economy.Purse
import kami.economy.now
import kami.libs.claims.ClaimsApi
import kami.libs.economy.ServerBank
import java.util.UUID
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class QuestsTest {
    private val item = "test:stew"
    private val a = UUID.randomUUID()
    private val b = UUID.randomUUID()
    private val staffer = UUID.randomUUID()
    private val purse = Purse()
    private val granted = mutableListOf<List<Any>>()
    private lateinit var claims: Fakes.Claims

    @BeforeTest
    fun setUp() {
        Config.s = Settings(starterGoods = listOf(StarterGood(item, 100, lot = 1, supply = 0, demand = 0)), quests = QuestSettings(enabled = false))
        Market.data = Data()
        Stocks.index { emptyList() }
        TestDb.open()
        ServerBank.load()
        Ledger.wallet = purse
        claims = object : Fakes.Claims() {
            override fun boost(country: String, kind: String, pct: Int, ms: Long): Boolean = granted.add(listOf(country, kind, pct, ms))
        }
        ClaimsApi.register(claims)
        Quests.load()
        Quests.dishes = { listOf(Dish(item, 5, 3, emptyList(), emptyList(), 1.0, false, true)) }
        Quests.eligible = { true }
        Quests.stackOf = { 1 }
        Quests.staff = { it == staffer.toString() }
    }

    @AfterTest
    fun tearDown() {
        Quests.unload()
        ClaimsApi.register(null)
        TestDb.close()
        Config.s = Settings()
        Stocks.index { emptyList() }
    }

    private fun quest(amount: Int = 100, boosts: List<QuestBoost> = listOf(QuestBoost("xp", 10, 3))) =
        checkNotNull(Quests.spawn(QuestSpec(item, 2, amount, 6, lotPrice = 150, boosts = boosts), announce = false))

    @Test
    fun questPricesIgnoreACoursePushedPastTheReference() {
        assertEquals(115.0, QuestRoll.capped(150.0, 100.0, 0.15), 1e-9)
        assertEquals(90.0, QuestRoll.capped(90.0, 100.0, 0.15), 1e-9)
        assertEquals(150.0, QuestRoll.capped(150.0, null, 0.15), 1e-9)
    }

    @Test
    fun dishQuestsNeedAPlayerAtTheFoodLevel() {
        assertTrue(QuestRoll.dishesOpen(emptyList(), 10))
        assertTrue(QuestRoll.dishesOpen(listOf(3, null), 10))
        assertTrue(QuestRoll.dishesOpen(listOf(3, 10), 10))
        assertFalse(QuestRoll.dishesOpen(listOf(3, 9), 10))
    }

    @Test
    fun pityMakesHigherRaritiesCertainAfterTheMaximum() {
        val s = QuestSettings(uncommonShare = 0.0)
        val t = now()
        val clock = QuestClock(0, longArrayOf(t, t, t))
        assertEquals(0, QuestRoll.rarity(clock, t, s, Random(1)))
        clock.last[1] = t - 50 * QuestRoll.HOUR
        assertEquals(0, QuestRoll.rarity(clock, t, s, Random(1)))
        clock.last[1] = t - 300 * QuestRoll.HOUR
        assertEquals(3, QuestRoll.rarity(clock, t, s, Random(1)))
        clock.last[2] = t - 2000 * QuestRoll.HOUR
        assertEquals(4, QuestRoll.rarity(clock, t, s, Random(1)))
    }

    @Test
    fun weightPrefersOldAndUntradedItems() {
        val t = now()
        val dishes = listOf("test:fresh", "test:old", "test:never").map { Dish(it, 5, 3, emptyList(), emptyList(), 1.0, false, true) }
        val traded = mapOf("test:fresh" to Traded(500, 500, t - QuestRoll.DAY), "test:old" to Traded(500, 500, t - 40 * QuestRoll.DAY))
        val w = QuestRoll.factors(dishes, t, QuestSettings(), traded::get, { null }, { 0 }, { 0 }).associate { it.item to it.weight }
        assertTrue(w.getValue("test:fresh") < w.getValue("test:old"))
        assertTrue(w.getValue("test:old") < w.getValue("test:never"))
    }

    @Test
    fun minusShareAndOffsetStayInBounds() {
        val s = QuestSettings(minusShare = 0.0, minusMax = 0.45)
        assertEquals(0.05, QuestRoll.minusChance(s, 0, 10_000, 64.0))
        assertEquals(0.45, QuestRoll.minusChance(s.copy(minusShare = 0.4), 1_000_000, 0, 64.0))
        val rnd = Random(7)
        repeat(300) {
            val m = kotlin.math.abs(QuestRoll.offset(s, 10, 10, 64.0, rnd))
            assertTrue(m >= s.offsetMin - 1e-9 && m <= s.offsetMax + 1e-9)
        }
    }

    @Test
    fun questFillPaysGrossFromTheBankWithoutTaxOrCourseMove() {
        val before = now()
        val q = quest()
        val sold = Ledger.questDeliver(a.toString(), q.id, 40)
        assertEquals(SellResult.Sold(40, 6000), sold)
        assertEquals(6000L, purse.balance(a))
        assertEquals(-6000L, ServerBank.balance)
        assertEquals(40, q.filled)
        assertEquals(100.0, Stocks.course(item))
        assertNull(Market.peek(item))
        assertEquals(40L, Market.data.traded.getValue(item).sold)
        assertTrue(Market.data.traded.getValue(item).last in before..now())
        TestDb.restart()
        Quests.load()
        assertEquals(40, Quests.get(q.id)?.filled)
        assertEquals(1, Quests.deliveriesOf(q.id).size)
        assertTrue(Quests.delivered(Quests.deliveriesOf(q.id).single().key))
    }

    @Test
    fun winnerIsTheCountryAtDeliveryTimeAndGetsTheBoosts() {
        val q = quest()
        claims.home[a] = "aurelia"
        claims.home[b] = "ostland"
        Ledger.questDeliver(a.toString(), q.id, 60)
        claims.home[a] = "ostland"
        Ledger.questDeliver(a.toString(), q.id, 20)
        Ledger.questDeliver(b.toString(), q.id, 20)
        assertEquals(QuestState.DONE, q.state)
        assertEquals("aurelia", q.winner)
        assertEquals<List<List<Any>>>(listOf(listOf("aurelia", "xp", 10, 3 * QuestRoll.HOUR)), granted)
        assertEquals(listOf("aurelia" to 60, "ostland" to 40), Quests.leaders(q.id))
        assertTrue(Quests.bids(item).isEmpty())
    }

    @Test
    fun expiryGrantsNoBoost() {
        val q = quest()
        Ledger.questDeliver(a.toString(), q.id, 50)
        Quests.tick(now() + 7 * QuestRoll.HOUR)
        assertEquals(QuestState.EXPIRED, q.state)
        assertNull(q.winner)
        assertTrue(granted.isEmpty())
        assertEquals(SellResult.NoBuyers, Ledger.questDeliver(a.toString(), q.id, 10))
    }

    @Test
    fun staffNeitherSeesNorFillsQuests() {
        val q = quest()
        assertTrue(Quests.bids(item, staffer.toString()).isEmpty())
        assertEquals(SellResult.NoBuyers, Ledger.questDeliver(staffer.toString(), q.id, 10))
        assertEquals(0, q.filled)
        assertEquals(1, Quests.bids(item, a.toString()).size)
    }

    private fun plan(q: Quest, qty: Int) = FillLinePlan(Quests.orderId(q.id), "quest:${q.id}", qty, 150)

    @Test
    fun totalsTrackPlayersCountriesAndBreakTiesByEarliestDelivery() {
        val q = quest()
        claims.home[a] = "aurelia"
        claims.home[b] = "ostland"
        assertEquals(30, Quests.fill(item, a.toString(), plan(q, 30), "k1", 2000))
        assertEquals(30, Quests.fill(item, b.toString(), plan(q, 30), "k2", 1000))
        assertEquals(listOf("ostland" to 30, "aurelia" to 30), Quests.leaders(q.id))
        Quests.fill(item, a.toString(), plan(q, 10), "k3", 3000)
        assertEquals(listOf("aurelia" to 40, "ostland" to 30), Quests.leaders(q.id))
        assertEquals(40, Quests.mine(q.id, a.toString()))
        assertEquals(0, Quests.mine(q.id, UUID.randomUUID().toString()))
        assertEquals(30, Quests.countryQty(q.id, "ostland"))
        assertEquals(0, Quests.countryQty(q.id, "nowhere"))
        assertEquals(0, Quests.fill(item, a.toString(), plan(q, 10), "k3", 4000))
        assertEquals(40, Quests.mine(q.id, a.toString()))
    }

    @Test
    fun fillAfterCloseIsRecordedWithoutChangingTheQuest() {
        val q = quest()
        claims.home[a] = "aurelia"
        assertTrue(Quests.cancel(q.id))
        assertEquals(25, Quests.fill(item, a.toString(), plan(q, 25), "late1", now()))
        assertTrue(Quests.delivered("late1"))
        assertEquals(25, Quests.mine(q.id, a.toString()))
        assertEquals(25, Quests.countryQty(q.id, "aurelia"))
        assertEquals(0, q.filled)
    }

    @Test
    fun fillForPrunedQuestReturnsZeroAndPruneDropsTotals() {
        val q = quest()
        claims.home[a] = "aurelia"
        Quests.fill(item, a.toString(), plan(q, 10), "k1", now())
        Quests.cancel(q.id)
        Quests.tick(now() + 100 * QuestRoll.DAY)
        assertNull(Quests.get(q.id))
        assertEquals(0, Quests.mine(q.id, a.toString()))
        assertEquals(0, Quests.countryQty(q.id, "aurelia"))
        assertTrue(Quests.leaders(q.id).isEmpty())
        assertEquals(0, Quests.fill(item, a.toString(), plan(q, 10), "k2", now()))
        assertTrue(!Quests.delivered("k2") && !Quests.delivered("k1"))
    }

    @Test
    fun loadReplaysTotalsFromStoredDeliveries() {
        val q = quest()
        claims.home[a] = "aurelia"
        claims.home[b] = "ostland"
        Quests.fill(item, a.toString(), plan(q, 20), "k1", 1000)
        Quests.fill(item, b.toString(), plan(q, 20), "k2", 500)
        TestDb.restart()
        Quests.load()
        assertEquals(20, Quests.mine(q.id, a.toString()))
        assertEquals(20, Quests.countryQty(q.id, "ostland"))
        assertEquals(listOf("ostland" to 20, "aurelia" to 20), Quests.leaders(q.id))
    }
}
