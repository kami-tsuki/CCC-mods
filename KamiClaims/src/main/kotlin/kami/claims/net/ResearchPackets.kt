package kami.claims.net

import kami.claims.KamiClaims
import kami.claims.research.Capacity
import kami.claims.research.Limits.MAX_CATEGORIES
import kami.claims.research.Limits.MAX_ID
import kami.claims.research.Limits.MAX_LEVELS
import kami.claims.research.Limits.MAX_NODES
import kami.claims.research.Limits.MAX_TEXT
import kami.claims.research.Limits.MAX_TREES
import kami.claims.research.NodeState
import kami.claims.research.Scope
import kami.libs.net.Blob
import kami.libs.net.DEFLATE_ABOVE
import kami.libs.net.WireReader
import kami.libs.net.WireWriter
import kami.libs.net.Packets
import kami.libs.text.Phrase
import net.minecraft.network.protocol.common.custom.CustomPacketPayload

private val net = Packets.forMod(KamiClaims.ID)

private const val MAX_BYTES = 8 * 1024 * 1024
private const val MAX_LINKS = 64
private const val MAX_UNLOCKS = 4096

class CategoryView(val id: String, val title: String, val icon: String, val order: Int)

class TaskView(val kind: String, val subject: String, val target: Long)

class UnlockView(val kind: String, val id: String, val add: Int = 0, val amount: Long = 0)

class LevelView(val level: Int, val xp: Long, val rewards: List<UnlockView>, val requires: List<String> = emptyList())

class NodeView(
    val tree: String, val id: String, val category: String, val title: String, val description: String, val icon: String,
    val level: Int, val cost: Long, val timeMs: Long, val xp: Long, val conditions: List<String>, val requires: List<Int>,
    val tasks: List<TaskView>, val unlocks: List<UnlockView>, val x: Int?, val y: Int?, val anyRequires: List<Int> = emptyList(),
    val hiddenRequires: List<Int> = emptyList()
) {
    val key get() = "$tree:$id"
    fun label() = Phrase.or("kami_claims.research.node.$tree.$id", title)
    fun summary() = Phrase.or("kami_claims.research.node.$tree.$id.desc", description)
    fun conditionTexts() = conditions.mapNotNull { Phrase.parse(it) }
}

class TreeView(val id: String, val title: String, val scope: Scope, val categories: List<CategoryView>, val nodes: List<NodeView>) {
    fun label() = Phrase.or("kami_claims.research.tree.$id", title)
}

class DefsView(val trees: List<TreeView>, val extras: Map<String, ByteArray> = emptyMap(), val levels: List<LevelView> = emptyList()) {
    companion object {
        val EMPTY = DefsView(emptyList())
    }
}

class QueueView(val node: String, val state: NodeState, val remainingMs: Long, val paid: Boolean, val tasks: List<Long>)

class BuffView(
    val key: String, val effect: String, val amplifier: Int, val mode: String, val cooldownSeconds: Int,
    val cost: Int, val tax: Int, val enabled: Boolean
)

class BuffsView(val unlocked: Boolean, val points: Int, val used: Int, val surchargePerBorderChunk: Int, val borderChunks: Int, val list: List<BuffView>) {
    companion object {
        val NONE = BuffsView(false, 0, 0, 0, 0, emptyList())
    }
}

class ActiveLoanView(val id: String, val principal: Long, val total: Long, val paid: Long, val perDay: Long, val daysLeft: Int, val overdue: Long)

class LoanOfferView(
    val id: String, val amount: Long, val interestPct: Int, val termDays: Int, val total: Long, val perDay: Long,
    val unlocked: Boolean, val level: Int, val cooldownDays: Int
)

class LoansView(val unlocked: Boolean, val slots: Int, val active: List<ActiveLoanView>, val offers: List<LoanOfferView>, val inDefault: Boolean) {
    companion object {
        val NONE = LoansView(false, 0, emptyList(), emptyList(), false)
    }
}

class StateView(
    val country: String, val level: Int, val xp: Long, val xpFloor: Long, val xpCeiling: Long, val treasury: Long, val canManage: Boolean,
    val done: List<String>, val queue: List<QueueView>, val available: List<String>, val used: List<Int>, val max: List<Int>,
    val trees: List<String> = emptyList(), val met: Map<String, List<Boolean>> = emptyMap(), val levelMet: List<List<Boolean>> = emptyList(),
    val tokens: Map<String, Int> = emptyMap(), val tokenCosts: Map<String, Long> = emptyMap(), val counters: Map<String, Long> = emptyMap(),
    val buffs: BuffsView = BuffsView.NONE,
    val loans: LoansView = LoansView.NONE
) {
    fun used(key: Capacity) = used.getOrElse(key.ordinal) { 0 }
    fun max(key: Capacity) = max.getOrElse(key.ordinal) { 0 }

    companion object {
        val NONE = StateView("", 1, 0, 0, 0, 0, false, emptyList(), emptyList(), emptyList(), emptyList(), emptyList())
    }
}

