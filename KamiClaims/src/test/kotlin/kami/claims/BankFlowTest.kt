package kami.claims

import kami.claims.gov.PRESIDENT
import kami.claims.research.*
import kami.claims.service.Service
import kami.claims.war.Stakes
import kami.claims.war.Wars
import kami.libs.config.Configs
import kami.libs.economy.ServerBank
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BankFlowTest : WithDb() {
    private val dim = "minecraft:overworld"

    private fun total() = Realm.data.countries.values.sumOf { it.treasury } + ServerBank.balance + Realm.data.wars.sumOf { it.escrow }

    private fun country(name: String, treasury: Long = 10_000): Country {
        val c = Country(name, created = now() - 10 * Config.s.dayMillis, level = 100, treasury = treasury)
        Realm.data.countries[c.id] = c
        c.mixed()
        Realm.join(c, "${name}_p", PRESIDENT)
        return c
    }

    private fun conserved(block: () -> Unit) {
        val before = total()
        val bank = ServerBank.balance
        block()
        assertEquals(before, total())
        assertTrue(ServerBank.balance != bank)
    }

    @BeforeTest
    fun setup() {
        Realm.reset(Data())
        Levels.day = { 1 }
        Levels.announce = {}
    }

    @AfterTest
    fun restore() {
        Levels.day = ::today
        Research.defs = ResearchDefs.EMPTY
    }

    private fun install() {
        val nodes = listOf(
            """{"id":"a","category":"c","time":"10m","cost":100}""",
            """{"id":"loan_small","category":"c","unlocks":[{"type":"loan","id":"small","amount":1000,"interestPct":30,"termDays":7},{"type":"loan_slots","add":1}]}"""
        )
        val tree = Configs.json().decodeFromString(TreeFile.serializer(), """{"categories":[{"id":"c"}],"nodes":[${nodes.joinToString(",")}]}""")
        val levels = LevelsConfig(rules = emptyMap(), capacities = mapOf(Capacity.TREASURY to 100_000, Capacity.RESEARCH_SLOTS to 1, Capacity.QUEUE_SLOTS to 2))
        Research.defs = Validator.build(ResearchSettings(baseline = emptyList()), levels, emptyMap(), mapOf("t" to tree)).defs
    }

    @Test
    fun claimPurchase() {
        val c = country("alpha")
        (0..Realm.freeAllowed(c)).forEach { Realm.add(Claim(c.id, dim, it, 0, "civic", at = it.toLong(), capital = it == 0)) }
        Realm.refreshFree(c)
        conserved { Service.addClaim(c, Key(dim, 100, 0), "civic") }
    }

    @Test
    fun researchAndLoan() {
        install()
        val c = country("alpha")
        Queue.enqueue(c, "t:a", null)
        conserved { Queue.start(c, "t:a", null) }
        c.research.done["t:loan_small"] = 0
        conserved { Loans.take(c, "small", "alpha_p") }
        conserved { Loans.repay(c, "small", "alpha_p") }
    }

    @Test
    fun warFee() {
        val a = country("aurelia", 100_000)
        val b = country("verdania", 100_000)
        conserved { Wars.declare(a, b, stakes = Stakes(money = 100)) }
    }

    @Test
    fun disband() {
        val c = country("alpha")
        val debtor = country("beta", -500)
        conserved { Realm.disband(c) }
        conserved { Realm.disband(debtor) }
        assertEquals(emptySet(), Realm.data.countries.keys)
    }
}
