package kami.geology.item

import kami.libs.chat.Theme
import kami.libs.text.Phrase
import kami.geology.KamiGeology
import kami.geology.command.GeoText
import kami.geology.command.GeoText.ore
import kami.geology.map.Heatmap
import kami.geology.net.MapServer
import kami.geology.world.Discovery
import kami.geology.world.Site
import kami.geology.world.Worlds
import kami.libs.claims.ClaimsApi
import kami.libs.claims.FeatureIds
import kami.libs.chat.Chat
import kami.libs.chat.Tone
import kami.libs.chat.bar
import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerPlayer
import net.minecraft.sounds.SoundEvents
import net.minecraft.sounds.SoundSource
import net.minecraft.world.InteractionHand
import net.minecraft.world.InteractionResultHolder
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.Item
import net.minecraft.world.item.Item.Properties
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.Level

class ProspectorItem(val tier: Int, props: Properties) : Item(props) {
    private companion object {
        const val COOLDOWN_TICKS_ONE_SECOND = 20
    }

    override fun use(level: Level, player: Player, hand: InteractionHand): InteractionResultHolder<ItemStack> {
        val stack = player.getItemInHand(hand)
        if (hand == InteractionHand.MAIN_HAND && player is ServerPlayer) {
            if (player.cooldowns.isOnCooldown(this)) return InteractionResultHolder.fail(stack)

            val world = Worlds.of(player.serverLevel())
            when {
                world == null -> player.bar(Chat.bar(Tone.WARN, Phrase.of("kami_geology.prospector.no_deposits").component()))
                tier == 1 -> {
                    val found = Heatmap.probeColumn(world, player.blockX, player.blockZ, level.minBuildHeight, level.maxBuildHeight - 1)
                    Discovery.report(player, tier, found)
                    player.bar(result(player, found))
                    if (found.isNotEmpty()) {
                        player.playNotifySound(SoundEvents.NOTE_BLOCK_CHIME.value(), SoundSource.PLAYERS, 0.7f, 1.2f)
                    }
                }
                else -> MapServer.scan(player, tier)
            }

            KamiGeology.PROSPECTORS_BY_TIER.forEach { player.cooldowns.addCooldown(it.get(), COOLDOWN_TICKS_ONE_SECOND) }
        }
        return InteractionResultHolder.sidedSuccess(stack, level.isClientSide)
    }

    private fun result(player: ServerPlayer, found: List<Site>): Component = GeoText.chat.msg {
        val grades = ClaimsApi.lock(player.uuid, FeatureIds.GRADES) == null
        if (found.isEmpty()) add(Phrase.of("kami_geology.prospector.nothing"), Theme.MUTED)
        found.forEachIndexed { i, site ->
            if (i > 0) muted(", ")
            ore(site.ore.id)
            muted(" ")
            add(GeoText.tier(site.tier.name), Theme.MUTED)
            if (grades) {
                muted(" ")
                add(GeoText.grade(site.grade.name), Theme.MUTED)
            }
        }
        muted("  ")
        pos(player.blockX, player.blockY, player.blockZ, player.level().dimension().location().toString())
    }
}