private fun WireWriter.category(c: CategoryView) {
    utf(c.id, MAX_ID).utf(c.title, MAX_TEXT).utf(c.icon, MAX_ID).varInt(c.order)
}

private fun WireReader.category() = CategoryView(utf(MAX_ID), utf(MAX_TEXT), utf(MAX_ID), varInt())

private fun WireWriter.task(t: TaskView) {
    utf(t.kind, 32).utf(t.subject, MAX_TEXT).varLong(t.target)
}

private fun WireReader.task() = TaskView(utf(32), utf(MAX_TEXT), varLong())

private fun WireWriter.unlock(u: UnlockView) {
    utf(u.kind, 32).utf(u.id, MAX_ID).varInt(u.add).varLong(u.amount)
}

private fun WireReader.unlock() = UnlockView(utf(32), utf(MAX_ID), varInt(), varLong())

private fun WireWriter.node(n: NodeView) {
    utf(n.id, MAX_ID).utf(n.category, MAX_ID).utf(n.title, MAX_TEXT).utf(n.description, MAX_TEXT).utf(n.icon, MAX_ID)
    varInt(n.level).varLong(n.cost).varLong(n.timeMs).varLong(n.xp)
    list(n.conditions) { utf(it, MAX_TEXT) }
    list(n.requires) { varInt(it) }
    list(n.tasks) { task(it) }
    list(n.unlocks) { unlock(it) }
    nullableInt(n.x).nullableInt(n.y)
    list(n.anyRequires) { varInt(it) }
    list(n.hiddenRequires) { varInt(it) }
}

private fun WireReader.node(tree: String) = NodeView(
    tree, utf(MAX_ID), utf(MAX_ID), utf(MAX_TEXT), utf(MAX_TEXT), utf(MAX_ID),
    varInt(), varLong(), varLong(), varLong(),
    list(MAX_LINKS) { utf(MAX_TEXT) },
    list(MAX_LINKS) { varInt() },
    list(MAX_LINKS) { task() },
    list(MAX_UNLOCKS) { unlock() },
    nullableInt(), nullableInt(),
    list(MAX_LINKS) { varInt() },
    list(MAX_LINKS) { varInt() }
)

private fun WireWriter.tree(t: TreeView) {
    utf(t.id, MAX_ID).utf(t.title, MAX_TEXT).varInt(t.scope.ordinal)
    list(t.categories) { category(it) }
    list(t.nodes) { node(it) }
}

private fun WireReader.tree(): TreeView {
    val id = utf(MAX_ID)
    val title = utf(MAX_TEXT)
    val scope = enum(Scope.entries)
    val categories = list(MAX_CATEGORIES) { category() }
    val nodes = list(MAX_NODES) { node(id) }
    require(nodes.all { node -> (node.requires + node.anyRequires + node.hiddenRequires).all { it in nodes.indices } }) { "bad link" }
    return TreeView(id, title, scope, categories, nodes)
}

object ResearchWire {
    fun encodeDefs(defs: DefsView, deflateAbove: Int = DEFLATE_ABOVE): ByteArray {
        val w = WireWriter()
        w.list(defs.trees) { w.tree(it) }
        w.list(defs.extras.entries.toList()) { w.utf(it.key, 32).bytes(it.value) }
        w.list(defs.levels) { level ->
            w.varInt(level.level).varLong(level.xp).list(level.rewards) { w.unlock(it) }
            w.list(level.requires) { w.utf(it, MAX_TEXT) }
        }
        return Blob.pack(w.toBytes(), deflateAbove)
    }

    fun decodeDefs(data: ByteArray): DefsView = WireReader(Blob.unpack(data, MAX_BYTES)).let { r ->
        DefsView(
            r.list(MAX_TREES) { r.tree() },
            r.list(16) { r.utf(32) to r.bytes(MAX_BYTES) }.toMap(),
            r.list(MAX_LEVELS) { LevelView(r.varInt(), r.varLong(), r.list(MAX_UNLOCKS) { r.unlock() }, r.list(MAX_LINKS) { r.utf(MAX_TEXT) }) }
        )
    }

