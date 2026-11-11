package kami.claims.service

import kami.claims.*
import kami.claims.gov.JobRules
import kami.claims.gov.JobSpec
import kami.claims.gov.TaskKind
import kami.claims.gov.TaskSpec
import kami.claims.world.Placed
import kami.libs.util.RecentSet
import net.minecraft.core.BlockPos
import net.minecraft.core.Registry
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.core.registries.Registries
import net.minecraft.resources.ResourceKey
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.level.ServerPlayer
import net.minecraft.tags.TagKey
import net.minecraft.world.item.Item
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.CocoaBlock
import net.minecraft.world.level.block.CropBlock
import net.minecraft.world.level.block.NetherWartBlock
import net.minecraft.world.level.block.PitcherCropBlock
import net.minecraft.world.level.block.SweetBerryBushBlock
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.block.state.properties.IntegerProperty
import net.neoforged.neoforge.common.util.FakePlayer
import kotlin.math.floor

object Credit {
    private const val TAG_CACHE = 256
    private val blockKinds = mapOf(TaskKind.HARVEST to Action.BREAK, TaskKind.MINE to Action.BREAK, TaskKind.CHOP to Action.BREAK, TaskKind.BUILD to Action.PLACE)
    private val inputKinds = setOf(TaskKind.HARVEST, TaskKind.MINE, TaskKind.CHOP)
    private val blankBlockFilters = mapOf(
        TaskKind.MINE to "#c:ores,#minecraft:base_stone_overworld",
        TaskKind.HARVEST to "#minecraft:crops",
        TaskKind.CHOP to "#minecraft:logs"
    )
    private val paidPlaces = RecentSet<Pair<String, Long>>(8192)
    private val dims = java.util.concurrent.ConcurrentHashMap<ResourceKey<Level>, String>()

    private class Spec<T : Any>(val id: ResourceLocation, val tag: TagKey<T>?)
    private class Filter<T : Any>(val blank: Boolean, val specs: List<Spec<T>>)

    private val blockFilters = HashMap<String, Filter<Block>>()
    private val itemFilters = HashMap<String, Filter<Item>>()

    fun reset() = paidPlaces.clear()

    fun dimOf(level: Level): String = level.dimension().let { key -> dims.getOrPut(key) { key.location().toString() } }

    private fun <T : Any> parse(raw: String, registry: ResourceKey<Registry<T>>): Filter<T> {
        val entries = raw.split(',').map { it.trim() }.filter { it.isNotEmpty() }
        return Filter(entries.isEmpty(), entries.mapNotNull { spec ->
            ResourceLocation.tryParse(spec.removePrefix("#"))?.let { Spec(it, if (spec.startsWith("#")) TagKey.create(registry, it) else null) }
        })
    }

    fun block(p: ServerPlayer, pos: BlockPos, action: Action, state: BlockState) {
        val dim = dimOf(p.level())
        if (action == Action.PLACE) Placed.mark(dim, pos, p.stringUUID)
        if (!earnsCredit(p)) return
        val cl = Realm.at(dim, pos.x shr 4, pos.z shr 4) ?: return
        val me = p.stringUUID
        if (repeated(dim, pos, action, state)) return
        val c = Realm.data.countries[cl.country] ?: return
        if (action == Action.BREAK && !mature(state)) return
        val ledger = ledgerOf(c)
        Work.held(c, me, cl).forEach { (job, spec) ->
            val i = spec.tasks.indexOfFirst { blockKinds[it.kind] == action && blockMatches(it, state) }
            if (i >= 0 && grant(ledger, me, spec, i, 1) > 0) {
                job.progress++
                if (spec.tasks[i].kind in inputKinds) ledger.inputs.merge(me, 1.0, Double::plus)
            }
        }
    }

    fun made(p: ServerPlayer, kind: TaskKind, itemId: String, count: Int) {
        val me = p.stringUUID
        val (ledger, held) = heldAt(p, count) ?: return
        held.forEach { (job, spec) ->
            val i = spec.tasks.indexOfFirst { it.kind == kind && itemMatches(it, itemId) }
            if (i < 0) return@forEach
            val granted = grant(ledger, me, spec, i, CraftRatios.allowed(itemId, count, ledger.inputs[me] ?: 0.0))
            if (granted <= 0) return@forEach
            CraftRatios.cost(itemId, granted)?.let { cost -> ledger.inputs.merge(me, -cost, Double::plus) }
            job.progress += granted
        }
    }

