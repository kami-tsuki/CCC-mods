package kami.economy.world

import kami.economy.LOG
import kami.economy.Market
import kami.economy.Vendor
import kami.economy.compat.NumismaticsVendors
import kami.libs.claims.ClaimsApi
import net.minecraft.core.BlockPos
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.core.registries.Registries
import net.minecraft.resources.ResourceKey
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.level.LevelAccessor

object VendorRegistry {
    private val BLOCK_IDS = listOf("numismatics:vendor", "numismatics:creative_vendor")
    private val blocks by lazy { BLOCK_IDS.mapNotNull { BuiltInRegistries.BLOCK.getOptional(ResourceLocation.parse(it)).orElse(null) }.toSet() }
    private data class Key(val dim: String, val x: Int, val y: Int, val z: Int)
    private val index = HashMap<Key, Vendor>()
    private var indexed: List<Vendor>? = null
    private var indexedSize = -1
    private val pending = LinkedHashSet<Pair<ServerLevel, BlockPos>>()
    private var warned = false

    fun isVendor(level: LevelAccessor, pos: BlockPos): Boolean {
        val state = level.getBlockState(pos)
        return state.block in blocks || state.hasBlockEntity() && NumismaticsVendors.isVendor(level.getBlockEntity(pos))
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

    fun at(dim: String, x: Int, y: Int, z: Int) = index()[Key(dim, x, y, z)]

    private fun find(level: LevelAccessor, pos: BlockPos) = at(dim(level), pos.x, pos.y, pos.z)

    private fun refresh(level: ServerLevel, pos: BlockPos) {
        val be = level.getBlockEntity(pos)?.takeIf(NumismaticsVendors::isVendor)
        val claim = ClaimsApi.at(dim(level), pos.x shr 4, pos.z shr 4)?.takeIf { it.type == "market" }
        val known = find(level, pos)
        if (be == null || claim == null) {
            if (known != null) { Market.data.vendors.remove(known); index.remove(Key(known.dim, known.x, known.y, known.z)); indexedSize = Market.data.vendors.size; Market.dirty = true }
            return
        }
        val v = known ?: Vendor(dim(level), pos.x, pos.y, pos.z)
        runCatching { NumismaticsVendors.read(be, v, claim.country) }.onFailure {
            if (!warned) { warned = true; LOG.warn("Could not read vendor at {}: {}", pos, it.message) }
            return
        }
        if (known == null) { Market.data.vendors += v; index[Key(v.dim, v.x, v.y, v.z)] = v; indexedSize = Market.data.vendors.size }
        Market.dirty = true
    }
}
