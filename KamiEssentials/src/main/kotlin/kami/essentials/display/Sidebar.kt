package kami.essentials.display

import kami.libs.text.Text
import kami.essentials.Config
import kami.essentials.Flag
import kami.essentials.Store
import kami.essentials.chat.Names
import kami.libs.chat.Theme
import kami.libs.claims.ClaimsApi
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
import java.util.Locale
import java.util.Optional
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

object Sidebar {
    private const val NAME = "kami_sidebar"

    val LINES: Map<String, (ServerPlayer) -> Component> = linkedMapOf(
        "country" to { p -> Names.citizenship(p.uuid)?.let { Component.literal(it.name).withColor(Names.color(it)) } ?: none() },
        "rank" to { p -> Names.citizenship(p.uuid)?.let { Names.title(it.rank).withColor(Theme.VALUE) } ?: none() },
        "level" to { p -> ClaimsApi.level(p.uuid)?.let { value(it.toString()) } ?: none() },
        "goal" to { p -> ClaimsApi.goals(p.uuid).firstOrNull()?.let { it.text.component().withColor(Theme.VALUE) } ?: none() },
        "balance" to { p -> value(Coins.compact(Numismatics.balance(p.uuid))) },
        "playtime" to { p -> Names.playtime(p).component().withColor(Theme.VALUE) },
        "kills" to { p -> value(String.format(Locale.ROOT, "%,d", Names.stat(p, Stats.PLAYER_KILLS))) },
        "deaths" to { p -> value(String.format(Locale.ROOT, "%,d", Names.stat(p, Stats.DEATHS))) },
        "online" to { p -> value(Tab.online(p.server, p).toString()) },
        "ping" to { p -> value("${p.connection.latency()} ms") },
        "tps" to { p -> value(Tab.tpsText(p.server)) },
    )

    private class View(val title: String, val foreign: Objective?, val keys: List<String>, val values: List<Component?>, val lines: List<Component>)

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
        val foreign = p.server.scoreboard.getDisplayObjective(DisplaySlot.SIDEBAR)
        if (view == null || view.title != title) {
            p.connection.send(ClientboundSetObjectivePacket(objective(title), if (view == null) ClientboundSetObjectivePacket.METHOD_ADD else ClientboundSetObjectivePacket.METHOD_CHANGE))
            p.connection.send(ClientboundSetDisplayObjectivePacket(DisplaySlot.SIDEBAR, objective(title)))
        } else if (view.foreign !== foreign) {
            p.connection.send(ClientboundSetDisplayObjectivePacket(DisplaySlot.SIDEBAR, objective(title)))
        }
        val keys = Config.s.sidebarLines
        val values = arrayOfNulls<Component>(keys.size)
        val lines = keys.mapIndexed { i, key ->
            val provider = LINES[key] ?: return@mapIndexed Component.empty()
            val value = provider(p)
            values[i] = value
            if (view != null && view.keys.getOrNull(i) == key && view.values.getOrNull(i) == value) view.lines[i] else line(key, value)
        }
        val old = view?.lines.orEmpty()
        lines.forEachIndexed { i, line ->
            if (old.size != lines.size || old.getOrNull(i) != line) p.connection.send(ClientboundSetScorePacket("$i", NAME, lines.size - i, Optional.of(line), Optional.empty()))
        }
        (lines.size until old.size).forEach { p.connection.send(ClientboundResetScorePacket("$it", NAME)) }
        views[p.uuid] = View(title, foreign, keys, values.toList(), lines)
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

    private val shared = setOf("online", "rank", "level", "country", "balance", "playtime")

    private fun line(key: String, value: Component): Component =
        Component.empty().append(Text.msg(if (key in shared) "kami_libs.common.$key" else "kami_essentials.sidebar.$key").withColor(Theme.MUTED)).append(" ").append(value)

    private fun value(text: String): Component = Component.literal(text).withColor(Theme.VALUE)

    private fun none(): Component = Text.msg("kami_libs.common.none").withColor(Theme.MUTED)
}
