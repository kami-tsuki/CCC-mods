package kami.claims.client.store

import kami.claims.client.forgetStacks
import kami.claims.net.DefsView
import kami.claims.research.NodeState
import kami.libs.ui.anim.Countdown
import kami.claims.net.NodeView
import kami.claims.net.QueueView
import kami.claims.net.IdExtras
import kami.claims.net.StateView
import kami.claims.net.TreeView
import kami.libs.text.Phrase

enum class NodeStatus { DONE, RESEARCHING, PAUSED, READY, QUEUED, AVAILABLE, LOCKED }

object ClientResearch {
    var defs = DefsView.EMPTY
        private set
    var state = StateView.NONE
        private set
    private var nodes: Map<String, NodeView> = emptyMap()
    private var recipes = IdExtras.empty(IdExtras.RECIPES)
    private var blocks = IdExtras.empty(IdExtras.BLOCKS)
    private var hiddenBlocks = emptySet<String>()
    private var hidden = emptySet<String>()
    private var outputs: Map<String, List<String>>? = null
    private val listeners = ArrayList<() -> Unit>()
    private val changeListeners = ArrayList<(ResearchChange) -> Unit>()
    var receivedAt = 0L
        private set

    var trees: List<TreeView> = emptyList()
        private set

    val maxLevel: Int get() = defs.levels.maxOfOrNull { it.level } ?: state.level

    val atMaxLevel: Boolean get() = state.level >= maxLevel

    val xpTracked: Boolean get() = state.xpCeiling > state.xpFloor

    val xpFraction: Float get() = if (!xpTracked) 0f else ((state.xp - state.xpFloor).toFloat() / (state.xpCeiling - state.xpFloor)).coerceIn(0f, 1f)

    fun listen(listener: () -> Unit): () -> Unit {
        listeners += listener
        return { listeners -= listener }
    }

    fun onChange(listener: (ResearchChange) -> Unit): () -> Unit {
        changeListeners += listener
        return { changeListeners -= listener }
    }

    fun penalty(key: String) = state.penalty[key] ?: 0L

    fun total(node: NodeView) = node.timeMs + penalty(node.key)

    fun missing(node: NodeView): List<NodeView> {
        val memo = HashMap<String, Set<String>>()
        fun time(keys: Set<String>) = keys.sumOf { nodes[it]?.timeMs ?: 0L }
        fun deps(n: NodeView): Set<String> = memo[n.key] ?: run {
            memo[n.key] = emptySet()
            val tree = trees.firstOrNull { it.id == n.tree }
            fun chain(key: String) = setOf(key) + (nodes[key]?.let(::deps) ?: emptySet())
            val any = n.anyRequires.mapNotNull { tree?.nodes?.getOrNull(it)?.key }
            val hard = n.requires.mapNotNull { tree?.nodes?.getOrNull(it)?.key }.filter { it !in any } + n.external
            val choice = if (any.isEmpty() || any.any { it in state.done }) emptySet() else any.map(::chain).minBy(::time)
            hard.filter { it !in state.done }.fold(choice) { acc, key -> acc + chain(key) }.also { memo[n.key] = it }
        }
        return deps(node).mapNotNull { nodes[it] }.sortedByDescending { it.timeMs }
    }

    fun countdown(queue: QueueView, node: NodeView, ticking: Boolean): Countdown {
        val left = 100 - state.speedPct
        return Countdown(total(node) * left / 100, queue.remainingMs * left / 100, receivedAt, ticking && queue.state == NodeState.RESEARCHING)
    }

    fun node(key: String) = nodes[key]

    fun hiddenRecipes(): Set<String> = hidden

    fun outputs(key: String): List<String> = (outputs ?: buildOutputs().also { outputs = it })[key].orEmpty()

