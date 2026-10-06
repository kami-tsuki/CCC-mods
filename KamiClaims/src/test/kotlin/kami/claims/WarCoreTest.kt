package kami.claims

import kami.claims.research.LevelsConfig
import kami.claims.research.Research
import kami.claims.research.ResearchDefs
import kami.claims.research.ResearchSettings
import kami.claims.research.TreeFile
import kami.claims.research.Validator
import kami.claims.economy.Treasury
import kami.claims.war.WarDebt
import kami.claims.service.Fail
import kami.claims.service.Provinces
import kami.claims.service.Diplomacy
import kami.claims.war.Fame
import kami.claims.war.Reputation
import kami.claims.war.Joining
import kami.claims.war.ChunkRef
import kami.claims.war.WarKind
import kami.claims.war.War
import kami.claims.war.WarLocks
import kami.claims.war.WarPhase
import kami.claims.war.Negotiation
import kami.claims.war.Stakes
import kami.claims.war.WarOffer
import kami.claims.war.WarResult
import kami.claims.war.SafeChunks
import kami.claims.war.WarGuard
import kami.claims.war.Wars
import kami.libs.config.Configs
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class WarCoreTest {
    private val day get() = Config.s.dayMillis
    private val pot = Stakes(money = 100)

    @AfterTest
    fun resetResearch() { Research.defs = ResearchDefs.EMPTY }

    private fun safeResearch(c: Country, vararg adds: Int) {
        val nodes = adds.mapIndexed { i, n -> """{"id":"s$i","category":"c","unlocks":[{"type":"safe_chunks","add":$n}]}""" }
        val tree = Configs.json().decodeFromString(TreeFile.serializer(), """{"categories":[{"id":"c"}],"nodes":[${nodes.joinToString(",")}]}""")
        Research.defs = Validator.build(ResearchSettings(baseline = emptyList()), LevelsConfig(rules = emptyMap()), emptyMap(), mapOf("t" to tree)).defs
        adds.indices.forEach { c.research.done["t:s$it"] = 0 }
    }

    private fun country(name: String, age: Long = 10 * day): Country {
        val c = Country(name, created = now() - age, level = 100, treasury = 100_000)
        Realm.data.countries[c.id] = c
        c.members["${name}_p"] = Member(Rank.PRESIDENT, since = now() - 1000)
        return c
    }

    private fun setup(): Pair<Country, Country> {
        Realm.reset(Data())
        return country("aurelia") to country("verdania")
    }

    @Test
    fun declareFightAndPreparationAdvancesToWar() {
        val (a, b) = setup()
        val t = now()
        Wars.declare(a, b, stakes = pot)
        val war = Wars.of(a)!!
        assertEquals(WarPhase.DECLARATION, war.phase)
        assertTrue(war.phaseEnds >= t + Config.s.war.declarationDays * day)
        Wars.fight(b)
        assertEquals(WarPhase.PREPARATION, war.phase)
        assertTrue(war.phaseEnds >= t + Config.s.war.preparationDays * day)
        war.phaseEnds = now() - 1
        Wars.tick()
        assertEquals(WarPhase.WAR, war.phase)
        assertEquals(0L, war.phaseEnds)
    }

    @Test
    fun silenceGivesAttackerTheWinThenSettlementEndsWithCooldowns() {
        val (a, b) = setup()
        Wars.declare(a, b, stakes = pot)
        val war = Wars.of(a)!!
        war.phaseEnds = now() - 1
        Wars.tick()
        assertEquals(WarResult.ATTACKER, war.result)
        assertEquals(WarPhase.SETTLEMENT, war.phase)
        war.phaseEnds = now() - 1
        Wars.tick()
        assertEquals(WarPhase.ENDED, war.phase)
        assertTrue(a.warCooldownUntil > now())
        assertTrue(b.warCooldownUntil > now())
    }

    @Test
    fun withdrawCoolsOnlyTheDefender() {
        val (a, b) = setup()
        Wars.declare(a, b, stakes = pot)
        val war = Wars.of(a)!!
        Wars.withdraw(a)
        assertEquals(WarPhase.ENDED, war.phase)
        assertEquals(WarResult.WITHDRAWN, war.result)
        assertEquals(0L, a.warCooldownUntil)
        assertTrue(b.warCooldownUntil > now())
    }

    @Test
    fun noWithdrawAfterFightBack() {
        val (a, b) = setup()
        Wars.declare(a, b, stakes = pot)
        Wars.fight(b)
        assertFailsWith<Fail> { Wars.withdraw(a) }
    }

    @Test
    fun declarationRefusals() {
        val (a, b) = setup()
        assertFailsWith<Fail> { Wars.declare(a, a, stakes = pot) }
        val young = country("nordmark", age = day)
        assertFailsWith<Fail> { Wars.declare(a, young, stakes = pot) }
        assertFailsWith<Fail> { Wars.declare(young, a, stakes = pot) }
        Wars.declare(a, b, stakes = pot)
        Wars.withdraw(a)
        assertFailsWith<Fail> { Wars.declare(a, b, stakes = pot) }
        val c = country("ostland")
        Wars.declare(c, a, stakes = pot)
        assertFailsWith<Fail> { Wars.declare(c, b, stakes = pot) }
    }

    @Test
    fun busyCountryCannotStartSecondRegularWar() {
        val (a, b) = setup()
        val c = country("ostland")
        Wars.declare(a, b, stakes = pot)
        assertFailsWith<Fail> { Wars.declare(c, a, stakes = pot) }
        assertFailsWith<Fail> { Wars.declare(b, c, stakes = pot) }
    }

    @Test
    fun provinceMayRebelAgainstOverlordDuringRegularWar() {
        val (top, other) = setup()
        val vale = country("vale")
        Provinces.finalize(vale, top, TaxMode.FLAT, 0.0)
        Wars.declare(top, other, stakes = pot)
        Wars.declare(vale, top, WarKind.REBELLION)
        assertNotNull(Wars.all(vale).singleOrNull { it.kind == WarKind.REBELLION })
        assertEquals(2, Wars.all(top).size)
        assertFailsWith<Fail> { Wars.declare(other, vale, WarKind.REBELLION) }
    }

    @Test
    fun locksBlockClaimsDisbandAndCombatantLeaving() {
        val (a, b) = setup()
        WarLocks.claims(a)
        Wars.declare(a, b, stakes = pot)
        assertFailsWith<Fail> { WarLocks.claims(a) }
        assertFailsWith<Fail> { WarLocks.disband(b) }
        Realm.data.countries[a.id]!!.members["late"] = Member(Rank.CITIZEN, since = now() + 1000)
        assertFailsWith<Fail> { WarLocks.roster(a, "aurelia_p") }
        WarLocks.roster(a, "late")
    }

    @Test
    fun downtimeGapShiftsDeadlines() {
        val (a, b) = setup()
        Wars.declare(a, b, stakes = pot)
        val war = Wars.of(a)!!
        val before = war.phaseEnds
        val gap = 2 * 3_600_000L
        Realm.data.warClock = now() - gap
        Wars.tick()
        assertTrue(war.phaseEnds - before in gap - 5_000..gap + 5_000)
        assertEquals(WarPhase.DECLARATION, war.phase)
    }

    private fun finish(war: War) {
        war.phaseEnds = now() - 1
        Wars.tick()
    }

    @Test
    fun agreedStakesMoveToAttackerAndFeeIsKept() {
        val (a, b) = setup()
        b.reputation = Config.s.war.repStart
        Wars.declare(a, b, stakes = pot)
        val war = Wars.of(a)!!
        assertTrue(war.fee > 0)
        assertEquals(100L, war.escrow)
        assertEquals(100_000 - war.fee - 100, a.treasury)
        Wars.agree(b)
        finish(war)
        assertEquals(WarPhase.ENDED, war.phase)
        assertEquals(100_100 - war.fee, a.treasury)
        assertEquals(99_900L, b.treasury)
        assertTrue(b.warDebts.isEmpty())
        assertEquals(Config.s.war.repStart + Config.s.war.repPeaceful, b.reputation, 1e-9)
    }

    @Test
    fun settlementOfferCannotRaiseMoney() {
        val (a, b) = setup()
        Wars.declare(a, b, stakes = pot)
        val war = Wars.of(a)!!
        Wars.agree(b)
        assertEquals(WarPhase.SETTLEMENT, war.phase)
        assertFailsWith<Fail> { Negotiation.offer(a, war, WarOffer(a.id, stakes = Stakes(money = 200))) }
        assertEquals(null, war.offer)
    }

    @Test
    fun capitulationGivesEscrowToDefenderAndFeeIsLost() {
        val (a, b) = setup()
        Wars.declare(a, b, stakes = pot)
        val war = Wars.of(a)!!
        val fee = war.fee
        Wars.fight(b)
        finish(war)
        assertEquals(WarPhase.WAR, war.phase)
        Wars.capitulate(a)
        assertEquals(WarResult.DEFENDER, war.result)
        finish(war)
        assertEquals(WarPhase.ENDED, war.phase)
        assertEquals(100_100L, b.treasury)
        assertEquals(100_000 - fee - 100, a.treasury)
    }

    @Test
    fun acceptedCeasefireOfferPausesWar() {
        val (a, b) = setup()
        Wars.declare(a, b, stakes = pot)
        val war = Wars.of(a)!!
        Wars.fight(b)
        finish(war)
        Negotiation.offer(a, war, WarOffer(a.id, ceasefireDays = 2))
        Negotiation.accept(b, war)
        assertEquals(WarPhase.CEASEFIRE, war.phase)
        assertEquals(1, war.ceasefires)
    }

    @Test
    fun chunkStakeMovesToAttackerButCapitalStays() {
        val (a, b) = setup()
        val type = Config.s.defaultType
        Realm.add(Claim(a.id, dim, 0, 0, type, capital = true))
        Realm.add(Claim(b.id, dim, 1, 0, type))
        Realm.add(Claim(b.id, dim, 2, 0, type, capital = true))
        Wars.declare(a, b, stakes = Stakes(chunks = listOf(ChunkRef(dim, 1, 0))))
        val war = Wars.of(a)!!
        war.stakes = Stakes(chunks = listOf(ChunkRef(dim, 1, 0), ChunkRef(dim, 2, 0)))
        Wars.agree(b)
        finish(war)
        assertEquals(a.id, Realm.at(dim, 1, 0)?.country)
        assertEquals(b.id, Realm.at(dim, 2, 0)?.country)
        assertTrue(Realm.at(dim, 2, 0)!!.capital)
    }

    private val dim = "minecraft:overworld"

    private fun claim(c: Country, x: Int, capital: Boolean = false) =
        Realm.add(Claim(c.id, dim, x, 0, Config.s.defaultType, capital = capital))

    @Test
    fun safeChunkLimitFollowsLevelAndResearch() {
        val (a, _) = setup()
        listOf(9 to 0, 10 to 1, 500 to 1).forEach { (level, max) ->
            a.level = level
            assertEquals(max, SafeChunks.limit(a))
        }
        safeResearch(a, 1, 2)
        assertEquals(4, SafeChunks.limit(a))
        a.level = 9
        assertEquals(3, SafeChunks.limit(a))
    }

    @Test
    fun safeChunkRefusesCapitalForeignAndOverLimit() {
        val (a, b) = setup()
        claim(a, 0, capital = true)
        claim(a, 1)
        claim(a, 2)
        claim(a, 3)
        claim(b, 4)
        safeResearch(a, 1)
        assertFailsWith<Fail> { SafeChunks.set(a, Key(dim, 0, 0)) }
        assertFailsWith<Fail> { SafeChunks.set(a, Key(dim, 4, 0)) }
        assertFailsWith<Fail> { SafeChunks.set(a, Key(dim, 9, 0)) }
        SafeChunks.set(a, Key(dim, 1, 0))
        assertFailsWith<Fail> { SafeChunks.set(a, Key(dim, 1, 0)) }
        SafeChunks.set(a, Key(dim, 2, 0))
        assertFailsWith<Fail> { SafeChunks.set(a, Key(dim, 3, 0)) }
        assertEquals(2, SafeChunks.valid(a).size)
    }

    @Test
    fun safeChunksLockedInWarPhaseOnly() {
        val (a, b) = setup()
        claim(a, 1)
        claim(a, 2)
        Wars.declare(a, b, stakes = pot)
        val war = Wars.of(a)!!
        Wars.fight(b)
        assertEquals(WarPhase.PREPARATION, war.phase)
        SafeChunks.set(a, Key(dim, 1, 0))
        finish(war)
        assertEquals(WarPhase.WAR, war.phase)
        assertFailsWith<Fail> { SafeChunks.set(a, Key(dim, 2, 0)) }
        assertFailsWith<Fail> { SafeChunks.unset(a, Key(dim, 1, 0)) }
    }

    @Test
    fun warGuardSafeCoversCapitalAndLiveSafeChunksOnly() {
        val (a, b) = setup()
        claim(a, 0, capital = true)
        claim(a, 1)
        claim(a, 2)
        SafeChunks.set(a, Key(dim, 1, 0))
        assertTrue(WarGuard.safe(Realm.at(dim, 0, 0)))
        assertTrue(WarGuard.safe(Realm.at(dim, 1, 0)))
        assertFalse(WarGuard.safe(Realm.at(dim, 2, 0)))
        assertFalse(WarGuard.safe(null))
        Realm.unclaim(Realm.at(dim, 1, 0)!!, false)
        claim(b, 1)
        assertFalse(WarGuard.safe(Realm.at(dim, 1, 0)))
        assertTrue(SafeChunks.valid(a).isEmpty())
    }

    private fun lostWar(a: Country, b: Country, billA: Long, billB: Long): War {
        Wars.declare(a, b, stakes = pot)
        val war = Wars.of(a)!!
        Wars.fight(b)
        finish(war)
        war.sides.forEach { it.power = 100.0 }
        war.attacker.bill[a.id] = billA
        war.defender.bill[b.id] = billB
        Wars.capitulate(a)
        finish(war)
        return war
    }

    @Test
    fun billsNetToTheMainOfTheLargerBill() {
        val (a, b) = setup()
        val war = Wars.declare(a, b, stakes = pot).let { Wars.of(a)!! }
        Wars.fight(b)
        finish(war)
        war.sides.forEach { it.power = 100.0 }
        war.attacker.bill[a.id] = 500
        war.defender.bill[b.id] = 200
        val (ta, tb) = a.treasury to b.treasury
        Wars.capitulate(a)
        finish(war)
        assertEquals(WarPhase.ENDED, war.phase)
        assertEquals(ta - 300, a.treasury)
        assertEquals(tb + 300 + pot.money, b.treasury)
        assertTrue(a.warDebts.isEmpty())
    }

    @Test
    fun shortfallBecomesDebtAndBindsDebtorAsWarProvince() {
        val (a, b) = setup()
        val c = country("ostland")
        a.treasury = 1_000
        val war = lostWar(a, b, 1_000_000, 0)
        assertEquals(WarPhase.ENDED, war.phase)
        assertTrue((a.warDebts[b.id] ?: 0L) > 0)
        assertEquals(b.id, a.warOverlord)
        a.warCooldownUntil = 0
        assertFailsWith<Fail> { Wars.declare(a, c, stakes = pot) }
    }

    @Test
    fun depositIsGarnishedForTheCreditor() {
        val (a, b) = setup()
        a.treasury = 0
        a.warDebts[b.id] = 500
        val before = b.treasury
        Treasury.move(a, LedgerKind.DEPOSIT, 1000)
        assertEquals(before + 300, b.treasury)
        assertEquals(700L, a.treasury)
        assertEquals(200L, a.warDebts[b.id])
    }

    @Test
    fun payingOffTheDebtReleasesTheWarProvince() {
        val (a, b) = setup()
        val c = country("ostland")
        a.warDebts[b.id] = 500
        WarDebt.bind(a)
        assertEquals(b.id, a.warOverlord)
        WarDebt.pay(a, b, 500)
        assertTrue(a.warDebts.isEmpty())
        assertEquals(null, a.warOverlord)
        c.treasury = 0
        c.warDebts[b.id] = 100
        WarDebt.bind(c)
        assertEquals(b.id, c.warOverlord)
        Treasury.move(c, LedgerKind.DEPOSIT, 1000)
        assertTrue(c.warDebts.isEmpty())
        assertEquals(null, c.warOverlord)
    }

    @Test
    fun warProvinceKeepsOverlordAndSkipsFamilyCreditors() {
        val (a, b) = setup()
        val top = country("ostland")
        Provinces.finalize(a, top, TaxMode.FLAT, 0.0)
        a.warDebts[top.id] = 900
        a.warDebts[b.id] = 500
        WarDebt.bind(a)
        assertEquals(b.id, a.warOverlord)
        assertEquals(top.id, a.parent)
        WarDebt.pay(a, b, 500)
        assertEquals(null, a.warOverlord)
        assertEquals(top.id, a.parent)
        assertEquals(900L, a.warDebts[top.id])
    }

    @Test
    fun debtorCannotDisband() {
        val (a, b) = setup()
        WarLocks.disband(a)
        a.warDebts[b.id] = 100
        assertFailsWith<Fail> { WarLocks.disband(a) }
    }

    private fun ally(x: Country, y: Country) {
        x.alliances += y.id
        y.alliances += x.id
    }

    @Test
    fun allyFromDeclarationJoinsDefenderAndLateAllyIsRefused() {
        val (a, b) = setup()
        val x = country("ostland")
        val late = country("nordmark")
        ally(x, a)
        ally(x, b)
        Wars.declare(a, b, stakes = pot)
        val war = Wars.of(a)!!
        ally(late, b)
        Wars.fight(b)
        assertFailsWith<Fail> { Joining.join(late, war, b) }
        Joining.join(x, war, b)
        assertTrue(x.id in war.defender.joined)
        assertTrue(Diplomacy.embargoed(x, a))
        assertFalse(a.id in x.alliances)
        assertFalse(x.id in a.alliances)
    }

    @Test
    fun joiningIsRefusedInWarPhase() {
        val (a, b) = setup()
        val x = country("ostland")
        ally(x, b)
        Wars.declare(a, b, stakes = pot)
        val war = Wars.of(a)!!
        Wars.fight(b)
        finish(war)
        assertEquals(WarPhase.WAR, war.phase)
        assertFailsWith<Fail> { Joining.join(x, war, b) }
        assertTrue(war.defender.joined.isEmpty())
    }

    private fun rebelWar(): Triple<Country, Country, Country> {
        val (a, b) = setup()
        val vale = country("vale")
        Provinces.finalize(vale, b, TaxMode.FLAT, 0.0)
        Wars.declare(a, b, stakes = pot)
        val war = Wars.of(a)!!
        Wars.fight(b)
        Joining.join(vale, war, a)
        assertEquals(null, vale.parent)
        assertEquals(b.id, war.rebels[vale.id])
        finish(war)
        return Triple(a, b, vale)
    }

    @Test
    fun rebelProvinceStaysFreeWhenAttackerWins() {
        val (a, b, vale) = rebelWar()
        val war = Wars.of(a)!!
        Wars.capitulate(b)
        finish(war)
        assertEquals(WarPhase.ENDED, war.phase)
        assertEquals(null, vale.parent)
    }

    @Test
    fun rebelProvinceReturnsToOverlordWhenOverlordWins() {
        val (a, b, vale) = rebelWar()
        val war = Wars.of(a)!!
        Wars.capitulate(a)
        finish(war)
        assertEquals(WarPhase.ENDED, war.phase)
        assertEquals(b.id, vale.parent)
    }

    @Test
    fun noWithdrawAfterRebelJoined() {
        val (a, b) = setup()
        val vale = country("vale")
        Provinces.finalize(vale, b, TaxMode.FLAT, 0.0)
        Wars.declare(a, b, stakes = pot)
        val war = Wars.of(a)!!
        Joining.join(vale, war, a)
        assertFailsWith<Fail> { Wars.withdraw(a) }
    }

    @Test
    fun warEmbargoIsLiftedAtWarEnd() {
        val (a, b) = setup()
        val x = country("ostland")
        ally(x, b)
        a.tradePolicy[x.id] = TradePolicy(0, true)
        Wars.declare(a, b, stakes = pot)
        val war = Wars.of(a)!!
        Joining.join(x, war, b)
        assertTrue(Diplomacy.policy(x, a).embargo)
        assertFailsWith<Fail> { Diplomacy.setEmbargo(x, a, false) }
        Wars.withdraw(a)
        assertEquals(WarPhase.ENDED, war.phase)
        assertFalse(Diplomacy.policy(x, a).embargo)
        assertTrue(Diplomacy.policy(a, x).embargo)
    }

    @Test
    fun exhaustionFromDeathsAndBillIsCappedPerDay() {
        val (a, b) = setup()
        Wars.declare(a, b, stakes = pot)
        val war = Wars.of(a)!!
        Wars.fight(b)
        finish(war)
        war.warSince = now() - day
        war.defender.deaths[b.id] = 4
        war.attacker.bill[a.id] = 1_000
        Wars.tick()
        assertEquals(6.0, war.defender.exhaustion, 0.01)
        war.defender.deaths[b.id] = 100
        war.attacker.bill[a.id] = 10_000
        Wars.tick()
        assertEquals(13.0, war.defender.exhaustion, 0.01)
        assertEquals(5.0, war.attacker.exhaustion, 0.01)
    }

    @Test
    fun exhaustionEndsLongWarAsWhitePeace() {
        val (a, b) = setup()
        Wars.declare(a, b, stakes = pot)
        val war = Wars.of(a)!!
        Wars.fight(b)
        finish(war)
        war.warSince = now() - 21 * day
        Wars.tick()
        assertEquals(100.0, war.attacker.exhaustion)
        assertEquals(WarResult.WHITE, war.result)
        assertEquals(WarPhase.SETTLEMENT, war.phase)
    }

    @Test
    fun fameIsHiddenUntilEnoughWeightThenPulledToPriorWithExtremesHalved() {
        val (a, b) = setup()
        a.ratings["v1"] = Rating(8, now(), 20.0)
        assertEquals(null, Fame.score(a))
        a.ratings["v2"] = Rating(8, now(), 40.0)
        a.ratings["v3"] = Rating(8, now(), 20.0)
        assertEquals(27.0 / 8, Fame.score(a)!!, 1e-9)
        a.ratings["v4"] = Rating(10, now(), 20.0)
        assertEquals(29.5 / 8.5, Fame.score(a)!!, 1e-9)
        b.ratings["v4"] = Rating(6, now(), 20.0)
        assertEquals(32.0 / 9, Fame.score(a)!!, 1e-9)
        assertEquals(4, Fame.count(a))
        a.ratings["aurelia_p"] = Rating(1, now(), 40.0)
        assertEquals(32.0 / 9, Fame.score(a)!!, 1e-9)
    }

    @Test
    fun fameExtremeVoterNeedsEightyPercentExtremeRatings() {
        val (a, b) = setup()
        val (c, d, e) = listOf("c", "d", "e").map { country(it) }
        listOf("v1", "v2", "v3").forEach { a.ratings[it] = Rating(8, now(), 20.0) }
        listOf(a to 10, b to 1, c to 10, d to 6).forEach { (x, stars) -> x.ratings["x"] = Rating(stars, now(), 20.0) }
        assertEquals(32.0 / 9, Fame.score(a)!!, 1e-9)
        e.ratings["x"] = Rating(10, now(), 20.0)
        assertEquals(29.5 / 8.5, Fame.score(a)!!, 1e-9)
    }

    @Test
    fun reputationWeeklyCapsAndClamp() {
        val (a, b) = setup()
        val s = Config.s.war
        a.reputation = s.repStart
        repeat(3) { Reputation.change(a, "x", 50.0) }
        assertEquals(s.repStart + s.repWeeklyGainCap, a.reputation, 1e-9)
        Reputation.change(a, "y", 10.0)
        assertEquals(s.repStart + s.repWeeklyGainCap + 10, a.reputation, 1e-9)
        b.reputation = s.repStart
        repeat(5) { Reputation.change(b, "s$it", -100.0) }
        assertEquals(s.repStart - s.repWeeklyLossCap, b.reputation, 1e-9)
        b.reputation = 10.0
        b.repWeek.clear()
        Reputation.change(b, "s", -5.0)
        assertTrue(b.reputation >= 0.0)
        a.reputation = s.repMax - 5
        a.repWeek.clear()
        Reputation.change(a, "z", 50.0)
        assertEquals(s.repMax, a.reputation, 1e-9)
    }

    @Test
    fun warResultMovesReputationByEloKFactor() {
        val (a, b) = setup()
        val s = Config.s.war
        a.reputation = s.repStart
        b.reputation = s.repStart
        lostWar(a, b, 1_000_000, 1_000_000)
        assertEquals(s.repStart + s.repWin * 0.5, b.reputation, 0.01)
        assertTrue(a.reputation < s.repStart)
    }

    @Test
    fun tradeFactorFollowsReputationExtremes() {
        val (a, _) = setup()
        val fee = Config.s.war.repTradeFee
        a.reputation = Config.s.war.repMax
        assertEquals(1 - fee, Reputation.tradeFactor(a), 1e-9)
        a.reputation = Config.s.war.repStart
        assertEquals(1.0, Reputation.tradeFactor(a), 1e-9)
        a.reputation = 0.0
        assertEquals(1 + fee, Reputation.tradeFactor(a), 1e-9)
    }

    @Test
    fun minimumReputationBlocksMutualDiplomacyUntilRaised() {
        val (a, b) = setup()
        b.minReputation = 1200
        a.reputation = 1000.0
        assertFailsWith<Fail> { Diplomacy.mutual(a, b) }
        a.reputation = 1200.0
        Diplomacy.mutual(a, b)
        assertFailsWith<Fail> { Reputation.setMin(b, 5000) }
    }
}