    fun encodeState(s: StateView): ByteArray = WireWriter().apply {
        utf(s.country, 64).varInt(s.level).varLong(s.xp).varLong(s.xpFloor).varLong(s.xpCeiling).varLong(s.treasury).bool(s.canManage)
        list(s.done) { utf(it, MAX_ID) }
        list(s.queue) { q ->
            utf(q.node, MAX_ID).varInt(q.state.ordinal).varLong(q.remainingMs).bool(q.paid)
            list(q.tasks) { varLong(it) }
        }
        list(s.available) { utf(it, MAX_ID) }
        list(s.used) { varInt(it) }
        list(s.max) { varInt(it) }
        list(s.trees) { utf(it, MAX_ID) }
        list(s.met.entries.toList()) { entry ->
            utf(entry.key, MAX_ID)
            list(entry.value) { bool(it) }
        }
        list(s.levelMet) { met -> list(met) { bool(it) } }
        list(s.tokens.entries.toList()) { utf(it.key, MAX_ID); varInt(it.value) }
        list(s.tokenCosts.entries.toList()) { utf(it.key, MAX_ID); varLong(it.value) }
        list(s.counters.entries.toList()) { utf(it.key, MAX_ID); varLong(it.value) }
        bool(s.buffs.unlocked).varInt(s.buffs.points).varInt(s.buffs.used).varInt(s.buffs.surchargePerBorderChunk).varInt(s.buffs.borderChunks)
        list(s.buffs.list) { b ->
            utf(b.key, MAX_ID).utf(b.effect, MAX_ID).varInt(b.amplifier).utf(b.mode, 16).varInt(b.cooldownSeconds).varInt(b.cost).varInt(b.tax).bool(b.enabled)
        }
        bool(s.loans.unlocked).varInt(s.loans.slots).bool(s.loans.inDefault)
        list(s.loans.active) { l -> utf(l.id, MAX_ID).varLong(l.principal).varLong(l.total).varLong(l.paid).varLong(l.perDay).varInt(l.daysLeft).varLong(l.overdue) }
        list(s.loans.offers) { o ->
            utf(o.id, MAX_ID).varLong(o.amount).varInt(o.interestPct).varInt(o.termDays).varLong(o.total).varLong(o.perDay).bool(o.unlocked).varInt(o.level).varInt(o.cooldownDays)
        }
    }.toBytes()

    fun decodeState(data: ByteArray): StateView = WireReader(data).let { r ->
        StateView(
            r.utf(64), r.varInt(), r.varLong(), r.varLong(), r.varLong(), r.varLong(), r.bool(),
            r.list(MAX_NODES) { r.utf(MAX_ID) },
            r.list(MAX_NODES) { QueueView(r.utf(MAX_ID), r.enum(NodeState.entries), r.varLong(), r.bool(), r.list(MAX_LINKS) { r.varLong() }) },
            r.list(MAX_NODES) { r.utf(MAX_ID) },
            r.list(Capacity.entries.size) { r.varInt() },
            r.list(Capacity.entries.size) { r.varInt() },
            r.list(MAX_TREES) { r.utf(MAX_ID) },
            r.list(MAX_NODES) { r.utf(MAX_ID) to r.list(MAX_LINKS + 1) { r.bool() } }.toMap(),
            r.list(MAX_LEVELS) { r.list(MAX_LINKS) { r.bool() } },
            r.list(MAX_LINKS) { r.utf(MAX_ID) to r.varInt() }.toMap(),
            r.list(MAX_LINKS) { r.utf(MAX_ID) to r.varLong() }.toMap(),
            r.list(MAX_LINKS) { r.utf(MAX_ID) to r.varLong() }.toMap(),
            BuffsView(
                r.bool(), r.varInt(), r.varInt(), r.varInt(), r.varInt(),
                r.list(MAX_NODES) { BuffView(r.utf(MAX_ID), r.utf(MAX_ID), r.varInt(), r.utf(16), r.varInt(), r.varInt(), r.varInt(), r.bool()) }
            ),
            r.bool().let { unlocked ->
                val slots = r.varInt()
                val inDefault = r.bool()
                LoansView(
                    unlocked, slots,
                    r.list(MAX_LINKS) { ActiveLoanView(r.utf(MAX_ID), r.varLong(), r.varLong(), r.varLong(), r.varLong(), r.varInt(), r.varLong()) },
                    r.list(MAX_LINKS) { LoanOfferView(r.utf(MAX_ID), r.varLong(), r.varInt(), r.varInt(), r.varLong(), r.varLong(), r.bool(), r.varInt(), r.varInt()) },
                    inDefault
                )
            }
        )
    }
}

class ResearchDefsPacket(val data: ByteArray) : CustomPacketPayload {
    val defs by lazy { ResearchWire.decodeDefs(data) }

    override fun type() = TYPE

    companion object {
        val TYPE = CustomPacketPayload.Type<ResearchDefsPacket>(net.id("research_defs"))
        val CODEC = net.codec<ResearchDefsPacket>(
            { b, v -> b.writeByteArray(v.data) },
            { b -> ResearchDefsPacket(b.readByteArray(MAX_BYTES)).also { it.defs } }
        )
    }
}

class ResearchStatePacket(val state: StateView) : CustomPacketPayload {
    override fun type() = TYPE

    companion object {
        val TYPE = CustomPacketPayload.Type<ResearchStatePacket>(net.id("research_state"))
        val CODEC = net.codec<ResearchStatePacket>(
            { b, v -> b.writeByteArray(ResearchWire.encodeState(v.state)) },
            { b -> ResearchStatePacket(ResearchWire.decodeState(b.readByteArray(MAX_BYTES))) }
        )
    }
}