    private fun buildOutputs(): Map<String, List<String>> {
        val made = HashMap<Int, MutableList<String>>()
        recipes.producers.forEach { (item, ids) -> ids.forEach { made.getOrPut(it) { ArrayList() } += item } }
        return nodes.keys.associateWith { key ->
            (recipes.nodes[key].orEmpty().flatMap { made[it].orEmpty() } + blocks.nodes[key].orEmpty().mapNotNull { blocks.ids.getOrNull(it) }).distinct()
        }
    }

    fun lockedBlocks(): Set<String> = hiddenBlocks

    fun recipesLockingItem(itemId: String): List<String> = recipes.producing(itemId).let { all -> if (all.isNotEmpty() && hidden.containsAll(all)) all else emptyList() }

    fun unlockedBy(recipeOrItemId: String): List<String> = (recipes.unlockedBy(recipeOrItemId) + blocks.unlockedBy(recipeOrItemId)).distinct()

    fun unlockedByLevels(recipeOrItemId: String): List<Int> = (recipes.unlockedByLevels(recipeOrItemId) + blocks.unlockedByLevels(recipeOrItemId)).distinct().sorted()

    fun levelRequirements(level: Int): List<Pair<Phrase, Boolean>> {
        val met = state.levelMet.getOrNull(level - 1).orEmpty()
        return defs.levels.firstOrNull { it.level == level }?.requires.orEmpty().mapIndexedNotNull { i, text -> Phrase.parse(text)?.let { it to met.getOrElse(i) { false } } }
    }

    fun levelProgress(level: Int, index: Int): Pair<Long, Long>? = state.levelProgress.getOrNull(level - 1)?.getOrNull(index)?.takeIf { it.second > 1 }

    fun unlockLevel(featureId: String): Int? =
        defs.levels.firstOrNull { level -> level.rewards.any { it.kind == "feature" && it.id == featureId } }?.level

    val buffs get() = state.buffs

    val loans get() = state.loans

    fun tokens(id: String): Int = state.tokens[id] ?: 0

    fun tokenCost(id: String): Long = state.tokenCosts[id] ?: 0

    fun conditionMet(key: String): List<Boolean>? = state.met[key]

    fun queued(key: String): QueueView? = state.queue.firstOrNull { it.node == key }

    fun status(key: String): NodeStatus =
        if (key in state.done) NodeStatus.DONE
        else queued(key)?.let { NodeStatus.valueOf(it.state.name) } ?: if (key in state.available) NodeStatus.AVAILABLE else NodeStatus.LOCKED

    fun receive(next: DefsView) {
        applyDefs(next)
        notifyListeners()
    }

    fun receive(next: StateView) {
        val change = ResearchChange.between(state, next)
        applyState(next)
        notifyListeners()
        change?.let { c -> changeListeners.toList().forEach { it(c) } }
    }

    fun clear() {
        loadDefs(DefsView.EMPTY)
        loadState(StateView.NONE)
        refreshDerived()
        notifyListeners()
    }

    private fun applyDefs(next: DefsView) {
        loadDefs(next)
        refreshDerived()
    }

    private fun loadDefs(next: DefsView) {
        defs = next
        forgetStacks()
        nodes = next.trees.flatMap { it.nodes }.associateBy { it.key }
        recipes = IdExtras.decode(next.extras, IdExtras.RECIPES)
        blocks = IdExtras.decode(next.extras, IdExtras.BLOCKS)
        outputs = null
    }

    private fun applyState(next: StateView) {
        loadState(next)
        refreshDerived()
    }

    private fun loadState(next: StateView) {
        state = next
        receivedAt = System.currentTimeMillis()
    }

    private fun refreshDerived() {
        trees = defs.trees.filter { it.id in state.trees }
        val done = if (state.country.isEmpty()) emptyList() else state.done
        val level = if (state.country.isEmpty()) 0 else state.level
        hidden = recipes.hidden(done, level)
        hiddenBlocks = blocks.hidden(done, level)
    }

    private fun notifyListeners() = listeners.toList().forEach { it() }
}
