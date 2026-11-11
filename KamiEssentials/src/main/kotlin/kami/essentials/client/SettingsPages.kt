package kami.essentials.client

import kami.libs.ui.app.Callout
import kami.libs.ui.app.Consequence
import kami.libs.ui.app.Page
import kami.libs.ui.app.Route
import kami.libs.ui.app.confirmDialog
import kami.libs.ui.core.Flow
import kami.libs.ui.core.Rect
import kami.libs.ui.core.Ui
import kami.libs.ui.style.Draw
import kami.libs.ui.style.Icon
import kami.libs.ui.style.Icons
import kami.libs.ui.style.Metrics
import kami.libs.ui.style.Palette
import kami.libs.ui.hud.HudEditor
import kami.libs.ui.hud.HudLayout
import kami.libs.ui.pin.PinWindow
import kami.libs.ui.pin.Pins
import kami.libs.ui.settings.AppearancePanels
import kami.libs.ui.text.tr
import net.minecraft.client.Minecraft
import kami.libs.ui.widget.*
import kotlin.math.max
import kotlin.math.min

private const val COLUMN_W = 320
private const val INTERFACE_W = 440
private const val SCROLL_W = 6
private const val GAP = 4
private const val CARD_GAP = 6
private const val HINT_H = 20
private val LINE_H get() = Metrics.px(14)
private val LABEL_H get() = Metrics.px(9)

private fun cardH(vararg parts: Int) = Metrics.cardHeaderH + 10 + parts.sum() + GAP * (parts.size - 1)

private fun toggleH() = cardH(LINE_H)

private fun Ui.cardColumn(key: String, r: Rect, heights: List<Int>, width: Int = COLUMN_W, draw: (Int, Rect) -> Unit) {
    val area = Rect(r.x, r.y, min(r.w, width), r.h)
    scroll(key, area, heights.sum() + CARD_GAP * (heights.size - 1)) { c ->
        val f = Flow(c, CARD_GAP)
        heights.forEachIndexed { i, h -> draw(i, f.take(h)) }
    }
}

private fun Ui.toggleCard(r: Rect, title: String, icon: Icon, on: Boolean?, label: String, tip: String, key: String, reason: String = tr("kami_essentials.prefs.unavailable"), change: (Boolean) -> Unit) {
    anchor("prefs:$key", r)
    val f = Flow(card(r, title, icon), GAP)
    toggle(f.take(LINE_H), on == true, label, enabled = on != null, disabledReason = reason, tip = tip, key = key)?.let(change)
}

private fun Ui.settingsSkeleton(r: Rect, cards: Int) = cardColumn("prefs:skeleton", r, List(cards) { toggleH() }) { _, box -> skeletonTile(box) }

internal class InterfacePage : Page() {
    private val panels = AppearancePanels()
    private var tab = 0
    override val title get() = tr("kami_libs.common.interface")
    override val help get() = when (tab) {
        0 -> listOf(Callout("appearance:look", tr("kami_libs.appearance.look"), tr("kami_libs.appearance.look.help")))
        1 -> listOf(Callout("appearance:feel", tr("kami_libs.appearance.feel"), tr("kami_libs.appearance.feel.help")))
        else -> listOf(Callout("prefs:hud", tr("kami_essentials.interface.hud"), tr("kami_essentials.help.hud")))
    }

    override fun leaving(next: Route): Boolean = true.also { panels.flush() }

    override fun draw(ui: Ui, r: Rect) {
        panels.begin(ui)
        val tabs = listOf(TabItem(tr("kami_libs.appearance.look"), Icons.BRUSH), TabItem(tr("kami_libs.appearance.feel"), Icons.EYE), TabItem(tr("kami_essentials.interface.hud"), Icons.GEAR))
        ui.subTabs(r.top(Metrics.px(22)), tabs, tab, key = "interface-tabs")?.let { panels.flush(); tab = it }
        val body = r.dropTop(Metrics.px(22), GAP)
        val width = min(body.w, INTERFACE_W)
        when (tab) {
            0 -> {
                val columns = panels.themeColumns(width - SCROLL_W)
                ui.cardColumn("interface-look", body, listOf(panels.lookHeight(columns), panels.previewHeight()), INTERFACE_W) { i, box ->
                    if (i == 0) panels.look(ui, box, columns) else panels.preview(ui, box)
                }
            }
            1 -> ui.cardColumn("interface-feel", body, listOf(panels.feelHeight()), INTERFACE_W) { _, box -> panels.feel(ui, box) }
            else -> {
                val pins = Pins.all().toList()
                ui.cardColumn("interface-hud", body, listOf(hudHeight(pins.size)), INTERFACE_W) { _, box -> hud(ui, box, pins) }
            }
        }
    }
}

