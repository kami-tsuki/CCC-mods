package kami.essentials.client

import kami.essentials.net.Snap
import kami.libs.ui.UiPrefs
import kami.libs.ui.app.Callout
import kami.libs.ui.app.KamiApp
import kami.libs.ui.app.NavGroup
import kami.libs.ui.app.NavItem
import kami.libs.ui.app.Page
import kami.libs.ui.app.Route
import kami.libs.ui.core.Flow
import kami.libs.ui.core.Rect
import kami.libs.ui.core.Ui
import kami.libs.ui.core.UiScale
import kami.libs.ui.style.Draw
import kami.libs.ui.style.Format
import kami.libs.ui.style.Icon
import kami.libs.ui.style.Icons
import kami.libs.ui.style.Palette
import kami.libs.ui.style.TextStyle
import kami.libs.ui.text.tr
import kami.libs.ui.widget.*
import net.minecraft.client.Minecraft

private const val TOGGLE_CARD_H = 42
private const val LOOK_H = 92
private const val FEEL_H = 110
private const val CHANNEL_H = 70

class PrefsApp private constructor() : KamiApp() {
    var snap: Snap? = null
    override val module get() = "essentials"
    override val home = Route("interface")

    private fun offline(): String? = if (snap == null) tr("kami_essentials.prefs.offline") else null

    override fun buildNav() = listOf(
        NavGroup(tr("kami_essentials.nav.group.client"), listOf(NavItem("interface", tr("kami_essentials.nav.interface"), Icons.BRUSH)), "client"),
        NavGroup(tr("kami_essentials.nav.group.server"), listOf(
            NavItem("chat", tr("kami_essentials.nav.chat"), Icons.SCROLL, lock = ::offline),
            NavItem("player", tr("kami_essentials.nav.player"), Icons.PERSON, lock = ::offline)
        ), "server")
    )

    override fun create(id: String): Page = when (id) {
        "chat" -> ChatPage(this)
        "player" -> PlayerPage(this)
        else -> InterfacePage()
    }

    override fun topBar(ui: Ui, r: Rect) {
        Draw.leadIcon(ui.g, Icons.SETTINGS, r.x + 6, r.centerY, Palette.brass)
        Draw.text(ui.g, tr("kami_essentials.module"), r.x + 24, r.centerY - 4, TextStyle.HEADING)
        Minecraft.getInstance().player?.let { Draw.textRight(ui.g, it.gameProfile.name, r.right - 8, r.centerY - 4, Palette.textMuted) }
    }

    fun set(key: String, value: String) = ClientEssentials.request("set", key, value)

    fun set(key: String, on: Boolean) = set(key, if (on) "on" else "off")

    companion object {
        val instance by lazy { PrefsApp() }
    }
}

private fun Ui.toggleCard(r: Rect, title: String, icon: Icon, on: Boolean?, label: String, tip: String, key: String, change: (Boolean) -> Unit) {
    anchor("prefs:$key", r)
    val f = Flow(card(r, title, icon), 4)
    toggle(f.take(14), on == true, label, enabled = on != null, disabledReason = tr("kami_essentials.prefs.unavailable"), tip = tip, key = key)?.let(change)
}

private class InterfacePage : Page() {
    override val title get() = tr("kami_essentials.nav.interface")
    override val help get() = listOf(
        Callout("prefs:look", tr("kami_essentials.interface.look"), tr("kami_essentials.help.look")),
        Callout("prefs:feel", tr("kami_essentials.interface.feel"), tr("kami_essentials.help.feel"))
    )

