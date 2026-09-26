package kami.claims.client.store

import kami.claims.Rank
import kami.claims.client.ClientClaims
import kami.claims.net.Act
import kami.claims.net.Info
import kami.claims.net.PreviewLine
import kami.claims.net.Snap
import kami.libs.ui.style.Severity
import net.neoforged.neoforge.network.PacketDistributor

class Outcome(val name: String, val key: String, val ok: Boolean, val message: String, val reason: String, val x: Int?, val z: Int?)

object ClaimsStore {
    private class Pending(val name: String, val key: String, val since: Long)

    var snap: Snap? = null
        private set
    var revision = 0
        private set
    private var nextRid = 1
    private val pending = HashMap<Int, Pending>()
    private var watched: List<String> = emptyList()
    private val listeners = ArrayList<(Outcome) -> Unit>()
    private var stickyPreview: PreviewLine? = null
    var lastPreviewKey = ""
        private set
    private var lastPreviewAt = 0L

    val info: Info? get() = snap?.info
    val delegated get() = info?.delegated == true
    val rank: Rank get() = info?.let { runCatching { Rank.valueOf(it.rank.uppercase()) }.getOrNull() } ?: Rank.BANISHED

    fun onOutcome(listener: (Outcome) -> Unit) { listeners += listener }

    fun send(name: String, vararg args: String, key: String = name): Int {
        val rid = nextRid++
        pending[rid] = Pending(name, key, System.currentTimeMillis())
        PacketDistributor.sendToServer(Act(name, args.toList(), ClientClaims.viewingAs, rid))
        return rid
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

    fun watch(sections: List<String>) {
        if (sections == watched) return
        watched = sections
        quiet("watch", *sections.toTypedArray())
    }

    fun isPending(key: String) = pending.values.any { it.key == key }

    fun receive(next: Snap) {
        next.preview?.let {
            stickyPreview = it
            lastPreviewKey = it.key
        }
        snap = next
        revision++
        ClientClaims.viewingAs = next.info?.takeIf { it.delegated }?.name ?: ""
        val done = pending.remove(next.rid) ?: return
        if (next.msg.isEmpty()) return
        val outcome = Outcome(done.name, done.key, next.ok, next.msg, next.reason, if (next.hasTarget) next.targetX else null, if (next.hasTarget) next.targetZ else null)
        listeners.forEach { it(outcome) }
    }

    fun tick() {
        val now = System.currentTimeMillis()
        val expired = pending.filterValues { now - it.since > 6000 }
        expired.keys.forEach { pending.remove(it) }
        expired.values.forEach { p -> listeners.forEach { it(Outcome(p.name, p.key, false, "No answer from the server. Try again.", "TIMEOUT", null, null)) } }
    }

    fun reset() {
        pending.clear()
        watched = emptyList()
        stickyPreview = null
        lastPreviewKey = ""
        lastPreviewAt = 0L
    }

    fun severityOf(name: String) = Severity.of(name)
}
