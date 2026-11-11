package kami.claims.client.store

import kami.claims.client.ClientClaims
import kami.claims.gov.Perm
import kami.claims.net.Act
import kami.claims.net.DayLine
import kami.claims.net.Info
import kami.claims.net.LedgerLine
import kami.claims.net.Line
import kami.claims.net.PlayerLine
import kami.claims.net.PreviewLine
import kami.claims.net.RankLine
import kami.claims.net.Snap
import kami.claims.net.WarHud
import kami.libs.text.Phrase
import kami.libs.ui.app.Personal
import kami.libs.ui.text.tr
import kami.libs.ui.text.trJson
import net.neoforged.neoforge.network.PacketDistributor

class Outcome(
    val name: String, val key: String, val ok: Boolean, val message: String, val reason: String, val x: Int?, val z: Int?,
    val args: List<String> = emptyList(), val confirm: List<String> = emptyList(), val viewingAs: String = ""
)

object ClaimsStore {
    private class Pending(val name: String, val key: String, val since: Long, val args: List<String>, val viewingAs: String)
    private class Queued(val name: String, val args: Array<out String>, val key: String, val viewingAs: String)
    private class Hold(val key: String, val open: () -> Boolean)

    var snap: Snap? = null
        private set
    var revision = 0
        private set
    var receivedAt = 0L
        private set
    private var nextRid = 1
    private val pending = HashMap<Int, Pending>()
    private val queue = ArrayDeque<Queued>()
    private var gap = 0
    private var watched: List<String> = emptyList()
    private val listeners = ArrayList<(Outcome) -> Unit>()
    private var stickyPreview: PreviewLine? = null
    var lastPreviewKey = ""
        private set
    private var lastPreviewAt = 0L
    private var subject = ""
    private var watchRid = 0
    private var confirmed = emptySet<String>()
    private val carriers = HashMap<String, Snap>()
    private val keyHolds = ArrayList<Hold>()

    val info: Info? get() = snap?.info
    val delegated get() = info?.delegated == true
    val myRank: RankLine? get() = info?.let { i -> i.ranks.firstOrNull { it.id == i.rank } }
    fun holds(perm: Perm) = info?.myPerms?.contains(perm.name) == true
    fun outranks(rankId: String): Boolean {
        val mine = myRank?.position ?: return false
        val theirs = info?.ranks?.firstOrNull { it.id == rankId }?.position ?: return false
        return mine < theirs
    }
    val warHud: List<WarHud> get() = snap?.war.orEmpty()
    val countries: List<Line> get() = cached(WORLD)?.countries.orEmpty()
    val players: List<PlayerLine> get() = cached(WORLD)?.players.orEmpty()
    val ledger: List<LedgerLine>? get() = cached(LEDGER)?.ledger
    val history: List<DayLine>? get() = cached(HISTORY)?.history

    fun loaded(section: String) = cached(section) != null
    fun stale(section: String) = cached(section).let { it != null && it !== snap }

    private fun cached(section: String): Snap? = carriers[section]?.takeIf { section == WORLD || it.info?.name == info?.name }

    fun onOutcome(listener: (Outcome) -> Unit): () -> Unit {
        listeners += listener
        return { listeners -= listener }
    }

    private fun emit(outcome: Outcome) = listeners.toList().forEach { it(outcome) }

    fun send(name: String, vararg args: String, key: String = name) = sendAs(ClientClaims.viewingAs, name, args, key)

    fun sendAs(viewingAs: String, name: String, args: Array<out String>, key: String): Int {
        val rid = nextRid++
        pending[rid] = Pending(name, key, System.currentTimeMillis(), args.toList(), viewingAs)
        gap = SEND_GAP_TICKS
        PacketDistributor.sendToServer(Act(name, args.toList(), viewingAs, rid))
        return rid
    }

    fun enqueue(name: String, vararg args: String, key: String = name) {
        queue += Queued(name, args, key, ClientClaims.viewingAs)
    }

