package kami.claims.research

import kami.claims.Realm
import kami.libs.progress.ProgressEvent
import kami.claims.world.Placed
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.Level
import net.neoforged.bus.api.EventPriority
import net.neoforged.neoforge.common.util.FakePlayer
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent
import net.neoforged.neoforge.event.entity.player.PlayerDestroyItemEvent
import net.neoforged.neoforge.event.entity.player.PlayerEnchantItemEvent
import net.neoforged.neoforge.event.entity.player.PlayerEvent
import net.neoforged.neoforge.event.level.BlockEvent
import net.neoforged.neoforge.event.level.BlockGrowFeatureEvent
import net.neoforged.neoforge.event.tick.ServerTickEvent
import thedarkcolour.kotlinforforge.neoforge.forge.FORGE_BUS

object Listeners {
    private fun report(entity: Any?, kind: String, subject: String, amount: Long = 1) {
        val player = entity as? ServerPlayer ?: return
        if (player is FakePlayer) return
        Progress.report(player, kind, subject, amount)
    }

    private fun countable(entity: Any?) = (entity as? ServerPlayer)?.let { !it.isCreative && !it.isSpectator } == true

    private fun id(stack: ItemStack) = BuiltInRegistries.ITEM.getKey(stack.item).toString()

    private fun treeGrown(event: BlockGrowFeatureEvent) {
        val level = event.level as? Level ?: return
        val claim = Realm.at(level.dimension().location().toString(), event.pos.x shr 4, event.pos.z shr 4) ?: return
        if (claim.type != "forestry") return
        Realm.country(claim.country)?.let { Counters.add(it, Counters.TREES_GROWN) }
    }

    fun register() {
        FORGE_BUS.addListener<ProgressEvent> { Progress.onEvent(it) }
        FORGE_BUS.addListener<ServerTickEvent.Post> { Buffs.tick(it.server) }
        FORGE_BUS.addListener<PlayerEvent.PlayerLoggedOutEvent> { Buffs.forget(it.entity.uuid) }
        FORGE_BUS.addListener<BlockEvent.BreakEvent>(EventPriority.LOWEST) {
            val wasPlaced = Placed.remove(it.player.level().dimension().location().toString(), it.pos)
            if (countable(it.player) && !wasPlaced) report(it.player, Kinds.MINE, BuiltInRegistries.BLOCK.getKey(it.state.block).toString())
        }
        FORGE_BUS.addListener<BlockEvent.EntityPlaceEvent>(EventPriority.LOWEST) {
            val player = it.entity as? ServerPlayer ?: return@addListener
            if (!countable(player)) return@addListener
            Placed.mark(player.level().dimension().location().toString(), it.pos)
            report(player, Kinds.PLACE, BuiltInRegistries.BLOCK.getKey(it.placedBlock.block).toString())
        }
        FORGE_BUS.addListener<LivingDeathEvent>(EventPriority.LOWEST) {
            if (countable(it.source.entity)) report(it.source.entity, Kinds.KILL, BuiltInRegistries.ENTITY_TYPE.getKey(it.entity.type).toString())
        }
        FORGE_BUS.addListener<BlockGrowFeatureEvent>(EventPriority.LOWEST) { treeGrown(it) }
        FORGE_BUS.addListener<PlayerEvent.ItemCraftedEvent> { report(it.entity, Kinds.CRAFT, id(it.crafting), it.crafting.count.toLong()) }
        FORGE_BUS.addListener<PlayerEvent.ItemSmeltedEvent> { report(it.entity, Kinds.SMELT, id(it.smelting), it.smelting.count.toLong()) }
        FORGE_BUS.addListener<PlayerEnchantItemEvent> { report(it.entity, Kinds.ENCHANT, "") }
        FORGE_BUS.addListener<PlayerDestroyItemEvent> { report(it.entity, Kinds.BREAK_ITEM, id(it.original)) }
        FORGE_BUS.addListener<PlayerEvent.PlayerChangedDimensionEvent> { event ->
            (event.entity as? ServerPlayer)?.let { Progress.visited(it, event.to.location().toString()) }
        }
    }
}