    override fun draw(ui: Ui, r: Rect) {
        val p = UiPrefs.prefs
        val (left, right) = r.columns(2, 10)
        val lookBox = left.top(LOOK_H)
        ui.anchor("prefs:look", lookBox)
        val look = Flow(ui.card(lookBox, tr("kami_essentials.interface.look"), Icons.BRUSH), 4)
        ui.fieldLabel(look.take(9), tr("kami_essentials.interface.scale"))
        ui.segmented(look.take(CONTROL_H), UiScale.choices.map { Option(it, Format.percent(it.toDouble())) }, UiScale.snap(p.uiScale), key = "ui-scale")?.let { p.uiScale = it; UiPrefs.save() }
        ui.fieldLabel(look.take(9), tr("kami_essentials.interface.colours"))
        ui.select(look.take(CONTROL_H), Palette.Vision.entries.map { Option(it.name, tr("kami_essentials.vision.${it.name.lowercase()}"), description = tr("kami_essentials.vision.${it.name.lowercase()}.desc")) }, p.vision, key = "vision")?.let { p.vision = it; UiPrefs.save() }
        val feelBox = right.top(FEEL_H)
        ui.anchor("prefs:feel", feelBox)
        val feel = Flow(ui.card(feelBox, tr("kami_essentials.interface.feel"), Icons.EYE), 4)
        ui.toggle(feel.take(14), p.reduceMotion, tr("kami_essentials.interface.motion"), tip = tr("kami_essentials.interface.motion.tip"), key = "motion")?.let { p.reduceMotion = it; UiPrefs.save() }
        ui.fieldLabel(feel.take(9), tr("kami_essentials.interface.sounds"), Format.percent(p.sounds.toDouble()))
        ui.slider(feel.take(CONTROL_H), p.sounds.toDouble(), 0.0, 1.0, 0.05, format = { Format.percent(it) }, key = "sounds")?.let { p.sounds = it.toFloat(); UiPrefs.save() }
        ui.fieldLabel(feel.take(9), tr("kami_essentials.interface.tooltip_delay"), "${Format.number(p.tooltipDelay)} ms")
        ui.slider(feel.take(CONTROL_H), p.tooltipDelay.toDouble(), 0.0, 1500.0, 50.0, format = { "${Format.number(it.toLong())} ms" }, key = "tip-delay")?.let { p.tooltipDelay = it.toInt(); UiPrefs.save() }
    }
}

private class ChatPage(private val app: PrefsApp) : Page() {
    override val title get() = tr("kami_essentials.nav.chat")
    override val help get() = listOf(
        Callout("prefs:channel", tr("kami_essentials.chat.channel"), tr("kami_essentials.help.channel")),
        Callout("prefs:dm_sound", tr("kami_essentials.chat.messages"), tr("kami_essentials.help.messages"))
    )

    override fun draw(ui: Ui, r: Rect) {
        val s = app.snap ?: return
        val (left, right) = r.columns(2, 10)
        val box = left.top(CHANNEL_H)
        ui.anchor("prefs:channel", box)
        val f = Flow(ui.card(box, tr("kami_essentials.chat.channel"), Icons.SCROLL), 4)
        ui.segmented(f.take(CONTROL_H), s.channels.map { Option(it, tr("kami_essentials.chat.channel.$it"), description = tr("kami_essentials.chat.channel.$it.desc")) }, s.channel, key = "channel")?.let { app.set("channel", it) }
        val hint = f.take(20)
        Draw.paragraph(ui.g, tr("kami_essentials.chat.channel.${s.channel}.desc"), hint.x, hint.y, hint.w, maxLines = 2)
        ui.toggleCard(right.top(TOGGLE_CARD_H), tr("kami_essentials.chat.messages"), Icons.BELL, s.dmSound, tr("kami_essentials.chat.dm_sound"), tr("kami_essentials.chat.dm_sound.tip"), "dm_sound") { app.set("dm_sound", it) }
    }
}

private class PlayerPage(private val app: PrefsApp) : Page() {
    override val title get() = tr("kami_essentials.nav.player")
    override val help get() = listOf(
        Callout("prefs:trades", tr("kami_essentials.player.trading"), tr("kami_essentials.help.trades")),
        Callout("prefs:sidebar", tr("kami_essentials.player.sidebar"), tr("kami_essentials.help.sidebar"))
    )

    override fun draw(ui: Ui, r: Rect) {
        val s = app.snap ?: return
        val f = Flow(r.columns(2, 10)[0], 6)
        ui.toggleCard(f.take(TOGGLE_CARD_H), tr("kami_essentials.player.trading"), Icons.HANDSHAKE, s.trades, tr("kami_essentials.player.trades"), tr("kami_essentials.player.trades.tip"), "trades") { app.set("trades", it) }
        ui.toggleCard(f.take(TOGGLE_CARD_H), tr("kami_essentials.player.sidebar"), Icons.LEDGER, s.sidebar, tr("kami_essentials.player.sidebar.show"), tr("kami_essentials.player.sidebar.tip"), "sidebar") { app.set("sidebar", it) }
        if (s.invisible != null) ui.toggleCard(f.take(TOGGLE_CARD_H), tr("kami_essentials.player.staff"), Icons.EYE, s.invisible, tr("kami_essentials.player.invisible"), tr("kami_essentials.player.invisible.tip"), "invisible") { app.set("invisible", it) }
    }
}
