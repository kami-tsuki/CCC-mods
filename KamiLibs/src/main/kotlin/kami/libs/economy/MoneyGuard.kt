package kami.libs.economy

import kami.libs.LibConfig
import kami.libs.LibSettings
import kami.libs.admin.AdminPerms
import kami.libs.admin.Audit
import kami.libs.admin.Page
import kami.libs.admin.Players
import kami.libs.chat.Chat
import kami.libs.chat.Tone
import kami.libs.db.Db
import kami.libs.db.Migration
import kami.libs.db.Rows
import kami.libs.db.SavePoint
import kami.libs.db.Session
import kami.libs.log.Log
import kami.libs.text.Phrase
import kami.libs.util.Time
import kami.libs.util.toUuidOrNull
import kami.libs.util.Time.DAY_MS
import kotlinx.serialization.Serializable
import net.minecraft.server.MinecraftServer
import java.util.UUID
import kotlin.math.abs

@Serializable
class UnexplainedAccount(val uuid: String, val name: String, val player: Boolean, val total: Long, val last: Long, val count: Long, val flagged: Boolean)

object MoneyGuard {
    private const val WINDOW_MS = 60_000L
    private const val COOLDOWN_MS = Time.HOUR_MS
    private val log = Log.of("library")

    private class Context(val reason: String, val actor: Party?, val actorName: String, val command: String)
    private class Window(val player: Boolean, var owner: UUID?) {
        var sum = 0L
        var count = 0
        var peak = 0L
    }

    private data class Total(val player: Boolean, var total: Long = 0, var last: Long = 0, var count: Long = 0, var alerted: Long = 0, var owner: UUID? = null) {
        fun flagged(now: Long) = alerted > 0 && alerted >= now - DAY_MS
    }

    internal class Alert(val account: UUID, val player: Boolean, val sum: Long, val count: Int, val owner: UUID?)

    private val stack = ThreadLocal.withInitial { ArrayList<Context>() }
    private val windows = HashMap<UUID, Window>()
    private val totals = HashMap<UUID, Total>()
    private val pending = ArrayList<Alert>()
    private val chatted = HashMap<UUID, Long>()
    private val rows = Rows<UUID>("libs_unexplained", listOf("account"), listOf("player", "total", "last", "count", "alerted"))
    private var opened = 0L

    internal val migration = Migration(
        "libs", 6, "unexplained", ddl = listOf(
            "CREATE TABLE libs_unexplained (account TEXT PRIMARY KEY, player INTEGER NOT NULL, total INTEGER NOT NULL, last INTEGER NOT NULL, count INTEGER NOT NULL, alerted INTEGER NOT NULL)"
        )
    )

    inline fun <T> explained(reason: String, block: () -> T): T {
        enter(reason)
        try {
            return block()
        } finally {
            exit()
        }
    }

    @JvmStatic
    fun enter(reason: String) {
        stack.get() += Context(reason, null, "", "")
    }

    @JvmStatic
    fun admin(actor: Party, actorName: String, reason: String, command: String) {
        stack.get() += Context(reason, actor, actorName, command)
    }

    @JvmStatic
    fun exit() {
        stack.get().removeLastOrNull()
    }

    @JvmStatic
    fun changed(account: UUID, player: Boolean, old: Long, new: Long, owner: UUID? = null) {
        val delta = new - old
        if (delta == 0L) return
        val ctx = stack.get().lastOrNull() ?: return record(account, player, owner.takeUnless { player }, delta)
        val actor = ctx.actor ?: return
        Journal.leg(PlayerParty(account), delta, Flow.ADMIN, actor, ctx.reason)
        Audit.record(actor, ctx.actorName, "money.admin_change", account.toString(), ctx.reason, mapOf("delta" to delta.toString(), "command" to ctx.command))
    }

