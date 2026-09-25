package kami.geology.item

import kami.geology.command.GeoText
import kami.geology.command.GeoText.ore
import kami.geology.map.Heatmap
import kami.geology.net.MapServer
import kami.geology.world.Worlds
import kami.libs.chat.Chat
import kami.libs.chat.Tone
import kami.libs.chat.bar
import kami.libs.chat.tell
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
                world == null -> player.bar(Chat.bar(Tone.WARN, "No deposits in this dimension"))
                tier == 1 -> player.tell(result(player, Heatmap.probeColumn(world, player.blockX, player.blockZ, level.minBuildHeight, level.maxBuildHeight - 1)))
                else -> MapServer.scan(player, tier)
            }
        }
        return InteractionResultHolder.sidedSuccess(stack, level.isClientSide)
    }

    private fun result(player: ServerPlayer, found: List<Pair<String, String>>): Component = GeoText.chat.msg {
        if (found.isEmpty()) muted("Nothing below you")
        found.forEachIndexed { i, (id, size) ->
            if (i > 0) muted(", ")
            ore(id)
            muted(" $size")
        }
        muted("  ")
        pos(player.blockX, player.blockY, player.blockZ, player.level().dimension().location().toString())
    }
}
