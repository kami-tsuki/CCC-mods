package kami.claims.client.app.pages

import kami.claims.client.app.ClaimsApp
import kami.claims.client.app.ClaimsPage
import kami.claims.client.store.ClientResearch
import kami.claims.net.NodeView
import kami.claims.net.TreeView
import kami.claims.research.NodeState
import kami.libs.text.SearchQuery
import kami.libs.ui.anim.transition
import kami.libs.ui.app.Route
import kami.libs.ui.core.Memo
import kami.libs.ui.core.Rect
import kami.libs.ui.core.Row
import kami.libs.ui.core.Ui
import kami.libs.ui.style.Icons
import kami.libs.ui.style.Palette
import kami.libs.ui.text.tr
import kami.libs.ui.widget.*
import org.lwjgl.glfw.GLFW

class ResearchPage(app: ClaimsApp) : ClaimsPage(app) {
    override val title get() = tr("kami_claims.nav.research")

    override val subtitle: String? get() = boostHint("research")

    private val detail = ResearchDetail(this)
    private val views = HashMap<String, TechTreeState>()
    private val cache = HashMap<String, List<TechNode>>()
    private val bands = HashMap<String, TechBands>()
    private val lanes = HashMap<String, Map<Int, String>>()
    private var tabs: List<TabItem>? = null
    private val selectedIndex = Memo()
    private val currentIndex = Memo()
    private val search = TextState()
    private var treeIndex = 0
    private var selected: String? = null
    private var matches: List<String> = emptyList()
    private var matchIndex = 0
    private var drawerOpen = false
    private var cachedTicking = true
    private val completed = ArrayList<String>()

    init {
        subscribe(
            ClientResearch.listen { invalidate() },
            ClientResearch.onChange { change -> if (app.isOpen && app.route.page == "research") completed += change.completed }
        )
    }

    override fun opened(route: Route) {
        invalidate()
        route.focus?.let { jump(it) }
    }

    override fun leaving(next: Route): Boolean {
        completed.clear()
        return super.leaving(next)
    }

    private fun invalidate() {
        bands.clear()
        lanes.clear()
        cache.clear()
        tabs = null
    }

    override fun draw(ui: Ui, r: Rect) {
        val trees = ClientResearch.trees
        if (trees.isEmpty()) {
            ui.emptyState(r, tr("kami_claims.research.empty.title"), tr("kami_claims.research.empty.body"))
            return
        }
        treeIndex = treeIndex.coerceIn(0, trees.lastIndex)
        val drawer = r.w < COMPACT_W
        val side = r.right(if (drawer) minOf(SIDE_W, r.w - DRAWER_MARGIN) else SIDE_W)
        var main = if (drawer) r else r.dropRight(SIDE_W, 6)
        if (trees.size > 1) {
            val items = tabs ?: trees.map { TabItem(it.label().resolve(), Icons.TREE, ResearchGraph.available(it)) }.also { tabs = it }
            ui.subTabs(main.top(CONTROL_H), items, treeIndex, "research-trees")?.let { if (it != treeIndex) { treeIndex = it; selected = null; drawerOpen = false } }
            main = main.dropTop(CONTROL_H + 4)
        }
        main = ui.filterBar(main, "research:filter") { bar -> toolbar(ui, bar) }
        val tree = trees[treeIndex]
        val ticking = !nobodyOnline
        if (ticking != cachedTicking) { cachedTicking = ticking; invalidate() }
        val nodes = cache.getOrPut(tree.id) { ResearchGraph.nodes(tree, matches.toSet(), ticking) }
        val state = views.getOrPut(tree.id) { TechTreeState() }
        val index = nodeIndex(selectedIndex, tree, selected)
        ui.techTree(main, nodes, state, index.takeIf { it >= 0 }, "research-tree", bands.getOrPut(tree.id) { ResearchGraph.bands(tree) }, lanes.getOrPut(tree.id) { ResearchGraph.lanes(tree) })?.let { selected = tree.nodes[it].key; drawerOpen = true }
        if (state.takeClear()) { selected = null; drawerOpen = false }
        celebrate(tree, state)
        val node = selected?.let { ClientResearch.node(it) }
        if (!drawer) detail.draw(ui, side, node, null) { jump(it, reselect = false) }
        else drawer(ui, side, node)
    }

