package kami.claims.research

import kami.claims.research.Limits.MAX_CATEGORIES
import kami.claims.research.Limits.MAX_ID
import kami.claims.research.Limits.MAX_KEY
import kami.claims.research.Limits.MAX_LEVELS
import kami.claims.research.Limits.MAX_LINKS
import kami.claims.research.Limits.MAX_NODES
import kami.claims.research.Limits.MAX_TEXT
import kami.claims.research.Limits.MAX_TREES
import kami.claims.research.Limits.MAX_UNLOCKS
import kami.libs.config.Configs
import kami.libs.config.Jsonc
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

class Built(val defs: ResearchDefs, val problems: List<String>)

object Validator {
    private val json = Configs.json()

    fun build(
        rawSettings: ResearchSettings,
        levels: LevelsConfig,
        groups: Map<String, Group>,
        files: Map<String, TreeFile>,
        previous: Map<String, Tree> = emptyMap(),
        fallback: Map<String, TreeFile> = emptyMap()
    ): Built {
        val problems = ArrayList<String>()
        val settings = if (rawSettings.tickSeconds >= 1) rawSettings else rawSettings.copy(tickSeconds = 1).also { problems += "research.json: tickSeconds must be at least 1" }
        if (levels.maxLevel > MAX_LEVELS || levels.table.size >= MAX_LEVELS) problems += "levels.json: at most $MAX_LEVELS levels are supported"
        val cleanGroups = groups.mapValues { (id, group) -> cleanGroup(id, group, problems) }
        settings.baseline.filter { it !in groups }.forEach { problems += "research.json: unknown baseline group $it" }
        val drafts = LinkedHashMap<String, Draft>()
        val reused = LinkedHashMap<String, Tree>()
        (fallback.keys + files.keys).forEach { id ->
            val draft = files[id]?.let { draft(id, it, problems) }
            when {
                draft != null -> drafts[id] = draft
                previous[id] != null -> reused[id] = previous.getValue(id)
                else -> fallback[id]?.let { draft(id, it, problems) }?.let { drafts[id] = it }
            }
        }
        settle(drafts, reused, previous, cleanGroups.keys, levels.top, problems)
        val trees = LinkedHashMap<String, Tree>()
        (fallback.keys + files.keys).forEach { id ->
            val tree = drafts[id]?.let { Tree(id, it.file.title, it.file.scope, it.file.categories, it.nodes) } ?: reused[id]
            when {
                tree == null -> Unit
                trees.size >= MAX_TREES -> problems += "trees/$id.json: ignored, at most $MAX_TREES trees are supported"
                else -> trees[id] = tree
            }
        }
        val known = trees.values.flatMap { tree -> tree.nodes.map { it.key } }.toSet()
        rewardProblems(levels, cleanGroups.keys, known).forEach { problems += "levels.json: $it" }
        problems += costProblems(levels, trees.values.flatMap { it.nodes })
        return Built(ResearchDefs(settings, cleanLevels(levels, problems), cleanGroups, trees), problems)
    }

    private fun treasuryCap(levels: LevelsConfig, level: Int) =
        levels.capacity(Capacity.TREASURY) + levels.rewardsUpTo(level).filterIsInstance<CapacityUnlock>().filter { it.key == Capacity.TREASURY }.sumOf { it.add }

    private fun costProblems(levels: LevelsConfig, nodes: List<Node>) = nodes.mapNotNull { node ->
        val cap = treasuryCap(levels, node.level)
        if (node.cost > cap) "${node.key}: cost ${node.cost} is above the treasury cap $cap at level ${node.level}" else null
    }

    private fun cleanLevels(levels: LevelsConfig, problems: MutableList<String>): LevelsConfig {
        val before = problems.size
        val rewards = levels.rewards.mapValues { (level, list) ->
            val cap = treasuryCap(levels, level)
            list.mapNotNull { reward ->
                when {
                    reward.oversized() -> null.also { problems += "levels.json: reward at level $level: id is longer than $MAX_ID" }
                    reward is MoneyReward && reward.amount > cap -> MoneyReward(cap.toLong()).also { problems += "levels.json: reward at level $level: money ${reward.amount} is above the treasury cap $cap, clamped" }
                    else -> reward
                }
            }
        }
        if (problems.size == before) return levels
        return LevelsConfig(levels.curve, levels.table, levels.maxLevel, levels.xpFromLevel, levels.sources, levels.counted, levels.capacities, levels.rules, rewards, levels.announce)
    }

