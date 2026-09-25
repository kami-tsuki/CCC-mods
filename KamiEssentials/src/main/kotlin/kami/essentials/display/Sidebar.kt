package kami.essentials.display

import kami.essentials.Config
import kami.essentials.Flag
import kami.essentials.Store
import kami.essentials.chat.Names
import kami.libs.chat.Theme
import kami.libs.economy.Coins
import kami.libs.economy.Numismatics
import net.minecraft.ChatFormatting
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.numbers.BlankFormat
import net.minecraft.network.protocol.game.ClientboundResetScorePacket
import net.minecraft.network.protocol.game.ClientboundSetDisplayObjectivePacket
import net.minecraft.network.protocol.game.ClientboundSetObjectivePacket
import net.minecraft.network.protocol.game.ClientboundSetScorePacket
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerPlayer
import net.minecraft.stats.Stats
import net.minecraft.world.scores.DisplaySlot
import net.minecraft.world.scores.Objective
import net.minecraft.world.scores.Scoreboard
import net.minecraft.world.scores.criteria.ObjectiveCriteria
import java.util.Optional
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

object Sidebar {
    private const val NAME = "kami_sidebar"

    val LINES: Map<String, (ServerPlayer) -> Component> = linkedMapOf(
        "country" to { p -> Names.citizenship(p.uuid)?.let { Component.literal(it.name).withColor(Names.color(it)) } ?: none() },
        "rank" to { p -> Names.citizenship(p.uuid)?.let { value(Names.title(it.rank)) } ?: none() },
        "balance" to { p -> value(Coins.compact(Numismatics.balance(p.uuid))) },
        "playtime" to { p -> value(Names.playtime(p)) },
        "kills" to { p -> value("%,d".format(Names.stat(p, Stats.PLAYER_KILLS))) },
        "deaths" to { p -> value("%,d".format(Names.stat(p, Stats.DEATHS))) },
        "online" to { p -> value(Tab.online(p.server, p).toString()) },
        "ping" to { p -> value("${p.connection.latency()} ms") },
        "tps" to { p -> value("%.1f".format(Tab.tps(p.server))) },
    )

    private class View(val title: String, val lines: MutableList<Component> = mutableListOf())

    private val views = ConcurrentHashMap<UUID, View>()
    private val board = Scoreboard()

    fun enabled(p: ServerPlayer) = Config.s.sidebar && !Store[Flag.NO_SIDEBAR, p.uuid]

    fun set(p: ServerPlayer, show: Boolean) {
        Store[Flag.NO_SIDEBAR, p.uuid] = !show
        if (!show) hide(p)
    }

    fun tick(server: MinecraftServer) = server.playerList.players.forEach { p -> if (enabled(p)) draw(p) else hide(p) }

    fun forget(p: ServerPlayer) {
        views.remove(p.uuid)
    }

    private fun draw(p: ServerPlayer) {
        val title = Config.s.sidebarTitle
        val view = views[p.uuid]
        if (view == null || view.title != title) {
            p.connection.send(ClientboundSetObjectivePacket(objective(title), if (view == null) ClientboundSetObjectivePacket.METHOD_ADD else ClientboundSetObjectivePacket.METHOD_CHANGE))
            p.connection.send(ClientboundSetDisplayObjectivePacket(DisplaySlot.SIDEBAR, objective(title)))
        } else if (p.server.scoreboard.getDisplayObjective(DisplaySlot.SIDEBAR) != null) {
            p.connection.send(ClientboundSetDisplayObjectivePacket(DisplaySlot.SIDEBAR, objective(title)))
        }
        val next = View(title, view?.lines ?: mutableListOf())
        views[p.uuid] = next
        val lines = Config.s.sidebarLines.map { key -> LINES[key]?.let { line(key, it(p)) } ?: Component.empty() }
        lines.forEachIndexed { i, line ->
            if (next.lines.getOrNull(i) != line) p.connection.send(ClientboundSetScorePacket("$i", NAME, lines.size - i, Optional.of(line), Optional.empty()))
        }
        (lines.size until next.lines.size).forEach { p.connection.send(ClientboundResetScorePacket("$it", NAME)) }
        next.lines.clear()
        next.lines.addAll(lines)
    }

    private fun hide(p: ServerPlayer) {
        val view = views.remove(p.uuid) ?: return
        p.connection.send(ClientboundSetObjectivePacket(objective(view.title), ClientboundSetObjectivePacket.METHOD_REMOVE))
        p.server.scoreboard.getDisplayObjective(DisplaySlot.SIDEBAR)?.let { p.connection.send(ClientboundSetDisplayObjectivePacket(DisplaySlot.SIDEBAR, it)) }
    }

    private fun objective(title: String) = Objective(
        board, NAME, ObjectiveCriteria.DUMMY, Component.literal(title).withColor(Theme.ACCENT).withStyle(ChatFormatting.BOLD),
        ObjectiveCriteria.RenderType.INTEGER, false, BlankFormat.INSTANCE
    )

    private fun line(key: String, value: Component): Component =
        Component.literal("${if (key == "tps") "TPS" else key.replaceFirstChar(Char::uppercase)} ").withColor(Theme.MUTED).append(value)

    private fun value(text: String): Component = Component.literal(text).withColor(Theme.VALUE)

    private fun none(): Component = Component.literal("none").withColor(Theme.MUTED)
}
