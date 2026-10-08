package kami.libs.admin

import kami.libs.db.Db
import kami.libs.db.SavePoint
import kami.libs.economy.Flow
import kami.libs.economy.Journal
import kami.libs.economy.PlayerParty
import kami.libs.economy.ServerBank
import kami.libs.economy.ServerParty
import kami.libs.economy.Statement
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.minutes

class AdminCoreTest {
    private lateinit var root: Path
    private val file get() = root.resolve("world").resolve("kami").resolve("kami.db")

    private fun open() {
        Db.open(file)
        ServerBank.load()
    }

    @BeforeTest
    fun setUp() {
        root = Files.createTempDirectory("kami-admin")
        open()
    }

    @AfterTest
    fun tearDown() {
        Db.close()
        root.toFile().deleteRecursively()
    }

    @Test
    fun bankPostsPersistAndReload() {
        val p = PlayerParty(UUID.randomUUID())
        assertEquals(500L, ServerBank.collect(p, 500, Flow.TAX, ref = "r1", once = true))
        assertEquals(200L, ServerBank.pay(p, 200, Flow.REWARD, "prize"))
        assertEquals(0L, ServerBank.pay(p, -5, Flow.REWARD))
        Statement.record(p.uuid, -75, Flow.RENT, "Landlord")
        SavePoint.run()
        Db.close()
        open()
        assertEquals(300L, ServerBank.balance)
        assertEquals(0L, ServerBank.collect(p, 500, Flow.TAX, ref = "r1", once = true))
        assertEquals(listOf(Triple(Flow.REWARD, 0L, 200L), Triple(Flow.TAX, 500L, 0L)), ServerBank.days(0).map { Triple(it.flow, it.inflow, it.outflow) })
        val legs = Journal.query(Journal.Filter(party = ServerParty), 0).rows
        assertEquals(listOf(-200L, 500L), legs.map { it.delta })
        assertEquals(p.key, legs.last().counterparty)
        val player = Journal.query(Journal.Filter(party = p, text = "landlord"), 0).rows.single()
        assertEquals(-75L to null, player.delta to player.counterparty)
    }

    @Test
    fun bankOverflowRejected() {
        assertEquals(Long.MAX_VALUE, ServerBank.collect(ServerParty, Long.MAX_VALUE, Flow.ADMIN))
        assertEquals(0L, ServerBank.collect(ServerParty, 1, Flow.ADMIN))
        assertEquals(Long.MAX_VALUE, ServerBank.balance)
    }

    @Test
    fun durationsAndReasons() {
        assertEquals(30.minutes, Durations.parse("30m"))
        assertEquals(7.days, Durations.parse("1w"))
        assertNull(Durations.parse("perm"))
        assertFailsWith<AdminFailure> { Durations.parse("5x") }
        assertEquals("abc", Reasons.clean("  a\u0007bc  "))
        assertNull(Reasons.clean("ab"))
        assertNull(Reasons.clean("x".repeat(257)))
        assertEquals("abc", Reasons.clean("\u00a7a\u200bbc"))
    }

    @Test
    fun auditInsertAndPage() {
        val actor = PlayerParty(UUID.randomUUID())
        listOf("players.kick", "players.ban", "market.cancel").forEach { Audit.record(actor, "Kami", it, "t", "because", mapOf("0" to it), it != "players.ban") }
        Db.flush()
        val first = Audit.query(Audit.Filter(action = "players."), 0, 1)
        assertEquals(2L, first.total)
        assertEquals(listOf("players.ban"), first.rows.map { it.action })
        assertEquals(listOf("players.kick"), Audit.query(Audit.Filter(action = "players."), 1, 1).rows.map { it.action })
        assertEquals(mapOf("0" to "market.cancel"), Audit.query(Audit.Filter(actor = "kami", ok = true), 0, 50).rows.first().args)
        Audit.record(actor, "Kami", "players.ban.ip", "t", "because", mapOf("ips" to "1.2.3.4") + (0..9).associate { "$it" to "y".repeat(300) })
        Db.flush()
        val view = Audit.query(Audit.Filter(action = "players.ban.ip"), 0).rows.single().args
        assertEquals(8, view.size)
        assertEquals("***", view["ips"])
        assertEquals(256, view.getValue("0").length)
        assertEquals("1.2.3.4", Audit.query(Audit.Filter(action = "players.ban.ip"), 0, level = AdminPerms.Level.MANAGE).rows.single().args["ips"])
    }

    @Test
    fun argsAndPaging() {
        val a = listOf(" x ", "", "12", "true", "-3")
        assertEquals("x", a.text(0))
        assertNull(a.text(1))
        assertEquals(12L, a.long(2))
        assertEquals(true, a.flag(3))
        assertEquals(0, a.page(4))
        val p = Page.of((1..120).toList(), 2)
        assertEquals(listOf(101, 120), listOf(p.rows.first(), p.rows.last()))
        assertEquals(120L to 50, p.total to p.size)
        assertEquals(emptyList<Int>(), Page.of((1..3).toList(), Int.MAX_VALUE).rows)
    }

    @Test
    fun subjectIsHonouredOnlyForViewersAndBlocksMutations() {
        val self = UUID.randomUUID()
        val other = UUID.randomUUID()
        assertEquals(other, Subject.resolve(self, AdminPerms.Level.VIEW, other.toString()))
        assertEquals(self, Subject.resolve(self, AdminPerms.Level.NONE, other.toString()))
        assertEquals(self, Subject.resolve(self, AdminPerms.Level.MANAGE, ""))
        Subject.requireSelf(self, self.toString())
        Subject.requireSelf(self, null)
        assertFailsWith<AdminFailure> { Subject.requireSelf(self, other.toString()) }
    }
}
