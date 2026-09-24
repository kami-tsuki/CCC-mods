package kami.geology.item

import kami.geology.map.Heatmap
import kami.geology.net.MapServer
import kami.geology.world.Prospector
import kami.geology.world.Worlds
import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.InteractionHand
import net.minecraft.world.InteractionResultHolder
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.Item
import net.minecraft.world.item.Item.Properties
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.Level

class ProspectorItem(val tier: Int, props: Properties) : Item(props) {
    override fun use(level: Level, player: Player, hand: InteractionHand): InteractionResultHolder<ItemStack> {
        val stack = player.getItemInHand(hand)
        if (hand == InteractionHand.MAIN_HAND && player is ServerPlayer) {
            val world = Worlds.of(player.serverLevel())
            val sample = player.offhandItem
            val ore = world?.let { Prospector.oreOf(it, sample) }
            when {
                world == null -> player.displayClientMessage(Component.literal("No geology in this dimension"), true)
                ore == null -> player.displayClientMessage(Component.literal("Hold a sample of the ore in your other hand"), true)
                tier == 1 -> player.displayClientMessage(
                    Component.literal(Heatmap.probeOre(world, ore, player.blockX, player.blockZ, level.minBuildHeight, level.maxBuildHeight - 1)),
                    false
                )
                else -> MapServer.scan(player, ore, tier)
            }
        }
        return InteractionResultHolder.sidedSuccess(stack, level.isClientSide)
    }
}
