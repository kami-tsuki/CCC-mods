package kami.essentials.vanish

import kami.essentials.Flag
import kami.essentials.Perms
import kami.essentials.Store
import kami.essentials.chat.Feed
import kami.essentials.chat.Talk
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
        if (on) Feed.leave(p, "now invisible") else Feed.join(p, "visible again")
        p.tell(if (on) Talk.chat.ok("You are invisible. Others saw you leave.") else Talk.chat.ok("You are visible again. Others saw you join."))
        if (by != null && by !== p) by.tell(Talk.chat.ok("{${p.gameProfile.name}} is ${if (on) "invisible" else "visible"} now."))
    }

    fun tick(server: MinecraftServer) = server.playerList.players.filter(::active).forEach { it.bar(Chat.bar(Tone.INFO, "You are {invisible}")) }

    private fun retrack(p: ServerPlayer) {
        val level = p.serverLevel()
        val tracked = (level.chunkSource.chunkMap as ChunkMapAccess).`kami$entities`()[p.id] as? TrackedEntityAccess ?: return
        level.players().forEach(tracked::`kami$updatePlayer`)
    }
}
