package kami.essentials.display

import kami.libs.text.Text
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
import java.util.Locale
import java.util.UUID

object Tab {
    private var shown = false
    private var countTick = -1
    private var hidden = 0
    private val names = HashMap<UUID, Component>()
    private val footers = HashMap<UUID, Pair<Component, Component>>()

    fun forget(id: UUID) {
        names.remove(id)
        footers.remove(id)
    }

    fun onName(e: PlayerEvent.TabListNameFormat) {
        if (Config.s.tab) (e.entity as? ServerPlayer)?.let { e.displayName = Names.player(it, hover = false) }
    }

    fun tick(server: MinecraftServer) {
        val on = Config.s.tab
        val players = server.playerList.players
        val ids = players.mapTo(HashSet()) { it.uuid }
        names.keys.retainAll(ids)
        footers.keys.retainAll(ids)
        val header = header()
        players.forEach { p ->
            val name = if (on) Names.player(p, hover = false) else Component.empty()
            if (names.put(p.uuid, name) != name) p.refreshTabListName()
            if (on) {
                val next = header to footer(server, p)
                if (footers.put(p.uuid, next) != next) p.connection.send(ClientboundTabListPacket(next.first, next.second))
            } else if (shown) p.connection.send(ClientboundTabListPacket(Component.empty(), Component.empty()))
        }
        if (!on) footers.clear()
        shown = on
    }

    fun online(server: MinecraftServer, viewer: ServerPlayer): Int {
        val players = server.playerList.players
        if (countTick != server.tickCount) {
            hidden = players.count(Vanish::active)
            countTick = server.tickCount
        }
        return if (Vanish.sees(viewer)) players.size else players.size - hidden + (if (Vanish.active(viewer)) 1 else 0)
    }

    fun tps(server: MinecraftServer) = minOf(20.0, 1e9 / server.averageTickTimeNanos.coerceAtLeast(1))

    fun tpsText(server: MinecraftServer): String = String.format(Locale.ROOT, "%.1f", tps(server))

    private fun header(): Component = Component.literal("\n  ${Config.s.tabTitle}  \n").withColor(Theme.ACCENT).withStyle(ChatFormatting.BOLD)

    private fun footer(server: MinecraftServer, p: ServerPlayer): Component = Component.literal("\n")
        .append(pair("kami_libs.common.online", online(server, p).toString()))
        .append(Component.literal("   ·   ").withColor(Theme.MUTED))
        .append(pair("kami_essentials.sidebar.ping", "${p.connection.latency()} ms"))
        .append(Component.literal("   ·   ").withColor(Theme.MUTED))
        .append(pair("kami_essentials.sidebar.tps", tpsText(server)))
        .append("\n")

    private fun pair(key: String, value: String) =
        Component.empty().append(Text.msg(key).withColor(Theme.MUTED)).append(" ").append(Component.literal(value).withColor(Theme.VALUE))
}
