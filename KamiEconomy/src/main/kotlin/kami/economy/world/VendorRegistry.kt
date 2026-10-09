package kami.economy.world

import kami.economy.LOG
import kami.economy.Market
import kami.economy.Vendor
import kami.economy.now
import kami.libs.claims.ClaimsApi
import net.minecraft.core.BlockPos
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.core.registries.Registries
import net.minecraft.resources.ResourceKey
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.LevelAccessor
import net.minecraft.world.level.block.entity.BlockEntity
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

object VendorRegistry {
    private val BLOCK_IDS = listOf("numismatics:vendor", "numismatics:creative_vendor")
    private val blocks by lazy { BLOCK_IDS.mapNotNull { BuiltInRegistries.BLOCK.getOptional(ResourceLocation.parse(it)).orElse(null) }.toSet() }
    private data class Key(val dim: String, val x: Int, val y: Int, val z: Int)
    private val index = HashMap<Key, Vendor>()
    private var indexed: List<Vendor>? = null
    private var indexedSize = -1
    private val pending = LinkedHashSet<Pair<ServerLevel, BlockPos>>()
    private val type = runCatching { Class.forName("dev.ithundxr.createnumismatics.content.vendor.VendorBlockEntity") }
        .onFailure { LOG.warn("Numismatics vendor class not found, vendor registry is off") }.getOrNull()
    private val ownerField = type?.let { t -> runCatching { t.getDeclaredField("owner").apply { isAccessible = true } }.getOrNull() }

    private val methods = object : ClassValue<ConcurrentHashMap<String, Method>>() {
        override fun computeValue(type: Class<*>) = ConcurrentHashMap<String, Method>()
    }
    private val itemsFields = object : ClassValue<Field>() {
        override fun computeValue(type: Class<*>): Field = type.getField("items")
    }

    private fun call(be: BlockEntity, name: String, vararg args: Any): Any? =
        methods.get(be.javaClass).getOrPut(name) { be.javaClass.getMethod(name, *args.map { if (it is ItemStack) ItemStack::class.java else it.javaClass }.toTypedArray()) }.invoke(be, *args)

    fun isVendor(level: LevelAccessor, pos: BlockPos): Boolean {
        val state = level.getBlockState(pos)
        return state.block in blocks || state.hasBlockEntity() && type?.isInstance(level.getBlockEntity(pos)) == true
    }

    fun touch(level: LevelAccessor, pos: BlockPos) {
        if (level is ServerLevel && isVendor(level, pos)) pending += level to pos.immutable()
    }

    fun drain() {
        if (pending.isEmpty()) return
        val batch = pending.toList()
        pending.clear()
        batch.forEach { (level, pos) -> refresh(level, pos) }
    }

    fun recheck(server: MinecraftServer) {
        Market.data.vendors.toList().forEach { v ->
            val level = server.getLevel(ResourceKey.create(Registries.DIMENSION, ResourceLocation.parse(v.dim))) ?: return@forEach
            val pos = BlockPos(v.x, v.y, v.z)
            if (level.isLoaded(pos)) refresh(level, pos)
        }
    }

    private fun dim(level: LevelAccessor) = (level as? ServerLevel)?.dimension()?.location()?.toString() ?: ""

    private fun index(): HashMap<Key, Vendor> {
        val list = Market.data.vendors
        if (indexed !== list || indexedSize != list.size) {
            index.clear()
            list.forEach { index[Key(it.dim, it.x, it.y, it.z)] = it }
            indexed = list
            indexedSize = list.size
        }
        return index
    }

    private fun find(level: LevelAccessor, pos: BlockPos) = index()[Key(dim(level), pos.x, pos.y, pos.z)]

    private fun refresh(level: ServerLevel, pos: BlockPos) {
        val be = level.getBlockEntity(pos)?.takeIf { type?.isInstance(it) == true }
        val claim = ClaimsApi.at(dim(level), pos.x shr 4, pos.z shr 4)?.takeIf { it.type == "market" }
        val known = find(level, pos)
        if (be == null || claim == null) {
            if (known != null) { Market.data.vendors.remove(known); index.remove(Key(known.dim, known.x, known.y, known.z)); indexedSize = Market.data.vendors.size; Market.dirty = true }
            return
        }
        val v = known ?: Vendor(dim(level), pos.x, pos.y, pos.z).also { Market.data.vendors += it; index[Key(it.dim, it.x, it.y, it.z)] = it; indexedSize = Market.data.vendors.size }
        runCatching { read(be, v, claim.country) }.onFailure { LOG.warn("Could not read vendor at {}: {}", pos, it.message) }
        Market.dirty = true
    }

    private fun read(be: BlockEntity, v: Vendor, country: String) {
        val filter = call(be, "getFilterItem") as ItemStack
        v.country = country
        v.owner = (ownerField?.get(be) as? UUID)?.toString() ?: ""
        v.item = if (filter.isEmpty || call(be, "filterActsSpecial") == true) "" else BuiltInRegistries.ITEM.getKey(filter.item).toString()
        v.count = filter.count.coerceAtLeast(1)
        v.price = call(be, "getTotalPrice") as Int
        v.sell = (call(be, "getMode") as Enum<*>).name == "SELL"
        val items = itemsFields.get(be.javaClass).get(be) as List<*>
        v.stock = if (!v.sell || call(be, "isCreativeVendor") == true || v.item.isEmpty()) -1
            else items.filterIsInstance<ItemStack>().filter { !it.isEmpty && call(be, "matchesFilterItem", it) == true }.sumOf { it.count }
        v.seen = now()
    }
}