    private fun Unlock.oversized() = when (this) {
        is RecipeUnlock -> listOf(id)
        is RecipeTypeUnlock -> listOf(id)
        is RecipesUnlock -> listOfNotNull(recipeType, input, output)
        is OutputUnlock -> listOf(id)
        is ModUnlock -> listOf(id)
        is BlockUnlock -> listOf(id)
        is FeatureUnlock -> listOf(id)
        is BuffUnlock -> listOf(effect)
        is LoanUnlock -> listOf(id)
        is TokenUnlock -> listOf(id)
        else -> emptyList()
    }.any { it.length > MAX_ID }

    private class Draft(val id: String, val file: TreeFile, var nodes: List<Node>)

    private fun settle(drafts: MutableMap<String, Draft>, reused: MutableMap<String, Tree>, previous: Map<String, Tree>, groups: Set<String>, maxLevel: Int, problems: MutableList<String>) {
        while (true) {
            val all = drafts.values.flatMap { it.nodes } + reused.values.flatMap { it.nodes }
            val keys = all.map { it.key }.toSet()
            val sharedLoans = all.flatMap { node -> node.unlocks.filterIsInstance<LoanUnlock>().map { it.id } }.groupingBy { it }.eachCount().filterValues { it > 1 }.keys
            var changed = false
            drafts.values.forEach { draft ->
                val categories = draft.file.categories.map { it.id }.toSet()
                val flawed = draft.nodes.mapNotNull { node -> flaw(node, keys, categories, groups, sharedLoans, maxLevel)?.let { node to it } }
                flawed.forEach { (node, text) -> problems += "trees/${draft.id}.json: node ${node.id}: $text" }
                if (flawed.isNotEmpty()) {
                    draft.nodes = draft.nodes - flawed.map { it.first }.toSet()
                    changed = true
                }
            }
            if (changed) continue
            val cyclic = cycle(drafts.values.flatMap { it.nodes } + reused.values.flatMap { it.nodes })
            if (cyclic.isEmpty()) return
            val broken = drafts.values.filter { draft -> draft.nodes.any { it.key in cyclic } }.map { it.id } + reused.values.filter { tree -> tree.nodes.any { it.key in cyclic } }.map { it.id }
            broken.forEach { id ->
                problems += "trees/$id.json: cycle between ${cyclic.filter { it.startsWith("$id:") }.joinToString(", ")}"
                if (drafts.remove(id) != null) previous[id]?.let { reused[id] = it } else reused.remove(id)
            }
        }
    }

    private fun rewardProblems(levels: LevelsConfig, groups: Set<String>, nodes: Set<String>): List<String> {
        val rewards = levels.rewards.flatMap { (level, rewards) ->
            rewards.mapNotNull { reward ->
                when {
                    level !in 1..levels.top -> "reward level $level is out of range"
                    reward is GroupRef && reward.id !in groups -> "reward at level $level: unknown group ${reward.id}"
                    else -> null
                }
            }
        }
        val rules = levels.rules.flatMap { (level, rule) ->
            listOfNotNull(if (level !in 2..levels.top) "rule level $level is out of range" else null) +
                rule.requires.mapNotNull { condition ->
                    when {
                        condition.refs().any { it !in nodes } -> "rule at level $level: unknown node ${condition.refs().first { it !in nodes }}"
                        condition.describe().json().length > MAX_TEXT -> "rule at level $level: condition is too long"
                        condition.counters().any { it.length > MAX_ID } -> "rule at level $level: counter key is longer than $MAX_ID"
                        else -> null
                    }
                }
        }
        return rewards + rules
    }

    private fun cleanGroup(id: String, group: Group, problems: MutableList<String>): Group {
        val (nested, plain) = group.unlocks.partition { it is GroupRef }
        if (nested.isNotEmpty()) problems += "groups/$id.json: groups cannot contain groups"
        val fitting = plain.filterNot { it.oversized() }
        if (fitting.size < plain.size) problems += "groups/$id.json: unlock id is longer than $MAX_ID"
        return Group(group.title, fitting)
    }