    fun quiet(name: String, vararg args: String) = PacketDistributor.sendToServer(Act(name, args.toList(), ClientClaims.viewingAs, 0))

    fun preview(kind: String, vararg args: String) {
        val key = "$kind|${args.joinToString("|")}" 
        val now = System.currentTimeMillis()
        if (key == lastPreviewKey && now - lastPreviewAt < 350L) return
        lastPreviewKey = key
        lastPreviewAt = now
        quiet("preview_$kind", *args)
    }

    fun previewLine() = stickyPreview

    fun clearPreview() {
        lastPreviewKey = ""
        lastPreviewAt = 0L
        stickyPreview = null
    }

    fun watch(sections: List<String>, force: Boolean = false) {
        if (!force && sections == watched) return
        watched = sections
        confirmed = emptySet()
        watchRid = nextRid++
        PacketDistributor.sendToServer(Act("watch", sections, ClientClaims.viewingAs, watchRid))
    }

    private fun carried(next: Snap): Set<String> {
        if (next.rid == watchRid || watchRid in next.extraRids) confirmed = buildSet {
            if (WORLD in watched) add(WORLD)
            if (LEDGER in watched) add(LEDGER)
            if (HISTORY in watched || "dashboard" in watched) add(HISTORY)
        }
        return if (next.info == null) confirmed + WORLD else confirmed
    }

    fun subject(id: String) {
        if (id == subject) return
        subject = id
        quiet("personal", id)
    }

    fun hold(key: String, open: () -> Boolean) { keyHolds += Hold(key, open) }

    fun isPending(key: String) = pending.values.any { it.key == key } || queue.any { it.key == key } || keyHolds.any { it.key == key && it.open() }

    fun receive(next: Snap) {
        next.preview?.let {
            stickyPreview = it
            lastPreviewKey = it.key
        }
        snap = next
        receivedAt = System.currentTimeMillis()
        carried(next).forEach { carriers[it] = next }
        revision++
        ClientClaims.viewingAs = next.info?.takeIf { it.delegated }?.name ?: ""
        next.extraRids.forEach { pending.remove(it) }
        val done = pending.remove(next.rid) ?: return
        if (next.msg.isEmpty()) return
        val outcome = Outcome(done.name, done.key, next.ok, trJson(next.msg), next.reason, if (next.hasTarget) next.targetX else null, if (next.hasTarget) next.targetZ else null, done.args, confirmKeys(next), done.viewingAs)
        emit(outcome)
    }

    private fun confirmKeys(next: Snap): List<String> =
        if (next.ok) emptyList() else Phrase.parse(next.msg)?.takeIf { it.key == CONFIRM_REQUIRED }?.args?.firstOrNull()?.text?.split(',').orEmpty()

    fun tick() {
        keyHolds.removeAll { !it.open() }
        if (subject.isNotEmpty() && !Personal.viewing) subject("")
        if (gap > 0) gap-- else queue.removeFirstOrNull()?.let { sendAs(it.viewingAs, it.name, it.args, it.key) }
        if (pending.isEmpty()) return
        val now = System.currentTimeMillis()
        val expired = pending.filterValues { now - it.since > 6000 }
        expired.keys.forEach { pending.remove(it) }
        expired.values.forEach { p -> emit(Outcome(p.name, p.key, false, tr("kami_libs.admin.timeout"), "TIMEOUT", null, null)) }
    }

    fun clear() {
        snap = null
        subject = ""
        carriers.clear()
        queue.clear()
        reset()
    }

    fun reset() {
        pending.clear()
        keyHolds.clear()
        watched = emptyList()
        confirmed = emptySet()
        watchRid = 0
        stickyPreview = null
        lastPreviewKey = ""
        lastPreviewAt = 0L
    }

    private const val SEND_GAP_TICKS = 5
    private const val CONFIRM_REQUIRED = "kami_claims.error.confirm_required"
    const val WORLD = "world"
    const val LEDGER = "ledger"
    const val HISTORY = "history"
}

fun Info.restricted(perm: Perm) = !delegated && perm.name !in myPerms
