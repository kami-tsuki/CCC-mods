package kami.essentials.vanish

import kami.libs.text.Phrase
import kami.essentials.Flag
import kami.essentials.Perms
import kami.essentials.Store
import kami.essentials.chat.Feed
import kami.essentials.chat.Talk
import kami.essentials.discord.Bot
import kami.essentials.mixin.ChunkMapAccess
import kami.essentials.mixin.TrackedEntityAccess
import kami.libs.chat.Chat
import kami.libs.chat.Tone
import kami.libs.chat.bar
import kami.libs.chat.tell
import net.minecraft.network.protocol.game.ClientboundPlayerInfoRemovePacket
import net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.entity.player.Player

object Vanish {
    fun active(p: Player) = Store[Flag.INVISIBLE, p.uuid]

    fun sees(viewer: ServerPlayer) = Perms.has(viewer, Perms.INVIS_SEE)

    fun hides(target: Player, viewer: ServerPlayer) = target !== viewer && active(target) && !sees(viewer)

    fun filter(viewer: ServerPlayer, packet: ClientboundPlayerInfoUpdatePacket): ClientboundPlayerInfoUpdatePacket? {
        val hidden = Store.ids(Flag.INVISIBLE)
        if (hidden.isEmpty() || packet.entries().none { it.profileId != viewer.uuid && it.profileId in hidden } || sees(viewer)) return packet
        val shown = packet.entries().mapNotNull { e -> viewer.server.playerList.getPlayer(e.profileId)?.takeUnless { hides(it, viewer) } }
        return if (shown.isEmpty()) null else ClientboundPlayerInfoUpdatePacket(packet.actions(), shown)
    }

    fun toggle(p: ServerPlayer, by: ServerPlayer?) {
        val on = !active(p)
        Store[Flag.INVISIBLE, p.uuid] = on
        p.server.playerList.players.filter { it !== p && !sees(it) }.forEach {
            it.connection.send(if (on) ClientboundPlayerInfoRemovePacket(listOf(p.uuid)) else ClientboundPlayerInfoUpdatePacket.createPlayerInitializing(listOf(p)))
        }
        retrack(p)
        Bot.presenceDirty()
        if (on) Feed.leave(p, Phrase.of("kami_essentials.vanish.now_invisible")) else Feed.join(p, Phrase.of("kami_essentials.vanish.visible_again"))
        p.tell(Talk.chat.ok(Phrase.of(if (on) "kami_essentials.vanish.on" else "kami_essentials.vanish.off")))
        if (by != null && by !== p) by.tell(Talk.chat.ok(Phrase.of(if (on) "kami_essentials.vanish.other.on" else "kami_essentials.vanish.other.off", Phrase.value(p.gameProfile.name))))
    }

    fun tick(server: MinecraftServer) = server.playerList.players.filter(::active).forEach { it.bar(Chat.bar(Tone.INFO, Phrase.of("kami_essentials.vanish.bar"))) }

    private fun retrack(p: ServerPlayer) {
        val level = p.serverLevel()
        val tracked = (level.chunkSource.chunkMap as ChunkMapAccess).`kami$entities`()[p.id] as? TrackedEntityAccess ?: return
        level.players().forEach(tracked::`kami$updatePlayer`)
    }
}