    private fun draft(id: String, file: TreeFile, problems: MutableList<String>): Draft? {
        fun report(text: String) = problems.add("trees/$id.json: $text")
        duplicate(file.categories.map { it.id })?.let { report("duplicate category $it"); return null }
        if (file.categories.size > MAX_CATEGORIES) { report("more than $MAX_CATEGORIES categories"); return null }
        if (file.categories.any { it.id.length > MAX_ID || it.icon.length > MAX_ID || it.title.length > MAX_TEXT }) { report("category text is too long"); return null }
        if (id.length > MAX_ID || file.title.length > MAX_TEXT) { report("tree id or title is too long"); return null }
        if (file.nodes.size > MAX_NODES) report("only the first $MAX_NODES nodes are used")
        val decoded = file.nodes.take(MAX_NODES).mapNotNull { element ->
            runCatching { json.decodeFromJsonElement(Node.serializer(), element) }
                .onFailure { report("node ${nameOf(element)}: ${Jsonc.reason(it)}") }
                .getOrNull()
        }
        duplicate(decoded.map { it.id })?.let { report("duplicate node $it"); return null }
        return Draft(id, file, decoded.map { it.placed(id) })
    }

    private fun flaw(node: Node, keys: Set<String>, categories: Set<String>, groups: Set<String>, sharedLoans: Set<String>, maxLevel: Int): String? = when {
        node.category !in categories -> "unknown category ${node.category}"
        node.level !in 0..maxLevel -> "level ${node.level} is out of range"
        node.cost < 0 -> "negative cost"
        listOf(node.key, node.category, node.icon).any { it.length > MAX_ID } -> "key, category or icon is longer than $MAX_ID"
        node.tasks.any { it.kind.length > MAX_KEY } || node.allConditions().any { condition -> condition.counters().any { it.length > MAX_ID } } || node.unlocks.any { it.oversized() } ->
            "task kind (max $MAX_KEY), counter key or unlock id (max $MAX_ID) is too long"
        node.title.length > MAX_TEXT || node.description.length > MAX_TEXT -> "title or description is longer than $MAX_TEXT"
        node.requires.any { it.describe().json().length > MAX_TEXT } || node.tasks.any { it is HoldTask && it.condition.describe().json().length > MAX_TEXT } -> "condition is too long"
        node.requires.size > MAX_LINKS || node.tasks.size > MAX_LINKS || node.unlocks.size > MAX_UNLOCKS || node.dependencies().distinct().size > MAX_LINKS -> "more than $MAX_LINKS requirements, tasks or links, or more than $MAX_UNLOCKS unlocks"
        node.tasks.any { it.subject.length > MAX_TEXT } -> "task subject is longer than $MAX_TEXT"
        node.unlocks.any { it is MoneyReward || it is TokenUnlock } -> "money and tokens are only level rewards"
        node.unlocks.count { it is BuffUnlock } > 1 -> "at most one buff per node"
        node.unlocks.any { it is BuffUnlock && (it.effect.isBlank() || it.amplifier !in 0..9 || it.cooldownSeconds < 0) || it is BuffPointsUnlock && it.add < 0 } -> "invalid buff"
        node.unlocks.any { it is LoanUnlock && (it.id.isBlank() || it.amount <= 0 || it.interestPct < 0 || it.termDays <= 0) || it is LoanSlotsUnlock && it.add < 0 } -> "invalid loan"
        node.unlocks.any { it is LoanUnlock && it.id in sharedLoans } -> "duplicate loan id"
        node.unlocks.any { it is RecipesUnlock && it.recipeType == null && it.input == null && it.output == null } -> "recipes unlock needs a type, input or output"
        node.tasks.any { it is DepositTask && it.selectors.isEmpty() } -> "deposit task needs an item"
        else -> node.dependencies().firstOrNull { it !in keys }?.let { "unknown node $it" }
            ?: node.unlocks.filterIsInstance<GroupRef>().firstOrNull { it.id !in groups }?.let { "unknown group ${it.id}" }
    }

    private fun cycle(nodes: List<Node>): List<String> {
        val waiting = nodes.associate { it.key to it.dependencies().toSet() }.toMutableMap()
        while (true) {
            val free = waiting.filterValues { it.isEmpty() }.keys
            if (free.isEmpty()) return waiting.keys.toList()
            free.forEach { waiting.remove(it) }
            waiting.replaceAll { _, deps -> deps - free }
            if (waiting.isEmpty()) return emptyList()
        }
    }

    private fun duplicate(ids: List<String>) = ids.groupingBy { it }.eachCount().entries.firstOrNull { it.value > 1 }?.key

    private fun nameOf(element: JsonElement) = ((element as? JsonObject)?.get("id") as? JsonPrimitive)?.contentOrNull ?: "?"
}
