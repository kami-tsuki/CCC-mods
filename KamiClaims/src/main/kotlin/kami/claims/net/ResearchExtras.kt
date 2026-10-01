package kami.claims.net

import kami.claims.research.Limits.MAX_ID
import kami.claims.research.Matched
import kami.claims.research.Resolution
import kami.libs.net.WireReader
import kami.libs.net.WireWriter

private const val MAX_IDS = 1 shl 20
private const val MAX_GROUPS = 4096

class ExtraKeys(val ids: String, val nodes: String, val levels: String, val producers: String)

class IdExtras(
    val ids: List<String>,
    val nodes: Map<String, List<Int>>,
    val levels: Map<Int, List<Int>>,
    val producers: Map<String, List<Int>>,
    private val keys: ExtraKeys
) {
    private val index by lazy { ids.withIndex().associate { (i, id) -> id to i } }

    fun openIndices(doneNodes: Collection<String>, level: Int): Set<Int> =
        doneNodes.flatMap { nodes[it].orEmpty() }.toSet() + levels.filterKeys { it <= level }.values.flatten()

    fun hidden(doneNodes: Collection<String>, level: Int): Set<String> {
        val open = openIndices(doneNodes, level)
        return ids.filterIndexed { i, _ -> i !in open }.toSet()
    }

    fun unlockedBy(id: String): List<String> = index[id]?.let { i -> nodes.filterValues { i in it }.keys.sorted() }.orEmpty()

    fun unlockedByLevels(id: String): List<Int> = index[id]?.let { i -> levels.filterValues { i in it }.keys.sorted() }.orEmpty()

    fun producing(item: String): List<String> = producers[item].orEmpty().mapNotNull { ids.getOrNull(it) }

    private fun encode(): Map<String, ByteArray> = mapOf(
        keys.ids to WireWriter().apply { list(ids) { utf(it, MAX_ID) } }.toBytes(),
        keys.nodes to WireWriter().apply { list(nodes.entries.toList()) { e -> utf(e.key, MAX_ID); list(e.value) { varInt(it) } } }.toBytes(),
        keys.levels to WireWriter().apply { list(levels.entries.toList()) { e -> varInt(e.key); list(e.value) { varInt(it) } } }.toBytes(),
        keys.producers to WireWriter().apply { list(producers.entries.toList()) { e -> utf(e.key, MAX_ID); list(e.value) { varInt(it) } } }.toBytes()
    )

    companion object {
        val RECIPES = ExtraKeys("recipes", "node_recipes", "level_recipes", "recipe_producers")
        val BLOCKS = ExtraKeys("blocks", "node_blocks", "level_blocks", "block_producers")

        fun empty(keys: ExtraKeys) = IdExtras(emptyList(), emptyMap(), emptyMap(), emptyMap(), keys)

        fun encode(resolution: Resolution) =
            of(resolution, RECIPES, { it.recipes }, resolution.outputs).encode() + of(resolution, BLOCKS, { it.blocks }, emptyMap()).encode()

        private fun of(resolution: Resolution, keys: ExtraKeys, pick: (Matched) -> Set<String>, outputs: Map<String, List<String>>): IdExtras {
            val ids = pick(resolution.gated).sorted()
            val index = ids.withIndex().associate { (i, id) -> id to i }
            fun indices(matched: Matched) = pick(matched).mapNotNull { index[it] }.sorted()
            val producers = outputs.flatMap { (recipe, items) -> items.map { it to index.getValue(recipe) } }.groupBy({ it.first }, { it.second }).mapValues { it.value.sorted() }
            return IdExtras(
                ids,
                resolution.nodes.mapValues { indices(it.value) }.filterValues { it.isNotEmpty() },
                resolution.levels.mapValues { indices(it.value) }.filterValues { it.isNotEmpty() },
                producers,
                keys
            )
        }

        fun decode(extras: Map<String, ByteArray>, keys: ExtraKeys): IdExtras {
            val ids = extras[keys.ids] ?: return empty(keys)
            return runCatching {
                IdExtras(
                    WireReader(ids).let { r -> r.list(MAX_IDS) { r.utf(MAX_ID) } },
                    WireReader(extras.getValue(keys.nodes)).let { r -> r.list(MAX_GROUPS) { r.utf(MAX_ID) to r.list(MAX_IDS) { r.varInt() } }.toMap() },
                    WireReader(extras.getValue(keys.levels)).let { r -> r.list(MAX_GROUPS) { r.varInt() to r.list(MAX_IDS) { r.varInt() } }.toMap() },
                    WireReader(extras.getValue(keys.producers)).let { r -> r.list(MAX_IDS) { r.utf(MAX_ID) to r.list(MAX_IDS) { r.varInt() } }.toMap() },
                    keys
                )
            }.getOrDefault(empty(keys))
        }
    }
}