    private fun drawer(ui: Ui, side: Rect, node: NodeView?) {
        val open = drawerOpen && node != null
        val t = ui.transition("research-drawer", open)
        if (t <= 0f) return
        ui.overlay {
            val box = side.slideIn(t, side.w, 0)
            ui.block(box)
            if (open) ui.onEscape(20) { drawerOpen = false }
            detail.draw(ui, box, node, { drawerOpen = false }) { jump(it, reselect = false) }
        }
    }

    private fun celebrate(tree: TreeView, state: TechTreeState) {
        completed.removeAll { key -> tree.nodes.indexOfFirst { it.key == key }.takeIf { it >= 0 }?.also(state::burst) != null }
    }

    private fun nodeIndex(memo: Memo, tree: TreeView, key: String?) = memo.of(tree, key) { tree.nodes.indexOfFirst { it.key == key } }

    private fun toolbar(ui: Ui, bar: Rect) {
        val tree = ClientResearch.trees.getOrNull(treeIndex) ?: return
        if (ui.input.takeKey(GLFW.GLFW_KEY_F) { it.ctrl } != null) ui.focus = ui.id("research-search")
        val row = Row(bar, 4)
        val fit = tr("kami_claims.research.fit")
        if (ui.edgeButton(row, fit, Icons.AREA, tip = tr("kami_claims.research.fit.tooltip"), key = "research-fit")) views[tree.id]?.refit()
        val current = ClientResearch.state.queue.firstOrNull { it.state == NodeState.RESEARCHING }?.node
        val located = nodeIndex(currentIndex, tree, current).takeIf { it >= 0 }
        val tip = tr(if (located == null) "kami_claims.research.current.none" else "kami_claims.research.current")
        if (ui.iconButton(row.take(CONTROL_H), Icons.LOCATE, tip, enabled = located != null, key = "research-current")) {
            located?.let { views[tree.id]?.center(it) }
            selected = current
            drawerOpen = true
        }
        val result = ui.textField(row.take(minOf(SEARCH_W, bar.w / 2)), search, tr("kami_claims.research.search"), Icons.SEARCH, key = "research-search", clearable = true)
        if (result.changed) { matches = find(search.text); matchIndex = 0; cache.clear(); matches.firstOrNull()?.let { jump(it) } }
        else if (result.submitted && matches.isNotEmpty()) { matchIndex = (matchIndex + 1) % matches.size; jump(matches[matchIndex]) }
        val hint = when {
            search.text.isBlank() -> tr("kami_claims.research.hint")
            matches.isEmpty() -> tr("kami_libs.common.nothing_found")
            else -> tr("kami_claims.research.match", matchIndex + 1, matches.size)
        }
        ui.fitText(row.rest.x + 2, bar.y + 5, row.rest.w, hint, Palette.textMuted)
    }

    private fun find(query: String): List<String> {
        val q = SearchQuery.parse(query)
        if (q.isEmpty) return emptyList()
        val all = ClientResearch.trees.flatMap { it.nodes }
        val byTitle = all.filter { q.matches(it.label().resolve()) }
        val byText = all.filter { it !in byTitle && q.matches(it.summary().resolve()) }
        return (byTitle + byText).map { it.key }
    }

    private fun jump(key: String, reselect: Boolean = true) {
        val trees = ClientResearch.trees
        val tree = trees.indexOfFirst { t -> t.nodes.any { it.key == key } }.takeIf { it >= 0 } ?: return
        treeIndex = tree
        selected = key
        drawerOpen = true
        if (reselect) views.getOrPut(trees[tree].id) { TechTreeState() }.center(trees[tree].nodes.indexOfFirst { it.key == key })
    }
}

private const val SIDE_W = 216
private const val SEARCH_W = 170
private const val COMPACT_W = 480
private const val DRAWER_MARGIN = 40
