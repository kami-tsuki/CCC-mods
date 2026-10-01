package kami.claims.client.app.pages

import kami.claims.client.stackOf
import kami.claims.client.store.ClientResearch
import kami.claims.client.store.NodeStatus
import kami.claims.net.NodeView
import kami.claims.net.TreeView
import kami.claims.research.NodeState
import kami.libs.ui.widget.Lock
import kami.libs.ui.core.Tip
import kami.libs.ui.graph.Cell
import kami.libs.ui.style.Format
import kami.libs.ui.style.Icons
import kami.libs.ui.style.Palette
import kami.libs.ui.text.tr
import kami.libs.text.Phrase
import kami.libs.ui.widget.TechBands
import kami.libs.ui.widget.TechNode

object ResearchGraph {
    fun nodes(tree: TreeView, matches: Set<String>, category: String?, ticking: Boolean): List<TechNode> {
        val groups = tree.categories.sortedBy { it.order }.withIndex().associate { (i, c) -> c.id to i }
        return tree.nodes.map { techNode(it, groups[it.category] ?: 0, it.key in matches, category != null && it.category != category, ticking) }
    }

    fun lanes(tree: TreeView) = tree.categories.sortedBy { it.order }.withIndex().associate { (i, c) -> i to Phrase.or("kami_claims.research.category.${c.id}", c.title).resolve() }

    fun bands(tree: TreeView) = TechBands(tree.nodes.map { it.level.coerceAtLeast(1) }.distinct().associateWith { tr("kami_libs.lock.ui.level", it) }, ClientResearch.state.level)

    fun available(tree: TreeView) = ClientResearch.state.available.count { it.startsWith("${tree.id}:") }

    fun availableIn(tree: TreeView, category: String) = tree.nodes.count { it.category == category && it.key in ClientResearch.state.available }

    private var labelTrees: List<TreeView>? = null
    private val labels = HashMap<String, String>()

    fun externalLabel(key: String): String {
        if (ClientResearch.trees !== labelTrees) { labels.clear(); labelTrees = ClientResearch.trees }
        return labels.getOrPut(key) {
            val tree = ClientResearch.trees.firstOrNull { key.startsWith(it.id + ":") }
            (tree?.label()?.resolve() ?: key.substringBefore(":")) + ": " + (ClientResearch.node(key)?.label()?.resolve() ?: key.substringAfter(":"))
        }
    }

    private fun techNode(node: NodeView, group: Int, highlight: Boolean, filteredOut: Boolean, ticking: Boolean): TechNode {
        val status = ClientResearch.status(node.key)
        val locked = node.level > ClientResearch.state.level
        val queued = ClientResearch.queued(node.key)
        val progress = queued?.takeIf { it.state == NodeState.QUEUED }?.let { q ->
            tasksProgress(node, q).let { (done, total) -> if (total > 0) done.toDouble() / total else null }
        }
        val countdown = queued?.takeIf { (it.state == NodeState.RESEARCHING || it.state == NodeState.PAUSED) && node.timeMs > 0 }?.let { ClientResearch.countdown(it, node, ticking) }
        val lock = if (locked && status != NodeStatus.DONE) Lock.level(node.level) else null
        val lockText = lock?.label
        val visible = node.external.filter { it !in node.hiddenExternal }
        return TechNode(
            label = node.label().resolve(),
            accent = ResearchLook.color(status),
            parents = node.requires,
            dashed = node.anyRequires.toSet(),
            hidden = node.hiddenRequires.toSet(),
            done = status == NodeStatus.DONE,
            dim = status == NodeStatus.LOCKED || filteredOut,
            status = ResearchLook.icon(status),
            band = node.level.coerceAtLeast(1),
            group = group,
            badge = if (visible.isEmpty()) null else Icons.CHAIN,
            item = stackOf(node.icon),
            progress = progress,
            ready = status == NodeStatus.READY,
            flowing = status == NodeStatus.RESEARCHING || status == NodeStatus.QUEUED,
            countdown = countdown,
            highlight = highlight,
            pinned = if (node.x != null && node.y != null) Cell(node.x, node.y) else null,
            tip = { Tip(node.label().resolve(), listOfNotNull(
                ResearchLook.label(status) to ResearchLook.color(status),
                lockText?.let { it to Palette.warning },
                lock?.how?.let { it to Palette.textSecondary },
                tr("kami_libs.common.cost") + ": " + Format.money(node.cost) to Palette.textSecondary,
                tr("kami_claims.research.detail.time") + ": " + Format.duration(node.timeMs) to Palette.textSecondary
            ) + visible.map { externalLabel(it) to if (it in ClientResearch.state.done) Palette.success else Palette.textSecondary }) }
        )
    }
}
