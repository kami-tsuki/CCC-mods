package kami.essentials.client

import kami.libs.ui.UiPrefs
import kami.libs.ui.app.Callout
import kami.libs.ui.app.Consequence
import kami.libs.ui.app.Page
import kami.libs.ui.app.Route
import kami.libs.ui.app.confirmDialog
import kami.libs.ui.core.Flow
import kami.libs.ui.core.Rect
import kami.libs.ui.core.Ui
import kami.libs.ui.core.UiScale
import kami.libs.ui.style.Draw
import kami.libs.ui.style.Format
import kami.libs.ui.style.Icon
import kami.libs.ui.style.Icons
import kami.libs.ui.style.Palette
import kami.libs.ui.hud.HudEditor
import kami.libs.ui.hud.HudLayout
import kami.libs.ui.pin.Pins
import kami.libs.ui.text.tr
import net.minecraft.client.Minecraft
import kami.libs.ui.widget.*

private const val TOGGLE_CARD_H = 42
private const val STATUS_CARD_H = 80
private const val LOOK_H = 92
private const val FEEL_H = 128
private const val CHANNEL_H = 70
private const val HUD_BASE_H = 76
private const val PIN_ROW_H = 18

private fun Ui.toggleCard(r: Rect, title: String, icon: Icon, on: Boolean?, label: String, tip: String, key: String, change: (Boolean) -> Unit) {
    anchor("prefs:$key", r)
    val f = Flow(card(r, title, icon), 4)
    toggle(f.take(14), on == true, label, enabled = on != null, disabledReason = tr("kami_essentials.prefs.unavailable"), tip = tip, key = key)?.let(change)
}

private fun Ui.settingsSkeleton(r: Rect, cards: Int) {
    val f = Flow(r.columns(2, 10)[0], 6)
    repeat(cards) { skeletonTile(f.take(TOGGLE_CARD_H)) }
}

internal class InterfacePage : Page() {
    private var dirty = false
    override val title get() = tr("kami_essentials.nav.interface")
    override val help get() = listOf(
        Callout("prefs:look", tr("kami_essentials.interface.look"), tr("kami_essentials.help.look")),
        Callout("prefs:feel", tr("kami_essentials.interface.feel"), tr("kami_essentials.help.feel")),
        Callout("prefs:hud", tr("kami_essentials.interface.hud"), tr("kami_essentials.help.hud"))
    )

    private fun flush() {
        if (!dirty) return
        dirty = false
        UiPrefs.save()
    }

    override fun leaving(next: Route): Boolean = true.also { flush() }

