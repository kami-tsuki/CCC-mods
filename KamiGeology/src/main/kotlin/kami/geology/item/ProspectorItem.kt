package kami.geology.item

import kami.geology.map.Heatmap
import kami.geology.map.MapColors
import kami.geology.net.MapServer
import kami.geology.world.Worlds
import net.minecraft.ChatFormatting
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
            when {
                world == null -> player.displayClientMessage(Component.literal("No geology in this dimension").withStyle(ChatFormatting.GRAY), true)
                tier == 1 -> player.displayClientMessage(
                    result(Heatmap.probeColumn(world, player.blockX, player.blockZ, level.minBuildHeight, level.maxBuildHeight - 1)),
                    false
                )
                else -> MapServer.scan(player, tier)
            }
        }
        return InteractionResultHolder.sidedSuccess(stack, level.isClientSide)
    }

    private fun result(found: List<Pair<String, String>>): Component {
        val message = Component.literal("Prospector: ").withStyle(ChatFormatting.GOLD)
        if (found.isEmpty()) return message.append(Component.literal("nothing found here").withStyle(ChatFormatting.GRAY))
        found.forEachIndexed { i, (ore, size) ->
            if (i > 0) message.append(Component.literal(", ").withStyle(ChatFormatting.DARK_GRAY))
            message.append(Component.literal(ore.split('_').joinToString(" ") { it.replaceFirstChar(Char::uppercase) }).withColor(MapColors.ore(ore)))
            message.append(Component.literal(" ($size)").withStyle(ChatFormatting.GRAY))
        }
        return message
    }
}