private fun hudHeight(pins: Int) = cardH(*(HudLayout.options.flatMap { listOf(LABEL_H, CONTROL_H) } + CONTROL_H + LABEL_H + List(max(1, pins)) { LINE_H }).toIntArray())

private fun hud(ui: Ui, box: Rect, pins: List<PinWindow>) {
    ui.anchor("prefs:hud", box)
    val f = Flow(ui.card(box, tr("kami_essentials.interface.hud"), Icons.EYE), GAP)
    HudLayout.options.forEachIndexed { i, o ->
        ui.fieldLabel(f.take(LABEL_H), o.label())
        ui.segmented(f.take(CONTROL_H), o.choices.map { Option(it.first, it.second()) }, o.get(), key = "hud-option:$i")?.let(o.set)
    }
    if (ui.button(f.take(CONTROL_H), tr("kami_libs.hud.edit"), Icons.GEAR, key = "hud-edit")) Minecraft.getInstance().let { it.setScreen(HudEditor(it.screen)) }
    ui.fieldLabel(f.take(LABEL_H), tr("kami_essentials.interface.pins"))
    if (pins.isEmpty()) f.take(LINE_H).let { ui.fitText(it.x, it.y + 3, it.w, tr("kami_essentials.interface.pins.none"), Palette.textMuted) }
    pins.forEach { w ->
        val row = f.take(LINE_H)
        val items = Pins.items(w.id)
        ui.toggle(row.dropRight(row.h + GAP), !Pins.hidden(w.id), w.title(items), key = "pin-show:${w.id}")?.let { Pins.hide(w.id, !it) }
        if (ui.iconButton(row.right(row.h), Icons.CLOSE, tr("kami_essentials.interface.pins.clear"), enabled = items.isNotEmpty(), key = "pin-clear:${w.id}")) Pins.clear(w.id)
    }
}

internal class ChatPage(private val app: PersonalApp) : Page() {
    override val title get() = tr("kami_essentials.nav.chat")
    override val help get() = listOf(
        Callout("prefs:channel", tr("kami_essentials.chat.channel"), tr("kami_essentials.help.channel")),
        Callout("prefs:dm_sound", tr("kami_essentials.chat.messages"), tr("kami_essentials.help.messages"))
    )

    override fun draw(ui: Ui, r: Rect) {
        val s = app.snap ?: return ui.settingsSkeleton(r, 2)
        ui.cardColumn("chat", r, listOf(cardH(CONTROL_H, HINT_H), toggleH())) { i, box ->
            if (i == 0) channel(ui, box, s.channel, s.channels)
            else ui.toggleCard(box, tr("kami_essentials.chat.messages"), Icons.BELL, app.shown("dm_sound", s.dmSound), tr("kami_essentials.chat.dm_sound"), tr("kami_essentials.chat.dm_sound.tip"), "dm_sound") { app.set("dm_sound", it) }
        }
    }

    private fun channel(ui: Ui, box: Rect, actual: String, channels: List<String>) {
        ui.anchor("prefs:channel", box)
        val f = Flow(ui.card(box, tr("kami_essentials.chat.channel"), Icons.SCROLL), GAP)
        val channel = app.shown("channel", actual)
        ui.segmented(f.take(CONTROL_H), channels.map { Option(it, tr("kami_essentials.chat.channel.$it"), description = tr("kami_essentials.chat.channel.$it.desc")) }, channel, key = "channel")?.let { app.set("channel", it) }
        val hint = f.take(HINT_H)
        Draw.paragraph(ui.g, tr("kami_essentials.chat.channel.$channel.desc"), hint.x, hint.y, hint.w, maxLines = 2)
    }
}