    override fun draw(ui: Ui, r: Rect) {
        if (!ui.isDown()) flush()
        val p = UiPrefs.prefs
        val (left, right) = r.columns(2, 10)
        val lookBox = left.top(LOOK_H)
        ui.anchor("prefs:look", lookBox)
        val look = Flow(ui.card(lookBox, tr("kami_essentials.interface.look"), Icons.BRUSH), 4)
        ui.fieldLabel(look.take(9), tr("kami_essentials.interface.scale"))
        ui.segmented(look.take(CONTROL_H), UiScale.choices.map { Option(it, Format.percent(it.toDouble())) }, UiScale.snap(p.uiScale), key = "ui-scale")?.let { p.uiScale = it; UiPrefs.save() }
        ui.fieldLabel(look.take(9), tr("kami_essentials.interface.colours"))
        ui.select(look.take(CONTROL_H), Palette.Vision.entries.map { Option(it.name, tr("kami_essentials.vision.${it.name.lowercase()}"), description = tr("kami_essentials.vision.${it.name.lowercase()}.desc")) }, p.vision, key = "vision")?.let { p.vision = it; UiPrefs.save() }
        hud(ui, left.dropTop(LOOK_H + 10))
        val feelBox = right.top(FEEL_H)
        ui.anchor("prefs:feel", feelBox)
        val feel = Flow(ui.card(feelBox, tr("kami_essentials.interface.feel"), Icons.EYE), 4)
        ui.toggle(feel.take(14), p.reduceMotion, tr("kami_essentials.interface.motion"), tip = tr("kami_essentials.interface.motion.tip"), key = "motion")?.let { p.reduceMotion = it; UiPrefs.save() }
        ui.toggle(feel.take(14), p.autoRefresh, tr("kami_essentials.interface.autorefresh"), tip = tr("kami_essentials.interface.autorefresh.tip"), key = "auto-refresh")?.let { p.autoRefresh = it; UiPrefs.save() }
        ui.fieldLabel(feel.take(9), tr("kami_essentials.interface.sounds"), Format.percent(p.sounds.toDouble()))
        ui.slider(feel.take(CONTROL_H), p.sounds.toDouble(), 0.0, 1.0, 0.05, format = { Format.percent(it) }, key = "sounds")?.let { p.sounds = it.toFloat(); UiPrefs.apply(); dirty = true }
        ui.fieldLabel(feel.take(9), tr("kami_essentials.interface.tooltip_delay"), "${Format.number(p.tooltipDelay)} ms")
        ui.slider(feel.take(CONTROL_H), p.tooltipDelay.toDouble(), 0.0, 1500.0, 50.0, format = { "${Format.number(it.toLong())} ms" }, key = "tip-delay")?.let { p.tooltipDelay = it.toInt(); UiPrefs.apply(); dirty = true }
    }
}

private fun hud(ui: Ui, area: Rect) {
    val pins = Pins.all().toList()
    val box = area.top(HUD_BASE_H + HudLayout.options.size * (CONTROL_H + 17) + maxOf(1, pins.size) * PIN_ROW_H)
    ui.anchor("prefs:hud", box)
    val f = Flow(ui.card(box, tr("kami_essentials.interface.hud"), Icons.EYE), 4)
    HudLayout.options.forEachIndexed { i, o ->
        ui.fieldLabel(f.take(9), o.label())
        ui.segmented(f.take(CONTROL_H), o.choices.map { Option(it.first, it.second()) }, o.get(), key = "hud-option:$i")?.let(o.set)
    }
    if (ui.button(f.take(CONTROL_H), tr("kami_libs.hud.edit"), Icons.GEAR, key = "hud-edit")) Minecraft.getInstance().let { it.setScreen(HudEditor(it.screen)) }
    ui.fieldLabel(f.take(9), tr("kami_essentials.interface.pins"))
    if (pins.isEmpty()) f.take(PIN_ROW_H).let { Draw.text(ui.g, Draw.fit(tr("kami_essentials.interface.pins.none"), it.w), it.x, it.y + 3, Palette.textMuted) }
    pins.forEach { w ->
        val row = f.take(PIN_ROW_H - 4)
        val items = Pins.items(w.id)
        ui.toggle(row.dropRight(row.h + 4), !Pins.hidden(w.id), w.title(items), key = "pin-show:${w.id}")?.let { Pins.hide(w.id, !it) }
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
        val (left, right) = r.columns(2, 10)
        val box = left.top(CHANNEL_H)
        ui.anchor("prefs:channel", box)
        val f = Flow(ui.card(box, tr("kami_essentials.chat.channel"), Icons.SCROLL), 4)
        val channel = app.shown("channel", s.channel)
        ui.segmented(f.take(CONTROL_H), s.channels.map { Option(it, tr("kami_essentials.chat.channel.$it"), description = tr("kami_essentials.chat.channel.$it.desc")) }, channel, key = "channel")?.let { app.set("channel", it) }
        val hint = f.take(20)
        Draw.paragraph(ui.g, tr("kami_essentials.chat.channel.$channel.desc"), hint.x, hint.y, hint.w, maxLines = 2)
        ui.toggleCard(right.top(TOGGLE_CARD_H), tr("kami_essentials.chat.messages"), Icons.BELL, app.shown("dm_sound", s.dmSound), tr("kami_essentials.chat.dm_sound"), tr("kami_essentials.chat.dm_sound.tip"), "dm_sound") { app.set("dm_sound", it) }
    }
}

