package kami.essentials.display

import kami.essentials.Config
import kami.essentials.chat.Names
import kami.essentials.vanish.Vanish
import kami.libs.chat.Theme
import net.minecraft.ChatFormatting
import net.minecraft.network.chat.Component
import net.minecraft.network.protocol.game.ClientboundTabListPacket
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerPlayer
import net.neoforged.neoforge.event.entity.player.PlayerEvent

object Tab {
    private var shown = false

    fun onName(e: PlayerEvent.TabListNameFormat) {
        if (Config.s.tab) (e.entity as? ServerPlayer)?.let { e.displayName = Names.player(it, hover = false) }
    }

    fun tick(server: MinecraftServer) {
        val on = Config.s.tab
        server.playerList.players.forEach { p ->
            p.refreshTabListName()
            if (on) p.connection.send(ClientboundTabListPacket(header(), footer(server, p)))
            else if (shown) p.connection.send(ClientboundTabListPacket(Component.empty(), Component.empty()))
        }
        shown = on
    }

    fun online(server: MinecraftServer, viewer: ServerPlayer) = server.playerList.players.count { !Vanish.hides(it, viewer) }

    fun tps(server: MinecraftServer) = minOf(20.0, 1e9 / server.averageTickTimeNanos.coerceAtLeast(1))

    private fun header(): Component = Component.literal("\n  ${Config.s.tabTitle}  \n").withColor(Theme.ACCENT).withStyle(ChatFormatting.BOLD)

    private fun footer(server: MinecraftServer, p: ServerPlayer): Component = Component.literal("\n")
        .append(pair("Online", online(server, p).toString()))
        .append(Component.literal("   ·   ").withColor(Theme.MUTED))
        .append(pair("Ping", "${p.connection.latency()} ms"))
        .append(Component.literal("   ·   ").withColor(Theme.MUTED))
        .append(pair("TPS", "%.1f".format(tps(server))))
        .append("\n")

    private fun pair(label: String, value: String) =
        Component.literal("$label ").withColor(Theme.MUTED).append(Component.literal(value).withColor(Theme.VALUE))
}