    @Synchronized
    private fun record(account: UUID, player: Boolean, owner: UUID?, delta: Long) {
        val w = windows.getOrPut(account) { Window(player, owner) }
        if (owner != null) w.owner = owner
        w.sum += delta
        w.count++
        w.peak = maxOf(w.peak, abs(delta))
    }

    internal fun alarming(sum: Long, peak: Long, s: LibSettings) = abs(sum) >= s.unexplainedAlert || peak >= s.unexplainedSingle

    @Synchronized
    internal fun flush(now: Long = System.currentTimeMillis()): List<Alert> {
        if (windows.isEmpty()) return emptyList()
        val s = LibConfig.s
        val done = windows.toList()
        windows.clear()
        val alerts = ArrayList<Alert>()
        done.forEach { (id, w) ->
            val t = totals.getOrPut(id) { Total(w.player) }
            t.total += w.sum
            t.last = now
            t.count += w.count
            if (w.owner != null) t.owner = w.owner
            if (alarming(w.sum, w.peak, s)) {
                t.alerted = now
                alerts += Alert(id, w.player, w.sum, w.count, w.owner)
                log.warn("Unexplained balance change of {} spurs in {} account {} owned by {} ({} changes, largest {})", w.sum, if (w.player) "player" else "blaze banker", id, w.owner ?: id, w.count, w.peak)
            }
        }
        pending += alerts
        if (Db.isOpen) Db.write {
            done.filter { it.second.sum != 0L }.forEach { (id, w) -> journalLeg(now, PlayerParty(w.owner ?: id), w.sum, Flow.UNEXPLAINED, null, listOfNotNull("blaze banker $id".takeUnless { w.player }, "×${w.count}").joinToString(" "), "") }
        }
        return alerts
    }

    @Synchronized
    fun recent(now: Long = System.currentTimeMillis()): Boolean = totals.values.any { it.flagged(now) }

    fun accounts(page: Int, now: Long = System.currentTimeMillis(), name: (UUID) -> String): Page<UnexplainedAccount> {
        val all = synchronized(this) { totals.map { it.key to it.value.copy() } }.sortedByDescending { abs(it.second.total) }
        val p = Page.of(all, page)
        return Page(p.rows.map { (id, t) -> UnexplainedAccount(id.toString(), if (t.player) name(id) else t.owner?.let(name) ?: "", t.player, t.total, t.last, t.count, t.flagged(now)) }, p.total, p.page, p.size)
    }

    internal fun tick(server: MinecraftServer) {
        val now = System.currentTimeMillis()
        if (now - opened < WINDOW_MS) return
        opened = now
        flush(now)
        val alerts = synchronized(this) { pending.toList().also { pending.clear() } }.filter { now - (chatted[it.account] ?: 0L) >= COOLDOWN_MS }
        if (alerts.isEmpty()) return
        alerts.forEach { a ->
            chatted[a.account] = now
            val name = if (a.player) Players.profile(server, a.account).name else listOfNotNull("blaze banker", a.owner?.let { Players.profile(server, it).name }).joinToString(" ")
            val msg = Chat.of("library").say(Tone.BAD, Phrase.of("kami_libs.money.unexplained.chat", name, Phrase.money(a.sum), a.account.toString()))
            AdminPerms.notifyManagers(server, msg)
        }
    }

    @Synchronized
    internal fun load() {
        windows.clear()
        totals.clear()
        pending.clear()
        chatted.clear()
        Db.read {
            query("SELECT account, player, total, last, count, alerted FROM libs_unexplained") {
                totals[it.string("account").toUuidOrNull() ?: return@query] = Total(it.bool("player"), it.long("total"), it.long("last"), it.long("count"), it.long("alerted"))
            }
        }
        rows.prime(rows())
        SavePoint.register("libs.unexplained", ::changes)
    }

    @Synchronized
    private fun changes(): (Session.() -> Unit)? {
        flush()
        return rows.diff(rows())
    }

    private fun rows() = totals.mapValues { (id, t) -> listOf<Any?>(id.toString(), t.player, t.total, t.last, t.count, t.alerted) }
}