internal class PlayerPage(private val app: PersonalApp) : Page() {
    override val title get() = tr("kami_libs.common.player")
    override val help get() = listOf(
        Callout("prefs:trades", tr("kami_essentials.player.trading"), tr("kami_essentials.help.trades")),
        Callout("prefs:sidebar", tr("kami_essentials.player.sidebar"), tr("kami_essentials.help.sidebar"))
    )

    override fun draw(ui: Ui, r: Rect) {
        val s = app.snap ?: return ui.settingsSkeleton(r, 3)
        ui.cardColumn("player", r, List(if (s.invisible != null) 3 else 2) { toggleH() }) { i, box ->
            when (i) {
                0 -> ui.toggleCard(box, tr("kami_essentials.player.trading"), Icons.HANDSHAKE, app.shown("trades", s.trades), tr("kami_essentials.player.trades"), tr("kami_essentials.player.trades.tip"), "trades") { app.set("trades", it) }
                1 -> ui.toggleCard(box, tr("kami_essentials.player.sidebar"), Icons.LEDGER, app.shown("sidebar", s.sidebar), tr("kami_essentials.player.sidebar.show"), tr("kami_essentials.player.sidebar.tip"), "sidebar") { app.set("sidebar", it) }
                else -> ui.toggleCard(box, tr("kami_essentials.admin.tab.staff"), Icons.EYE, app.shown("invisible", s.invisible), tr("kami_essentials.player.invisible"), tr("kami_essentials.player.invisible.tip"), "invisible") { app.set("invisible", it) }
            }
        }
    }
}

internal class DiscordPage(private val app: PersonalApp) : Page() {
    override val title get() = tr("kami_essentials.nav.discord")
    override val help get() = listOf(
        Callout("prefs:discord", tr("kami_essentials.prefs.discord.account"), tr("kami_essentials.prefs.discord.help.account")),
        Callout("prefs:discord_ping", tr("kami_essentials.prefs.discord.pings"), tr("kami_essentials.prefs.discord.help.pings")),
        Callout("prefs:discord_alerts", tr("kami_essentials.prefs.discord.alerts"), tr("kami_essentials.prefs.discord.help.alerts"))
    )

    override fun draw(ui: Ui, r: Rect) {
        val s = app.snap ?: return ui.settingsSkeleton(r, 3)
        val account = if (s.discord != null) cardH(LINE_H, CONTROL_H) else cardH(LINE_H)
        ui.cardColumn("discord", r, listOf(account, toggleH(), toggleH())) { i, box ->
            when (i) {
                0 -> account(ui, box, s.discord)
                1 -> ui.toggleCard(box, tr("kami_essentials.prefs.discord.pings"), Icons.BELL, app.shown("discord_ping", s.discordPing), tr("kami_essentials.prefs.discord.ping"), tr("kami_essentials.prefs.discord.ping.tip"), "discord_ping") { app.set("discord_ping", it) }
                else -> ui.toggleCard(box, tr("kami_essentials.prefs.discord.alerts"), Icons.BELL, app.shown("discord_alerts", s.discordAlerts).takeIf { s.discord != null }, tr("kami_essentials.prefs.discord.alert"), tr("kami_essentials.prefs.discord.alert.tip"), "discord_alerts", tr("kami_essentials.prefs.discord.link_first")) { app.set("discord_alerts", it) }
            }
        }
    }

    private fun account(ui: Ui, box: Rect, name: String?) {
        ui.anchor("prefs:discord", box)
        val c = Flow(ui.card(box, tr("kami_essentials.prefs.discord.account"), Icons.GLOBE), GAP)
        val line = c.take(LINE_H)
        ui.fitText(line.x, line.y, line.w, name ?: tr("kami_essentials.prefs.discord.unlinked"), if (name != null) Palette.textSecondary else Palette.textMuted)
        if (name != null && ui.button(c.take(CONTROL_H), tr("kami_essentials.prefs.discord.unlink"), Icons.CROSS, style = ButtonStyle.DANGER, key = "discord-unlink"))
            confirmDialog(app::open, tr("kami_essentials.prefs.discord.unlink.title"), name, Icons.CROSS,
                listOf(Consequence(tr("kami_essentials.prefs.discord.unlink.body"))), tr("kami_essentials.prefs.discord.unlink"), danger = true) { app.set("discord_unlink", "on") }
    }
}
