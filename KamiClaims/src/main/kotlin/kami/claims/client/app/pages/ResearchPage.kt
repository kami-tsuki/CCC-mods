package kami.claims.client.app.pages

import kami.claims.client.app.ClaimsApp
import kami.claims.client.app.ClaimsPage
import kami.claims.client.store.ClientResearch
import kami.claims.net.CategoryView
import kami.claims.net.NodeView
import kami.claims.net.TreeView
import kami.claims.research.NodeState
import kami.libs.text.Phrase
import kami.libs.ui.anim.transition
import kami.libs.ui.app.Route
import kami.libs.ui.core.Rect
import kami.libs.ui.core.Row
import kami.libs.ui.core.Stack
import kami.libs.ui.core.Ui
import kami.libs.ui.style.Draw
import kami.libs.ui.style.Icons
import kami.libs.ui.style.Palette
import kami.libs.ui.text.tr
import kami.libs.ui.widget.*
import org.lwjgl.glfw.GLFW

class ResearchPage(app: ClaimsApp) : ClaimsPage(app) {
    override val title get() = tr("kami_claims.nav.research")

    private val detail = ResearchDetail(this)
    private val views = HashMap<String, TechTreeState>()
    private val cache = HashMap<String, List<TechNode>>()
    private val bands = HashMap<String, TechBands>()
    private val chips = HashMap<String, CategoryChips>()
    private var tabs: List<TabItem>? = null
    private var subscriptions = emptyList<() -> Unit>()
    private val search = TextState()
    private var treeIndex = 0
    private var category: String? = null
    private var selected: String? = null
    private var matches: List<String> = emptyList()
    private var matchIndex = 0
    private var drawerOpen = false
    private var cachedTicking = true
    private val completed = ArrayList<String>()

    override fun opened(route: Route) {
        unsubscribe()
        subscriptions = listOf(
            ClientResearch.listen { invalidate() },
            ClientResearch.onChange { change -> if (app.isOpen) completed += change.completed }
        )
        invalidate()
        route.focus?.let { jump(it) }
    }

    override fun leaving(next: Route): Boolean {
        unsubscribe()
        completed.clear()
        return true
    }

    private fun unsubscribe() {
        subscriptions.forEach { it() }
        subscriptions = emptyList()
    }

    private fun invalidate() {
        bands.clear()
        cache.clear()
        chips.clear()
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
            ui.subTabs(main.top(CONTROL_H), items, treeIndex, "research-trees")?.let { treeIndex = it }
            main = main.dropTop(CONTROL_H + 4)
        }
        main = ui.filterBar(main, "research:filter") { bar -> toolbar(ui, bar) }
        val tree = trees[treeIndex]
        main = categoryChips(ui, main, tree)
        val ticking = info?.members?.none { it.online } != true
        if (ticking != cachedTicking) { cachedTicking = ticking; invalidate() }
        val nodes = cache.getOrPut(tree.id) { ResearchGraph.nodes(tree, matches.toSet(), category, ticking) }
        val state = views.getOrPut(tree.id) { TechTreeState() }
        val index = tree.nodes.indexOfFirst { it.key == selected }
        ui.techTree(main, nodes, state, index.takeIf { it >= 0 }, "research-tree", bands.getOrPut(tree.id) { ResearchGraph.bands(tree) })?.let { selected = tree.nodes[it].key; drawerOpen = true }
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

    private fun categoryChips(ui: Ui, area: Rect, tree: TreeView): Rect {
        if (tree.categories.size < 2) return area
        if (category != null && tree.categories.none { it.id == category }) { category = null; invalidate() }
        val entry = chips.getOrPut(tree.id) { buildChips(tree) }
        val stack = Stack(area.x, area.y, area.w, CHIP_GAP)
        ui.chipFlow(stack, entry.chips, "research-categories")?.let {
            category = entry.categories.getOrNull(it - 1)?.id.takeIf { id -> id != category }
            invalidate()
        }
        return area.dropTop(stack.bottom - area.y)
    }

    private fun buildChips(tree: TreeView): CategoryChips {
        val categories = tree.categories.sortedBy { it.order }
        val all = ChipSpec(tr("kami_claims.research.category.all"), Palette.brass, selected = category == null)
        val specs = listOf(all) + categories.map {
            val label = Phrase.or("kami_claims.research.category.${it.id}", it.title).resolve()
            ChipSpec("$label ${ResearchGraph.availableIn(tree, it.id)}", if (it.id == category) Palette.brass else Palette.textSecondary, selected = it.id == category)
        }
        return CategoryChips(categories, specs)
    }

    private fun toolbar(ui: Ui, bar: Rect) {
        if (ui.input.takeKey(GLFW.GLFW_KEY_F) { it.ctrl } != null) ui.focus = ui.id("research-search")
        val row = Row(bar, 4)
        val fit = tr("kami_claims.research.fit")
        if (ui.edgeButton(row, fit, Icons.AREA, tip = tr("kami_claims.research.fit.tooltip"), key = "research-fit")) views[ClientResearch.trees[treeIndex].id]?.refit()
        val current = ClientResearch.state.queue.firstOrNull { it.state == NodeState.RESEARCHING }?.node
        val located = current?.let { key -> ClientResearch.trees[treeIndex].nodes.indexOfFirst { it.key == key } }?.takeIf { it >= 0 }
        val tip = tr(if (located == null) "kami_claims.research.current.none" else "kami_claims.research.current")
        if (ui.iconButton(row.take(CONTROL_H), Icons.LOCATE, tip, enabled = located != null, key = "research-current")) {
            views[ClientResearch.trees[treeIndex].id]?.center(located!!)
            selected = current
        }
        val result = ui.textField(row.take(minOf(SEARCH_W, bar.w / 2)), search, tr("kami_claims.research.search"), Icons.SEARCH, key = "research-search", clearable = true)
        if (result.changed) { matches = find(search.text); matchIndex = 0; cache.clear(); matches.firstOrNull()?.let { jump(it) } }
        else if (result.submitted && matches.isNotEmpty()) { matchIndex = (matchIndex + 1) % matches.size; jump(matches[matchIndex]) }
        val hint = when {
            search.text.isBlank() -> tr("kami_claims.research.hint")
            matches.isEmpty() -> tr("kami_claims.research.no_match")
            else -> tr("kami_claims.research.match", matchIndex + 1, matches.size)
        }
        Draw.text(ui.g, Draw.fit(hint, row.rest.w), row.rest.x + 2, bar.y + 5, Palette.textMuted)
    }

    private fun find(query: String): List<String> {
        val q = query.trim().lowercase()
        if (q.isEmpty()) return emptyList()
        val all = ClientResearch.trees.flatMap { it.nodes }
        val byTitle = all.filter { it.label().resolve().lowercase().contains(q) }
        val byText = all.filter { it !in byTitle && it.summary().resolve().lowercase().contains(q) }
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

private class CategoryChips(val categories: List<CategoryView>, val chips: List<ChipSpec>)

private const val SIDE_W = 216
private const val CHIP_GAP = 3
private const val SEARCH_W = 170
private const val COMPACT_W = 480
private const val DRAWER_MARGIN = 40
