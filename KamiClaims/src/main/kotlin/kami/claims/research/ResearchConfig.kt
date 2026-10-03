package kami.claims.research

import kami.claims.Country
import kami.libs.config.Configs
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

@Serializable
data class ResearchSettings(
    val enabled: Boolean = true,
    val onlineRequired: Boolean = true,
    val tickSeconds: Int = 5,
    val baseline: List<String> = listOf("basics"),
    val locked: List<Unlock> = emptyList(),
    val announce: Boolean = true
)

@Serializable
class TreeFile(
    val title: String = "",
    val scope: Scope = Scope.ANY,
    val categories: List<Category> = emptyList(),
    val nodes: List<JsonElement> = emptyList(),
    val strict: Boolean = false
)

class ResearchDefs(val settings: ResearchSettings, val levels: LevelsConfig, val groups: Map<String, Group>, val trees: Map<String, Tree>) {
    val nodes: Map<String, Node> = trees.values.flatMap { it.nodes }.associateBy { it.key }
    val counterKeys: Set<String> = (nodes.values.flatMap { it.allConditions() } + levels.rules.values.flatMap { it.requires }).flatMap { it.counters() }.toSet()
    val taskIndex: Map<String, Map<String, List<Int>>> = nodes.values
        .flatMap { node -> node.tasks.mapIndexed { index, task -> Triple(task.kind, node.key, index) } }
        .groupBy({ it.first }, { it.second to it.third })
        .mapValues { (_, list) -> list.groupBy({ it.first }, { it.second }) }

    fun node(key: String) = nodes[key]

    fun treesFor(country: Country): List<Tree> = trees.values.filter { it.serves(country) }

    fun nodeFor(country: Country, key: String): Node? = node(key)?.takeIf { trees[it.tree]?.serves(country) == true }

    companion object {
        val EMPTY = ResearchDefs(ResearchSettings(), LevelsConfig(), emptyMap(), emptyMap())
    }
}

object Defaults {
    private val json = Configs.json()
    val groupNames = listOf("basics")
    val treeNames = listOf("metallurgy", "technology", "buffs", "tools_armor", "military")

    val groups: Map<String, Group> by lazy { groupNames.associateWith { read("groups/$it.json", Group.serializer()) } }
    val trees: Map<String, TreeFile> by lazy { treeNames.associateWith { read("trees/$it.json", TreeFile.serializer()) } }

    private fun <T> read(path: String, serializer: KSerializer<T>): T {
        val stream = Defaults::class.java.getResourceAsStream("/assets/kami_claims/research/$path") ?: error("Missing bundled $path")
        return json.decodeFromString(serializer, stream.use { it.readBytes().decodeToString() })
    }
}

object Docs {
    val settings = mapOf(
        "enabled" to "Master switch for research",
        "onlineRequired" to "Research timers only run while a citizen of the country is online",
        "tickSeconds" to "Seconds between research timer updates",
        "baseline" to "Group ids that are always unlocked",
        "locked" to "Extra selectors that stay locked until a node opens them",
        "announce" to "Announce finished research to the country"
    )
    val levels = mapOf(
        "curve" to "XP cost per level from level from: concave quadratic base + rise * (n - from) * (2 * peak - from - n) up to peak, then convex peak cost + tail * (n - peak) ^ 2",
        "xpFromLevel" to "XP is only earned from this level on, lower levels need requirements only",
        "table" to "Optional explicit XP thresholds for levels 2 and up, replaces the curve when not empty",
        "maxLevel" to "Highest reachable level",
        "sources" to "XP sources: rate per unit and daily cap per country (0 means no cap)",
        "capacities" to "Base capacities (chunks, provinces, citizens, researchSlots, queueSlots, treasury, officers, marketSlots, auctionSlots, freeChunks, plots), raised by research nodes and level rewards",
        "rules" to "Per level: xp overrides the curve (0 means requirements only), requires are conditions (chunks, plots, counter, flag, treasury, citizens, ...) that must hold to reach the level",
        "rewards" to "Rewards per level: unlocks like in nodes (capacity, group, recipe, output, block, mod) money paid once into the treasury, feature (claim_type:id, role:chancellor, banish, alliances, embargoes) or token (rename, capital_move)",
        "announce" to "Announce level ups"
    )
    val group = mapOf("unlocks" to "Selectors: group, recipe, recipe_type, recipes, output, mod, block, capacity")
    val tree = mapOf(
        "scope" to "country, province or any: which countries get this tree, a country uses every matching tree",
        "categories" to "Tabs of the tree",
        "nodes" to "Nodes: id, category, level, cost (spurs), time (like 1h30m), requires (node requirements are optional unless the tree sets strict: each missing one adds its time, recursively), tasks, unlocks"
    )
}