internal class PlayerPage(private val app: PersonalApp) : Page() {
    override val title get() = tr("kami_essentials.nav.player")
    override val help get() = listOf(
        Callout("prefs:trades", tr("kami_essentials.player.trading"), tr("kami_essentials.help.trades")),
        Callout("prefs:sidebar", tr("kami_essentials.player.sidebar"), tr("kami_essentials.help.sidebar"))
    )

    override fun draw(ui: Ui, r: Rect) {
        val s = app.snap ?: return ui.settingsSkeleton(r, 3)
        val f = Flow(r.columns(2, 10)[0], 6)
        ui.toggleCard(f.take(TOGGLE_CARD_H), tr("kami_essentials.player.trading"), Icons.HANDSHAKE, app.shown("trades", s.trades), tr("kami_essentials.player.trades"), tr("kami_essentials.player.trades.tip"), "trades") { app.set("trades", it) }
        ui.toggleCard(f.take(TOGGLE_CARD_H), tr("kami_essentials.player.sidebar"), Icons.LEDGER, app.shown("sidebar", s.sidebar), tr("kami_essentials.player.sidebar.show"), tr("kami_essentials.player.sidebar.tip"), "sidebar") { app.set("sidebar", it) }
        if (s.invisible != null) ui.toggleCard(f.take(TOGGLE_CARD_H), tr("kami_essentials.player.staff"), Icons.EYE, app.shown("invisible", s.invisible), tr("kami_essentials.player.invisible"), tr("kami_essentials.player.invisible.tip"), "invisible") { app.set("invisible", it) }
    }
}

internal class DiscordPage(private val app: PersonalApp) : Page() {
    override val title get() = tr("kami_essentials.nav.discord")
    override val help get() = listOf(
        Callout("prefs:discord", tr("kami_essentials.prefs.discord.account"), tr("kami_essentials.prefs.discord.help.account")),
        Callout("prefs:discord_ping", tr("kami_essentials.prefs.discord.pings"), tr("kami_essentials.prefs.discord.help.pings"))
    )

    override fun draw(ui: Ui, r: Rect) {
        val s = app.snap ?: return ui.settingsSkeleton(r, 2)
        val f = Flow(r.columns(2, 10)[0], 6)
        val box = f.take(STATUS_CARD_H)
        ui.anchor("prefs:discord", box)
        val c = Flow(ui.card(box, tr("kami_essentials.prefs.discord.account"), Icons.GLOBE), 4)
        val line = c.take(12)
        Draw.text(ui.g, Draw.fit(s.discord ?: tr("kami_essentials.prefs.discord.unlinked"), line.w), line.x, line.y, if (s.discord != null) Palette.textSecondary else Palette.textMuted)
        if (s.discord != null && ui.button(c.take(CONTROL_H), tr("kami_essentials.prefs.discord.unlink"), Icons.CROSS, style = ButtonStyle.DANGER, key = "discord-unlink"))
            confirmDialog(app::open, tr("kami_essentials.prefs.discord.unlink.title"), s.discord, Icons.CROSS,
                listOf(Consequence(tr("kami_essentials.prefs.discord.unlink.body"))), tr("kami_essentials.prefs.discord.unlink"), danger = true) { app.set("discord_unlink", "on") }
        ui.toggleCard(f.take(TOGGLE_CARD_H), tr("kami_essentials.prefs.discord.pings"), Icons.BELL, app.shown("discord_ping", s.discordPing), tr("kami_essentials.prefs.discord.ping"), tr("kami_essentials.prefs.discord.ping.tip"), "discord_ping") { app.set("discord_ping", it) }
    }
}