    fun acted(p: ServerPlayer, kind: TaskKind, count: Int) {
        val me = p.stringUUID
        val (ledger, held) = heldAt(p, count) ?: return
        held.forEach { (job, spec) ->
            val i = spec.tasks.indexOfFirst { it.kind == kind }
            if (i >= 0) job.progress += grant(ledger, me, spec, i, count)
        }
    }

    private fun heldAt(p: ServerPlayer, count: Int): Pair<CreditLedger, List<Pair<Job, JobSpec>>>? {
        if (!earnsCredit(p) || count <= 0) return null
        val cl = Realm.at(dimOf(p.level()), p.blockPosition().x shr 4, p.blockPosition().z shr 4) ?: return null
        val c = Realm.data.countries[cl.country] ?: return null
        return ledgerOf(c) to Work.held(c, p.stringUUID, cl)
    }

    private fun earnsCredit(p: ServerPlayer) = p !is FakePlayer && !p.isCreative && !p.isSpectator

    private fun cropLike(block: Block) = block is CropBlock || block is NetherWartBlock || block is CocoaBlock || block is SweetBerryBushBlock || block is PitcherCropBlock

    private fun mature(state: BlockState): Boolean {
        if (!cropLike(state.block)) return true
        val age = state.properties.firstOrNull { it.name == "age" } as? IntegerProperty ?: return true
        return state.getValue(age) == age.possibleValues.max()
    }

    private fun repeated(dim: String, pos: BlockPos, action: Action, state: BlockState): Boolean {
        if (action != Action.PLACE) return Placed.contains(dim, pos) && !cropLike(state.block)
        val spot = dim to pos.asLong()
        return paidPlaces.remove(spot).also { paidPlaces.add(spot) }
    }

    private fun <T> HashMap<String, T>.bounded(key: String, make: () -> T): T {
        if (key !in this && size >= TAG_CACHE) clear()
        return getOrPut(key, make)
    }

    private fun ledgerOf(c: Country): CreditLedger {
        val ledger = c.creditLedger
        if (ledger.day != today()) {
            ledger.day = today()
            ledger.used.clear()
            ledger.inputs.clear()
        }
        return ledger
    }

    private fun grant(ledger: CreditLedger, me: String, spec: JobSpec, index: Int, want: Int): Int {
        val cap = spec.tasks[index].cap.takeIf { it > 0 } ?: JobRules.MAX_TASK_CAP
        val key = "$me|${spec.id}|$index"
        val granted = minOf(want, cap - (ledger.used[key] ?: 0)).coerceAtLeast(0)
        if (granted > 0) ledger.used.merge(key, granted, Int::plus)
        return granted
    }

    private fun blockMatches(task: TaskSpec, state: BlockState): Boolean {
        val raw = task.filter.ifBlank { blankBlockFilters[task.kind].orEmpty() }
        val filter = blockFilters.bounded(raw) { parse(raw, Registries.BLOCK) }
        if (filter.blank) return task.kind == TaskKind.BUILD
        return filter.specs.any { spec -> spec.tag?.let { state.`is`(it) } ?: (BuiltInRegistries.BLOCK.getKey(state.block) == spec.id) }
    }

    private fun itemMatches(task: TaskSpec, itemId: String): Boolean {
        val filter = itemFilters.bounded(task.filter) { parse(task.filter, Registries.ITEM) }
        if (filter.blank) return true
        val item = ResourceLocation.tryParse(itemId) ?: return false
        return filter.specs.any { spec -> spec.tag?.let { BuiltInRegistries.ITEM.get(item).builtInRegistryHolder().`is`(it) } ?: (spec.id == item) }
    }
}

object CraftRatios {
    private val exact = mapOf("stick" to 8.0, "charcoal" to 1.0, "bread" to 0.34, "glass" to 1.0, "brick" to 1.0)
    private val suffixed = listOf("_planks" to 4.0, "_slab" to 8.0, "_stairs" to 4.0, "_ingot" to 1.0)

    private fun ratio(itemId: String): Double? {
        val name = itemId.substringAfter(':')
        return exact[name] ?: suffixed.firstOrNull { name.endsWith(it.first) }?.second ?: 1.0.takeIf { name.startsWith("cooked_") }
    }

    fun allowed(itemId: String, count: Int, credit: Double): Int {
        val r = ratio(itemId) ?: return count
        return minOf(count.toDouble(), floor(credit * r)).toInt()
    }

    fun cost(itemId: String, count: Int): Double? = ratio(itemId)?.let { count / it }
}
