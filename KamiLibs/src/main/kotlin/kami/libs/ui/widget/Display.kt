package kami.libs.ui.widget

import kami.libs.ui.text.tr
import kami.libs.ui.core.Cursor
import kami.libs.ui.core.Rect
import kami.libs.ui.core.Tip
import kami.libs.ui.core.Ui
import kami.libs.ui.style.Draw
import kami.libs.ui.style.Format
import kami.libs.ui.style.Glyphs
import kami.libs.ui.style.Icon
import kami.libs.ui.style.Icons
import kami.libs.ui.style.Palette
import kami.libs.ui.style.Severity
import kami.libs.ui.style.Sprites
import kami.libs.ui.style.TextStyle
import net.minecraft.client.Minecraft
import net.minecraft.world.item.ItemStack
import kotlin.math.max

fun Ui.filterBar(r: Rect, key: String, content: (Rect) -> Unit): Rect {
    val bar = r.top(CONTROL_H)
    anchor(key, bar)
    content(bar)
    return r.dropTop(CONTROL_H + 6)
}

fun Ui.property(r: Rect, label: String, value: String, color: Int = Palette.text, copy: Boolean = false, tip: String? = null, key: Any = "prop:$label"): Rect {
    Draw.text(g, label.uppercase(), r.x, r.y + 1, Palette.textMuted)
    val labelW = Draw.width(label.uppercase()) + 4
    val copyW = if (copy) 14 else 0
    val shown = Draw.fit(value, r.w - labelW - copyW - 4)
    val vw = Draw.width(shown)
    val dotsFrom = r.x + labelW
    val dotsTo = r.right - copyW - vw - 4
    var dx = dotsFrom
    while (dx < dotsTo) { g.fill(dx, r.y + 7, dx + 1, r.y + 8, Palette.border); dx += 3 }
    Draw.text(g, shown, r.right - copyW - vw, r.y + 1, color)
    if (copy) {
        val c = Rect(r.right - 12, r.y - 1, 12, 12)
        if (hovering(c)) cursor = Cursor.HAND
        Draw.tintedIcon(g, Icons.COPY, c.x - 2, c.y - 2, 16, if (hovering(c)) Palette.text else Palette.textMuted)
        tooltip("$key:copy", c, tr("kami_libs.common.copy"))
        if (pressed(c) != null) Minecraft.getInstance().keyboardHandler.clipboard = value
    }
    if (shown != value || tip != null) tooltip(key, r, tip ?: value)
    return r.dropTop(12)
}

class Trend(val delta: Long, val label: String, val goodWhenUp: Boolean = true)

fun Ui.statTile(
    r: Rect, label: String, value: String, icon: Icon? = null, color: Int = Palette.text, sub: String? = null, trend: Trend? = null,
    spark: List<Long>? = null, flashValue: Long? = null, tip: Tip? = null, key: Any = "tile:$label"
): Boolean {
    val hover = hover(key, r)
    Draw.sprite(g, if (hover) Sprites.CARD_HOVER else Sprites.CARD, r)
    flashValue?.let { v -> flash(key, v).takeIf { it != 0 }?.let { Draw.fill(g, r.inset(1), it) } }
    var x = r.x + 5
    icon?.let { x += Draw.leadIcon(g, it, x, r.y + 9) }
    Draw.text(g, Draw.fit(label.uppercase(), r.right - x - 4), x, r.y + 5, Palette.textMuted)
    val valueY = r.y + if (r.h >= 44) 19 else 17
    val sparkW = if ((spark?.size ?: 0) >= 2) 46 else 0
    Draw.text(g, Draw.fit(value, r.w - 10 - sparkW, TextStyle.HEADING), r.x + 5, valueY, TextStyle.HEADING, color)
    var infoY = valueY + 12
    trend?.let { t ->
        val up = t.delta > 0
        val good = if (t.delta == 0L) null else up == t.goodWhenUp
        val c = when (good) { true -> Palette.success; false -> Palette.danger; null -> Palette.textMuted }
        val glyph = if (t.delta > 0) Glyphs.Glyph.UP else if (t.delta < 0) Glyphs.Glyph.DOWN else Glyphs.Glyph.FLAT
        val tx = Draw.component(g, Glyphs.component(glyph), r.x + 5, infoY, c)
        Draw.text(g, Draw.fit(t.label, r.right - tx - 8), tx + 3, infoY, c)
        infoY += 10
    }
    sub?.let { if (infoY + 8 <= r.bottom - 3) Draw.text(g, Draw.fit(it, r.w - 10), r.x + 5, infoY, Palette.textMuted) }
    spark?.takeIf { it.size >= 2 }?.let { sparkline(Rect(r.right - 45, valueY - 2, 40, 12), it, color) }
    tip?.let { t -> tooltip(key, r) { t } }
    if (hover && tip != null) cursor = Cursor.HAND
    return pressed(r) != null
}

class KpiTile(
    val label: String, val value: String, val icon: Icon? = null, val color: Int = Palette.text, val sub: String? = null,
    val trend: Trend? = null, val spark: List<Long>? = null, val flashValue: Long? = null, val tip: Tip? = null, val onClick: (() -> Unit)? = null
)

fun Ui.kpiRow(r: Rect, tiles: List<KpiTile>, gap: Int = 6, key: Any = "kpi") {
    val cells = r.columns(tiles.size, gap)
    tiles.forEachIndexed { i, t ->
        if (statTile(cells[i], t.label, t.value, t.icon, t.color, t.sub, t.trend, t.spark, t.flashValue, t.tip, key = "$key:${t.label}")) t.onClick?.invoke()
    }
}

fun Ui.progress(r: Rect, fraction: Double, color: Int = Palette.brass, label: String? = null) {
    Draw.sprite(g, Sprites.TRACK, r)
    val f = fraction.coerceIn(0.0, 1.0)
    if (f > 0) Draw.tinted(g, Sprites.FILL, r.withWidth(max(2, (r.w * f).toInt())), color)
    label?.let { Draw.textRight(g, it, r.right, r.y - 10, Palette.textMuted) }
}

fun Ui.meter(r: Rect, value: Int, max: Int, severityAt: (Int) -> Severity, label: String? = null) {
    val segments = max.coerceAtLeast(1)
    val cells = r.columns(segments, 2)
    cells.forEachIndexed { i, c ->
        val filled = i < value
        Draw.fill(g, c, if (filled) severityAt(i + 1).color else Palette.alpha(Palette.border, 0xC0))
    }
    label?.let { Draw.textRight(g, it, r.right, r.y - 10, Palette.textMuted) }
}

fun Ui.chip(x: Int, y: Int, label: String, color: Int = Palette.textSecondary, icon: Icon? = null, tip: String? = null, key: Any = "chip:$label"): Int {
    val w = Draw.width(label) + 10 + (if (icon != null) Draw.ICON_SLOT - 1 else 0)
    val r = Rect(x, y, w, 13)
    Draw.tinted(g, Sprites.CHIP, r, Palette.alpha(color, 0x24))
    var tx = x + 5
    icon?.let { tx += Draw.leadIcon(g, it, tx - 1, r.centerY) - 1 }
    Draw.text(g, label, tx, y + 3, color)
    tooltip(key, r, tip)
    return w
}

fun Ui.statusPill(x: Int, y: Int, label: String, severity: Severity, tip: String? = null, key: Any = "pill:$label"): Int {
    val w = Draw.width(label) + 16
    val r = Rect(x, y, w, 13)
    Draw.tinted(g, Sprites.CHIP, r, severity.tint)
    Draw.fill(g, Rect(x + 4, y + 4, 5, 5), severity.color)
    Draw.text(g, label, x + 12, y + 3, severity.color)
    tooltip(key, r, tip)
    return w
}

fun Ui.money(x: Int, y: Int, amount: Long, signed: Boolean = false, compact: Boolean = false, color: Int? = null): Int {
    val text = (if (signed) Format.signed(amount).substringBefore(" ") else if (compact) Format.compact(amount) else Format.number(amount))
    val c = color ?: when {
        signed && amount > 0 -> Palette.success
        signed && amount < 0 -> Palette.danger
        amount < 0 -> Palette.danger
        else -> Palette.money
    }
    val end = Draw.text(g, text, x, y, c)
    return Draw.component(g, Glyphs.component(Glyphs.Glyph.COIN), end + 1, y, Palette.money) - x
}

fun moneyWidth(amount: Long, signed: Boolean = false, compact: Boolean = false) =
    Draw.width(if (signed) Format.signed(amount) else if (compact) Format.compact(amount) else Format.number(amount)) + 10

fun Ui.moneyRight(right: Int, y: Int, amount: Long, signed: Boolean = false, compact: Boolean = false, color: Int? = null) =
    money(right - moneyWidth(amount, signed, compact), y, amount, signed, compact, color)

fun Ui.banner(r: Rect, severity: Severity, title: String, body: String? = null, action: String? = null, key: Any = "banner:$title"): Boolean {
    Draw.fill(g, r, severity.tint)
    Draw.outline(g, r, severity.edge)
    Draw.fill(g, r.left(2), severity.color)
    Draw.icon(g, iconFor(severity), r.x + 5, r.y + (r.h - 16) / 2)
    val actionW = action?.let { buttonWidth(it) } ?: 0
    val textW = (r.w - 34 - actionW).coerceAtLeast(20)
    if (body == null) Draw.text(g, Draw.fit(title, textW), r.x + 25, r.y + (r.h - 8) / 2, TextStyle.HEADING, severity.color)
    else {
        Draw.text(g, Draw.fit(title, textW), r.x + 25, r.y + (r.h - 18) / 2, TextStyle.HEADING, severity.color)
        Draw.text(g, Draw.fit(body, textW), r.x + 25, r.y + (r.h - 18) / 2 + 10, Palette.textSecondary)
    }
    return action != null && button(Rect(r.right - actionW - 4, r.y + (r.h - SMALL_H) / 2, actionW, SMALL_H), action, key = "$key:action")
}

fun Ui.callout(r: Rect, severity: Severity, text: String): Int {
    val h = Draw.paragraphHeight(text, r.w - 24) + 8
    val box = r.withHeight(h)
    Draw.fill(g, box, severity.tint)
    Draw.fill(g, box.left(2), severity.color)
    Draw.icon(g, iconFor(severity), box.x + 4, box.y + 2)
    Draw.paragraph(g, text, box.x + 22, box.y + 5, r.w - 26, Palette.textSecondary)
    return h
}

fun iconFor(severity: Severity) = when (severity) {
    Severity.SUCCESS -> Icons.CHECK
    Severity.WARNING -> Icons.WARNING
    Severity.DANGER -> Icons.DANGER
    Severity.INFO -> Icons.INFO
    Severity.NEUTRAL -> Icons.HELP
}

class Step(val label: String, val detail: String, val state: StepState)
enum class StepState { DONE, CURRENT, PENDING, DANGER }

fun Ui.timeline(r: Rect, steps: List<Step>) {
    if (steps.isEmpty()) return
    val gap = if (steps.size > 1) (r.w - 10) / (steps.size - 1) else 0
    val y = r.y + 4
    steps.forEachIndexed { i, s ->
        val x = r.x + 5 + i * gap
        if (i < steps.lastIndex) {
            val next = steps[i + 1].state
            val color = if (next == StepState.PENDING) Palette.border else if (next == StepState.DANGER) Palette.danger else Palette.brass
            Draw.fill(g, Rect(x + 3, y + 2, gap - 3, 2), color)
        }
        val dot = when (s.state) {
            StepState.DONE -> Palette.brass
            StepState.CURRENT -> Palette.warning
            StepState.DANGER -> Palette.danger
            StepState.PENDING -> Palette.border
        }
        Draw.fill(g, Rect(x - 2, y - 1, 7, 7), Palette.canvas)
        Draw.fill(g, Rect(x - 1, y, 5, 5), dot)
        if (s.state == StepState.CURRENT) Draw.outline(g, Rect(x - 3, y - 2, 9, 9), Palette.alpha(Palette.warning, (0x60 + 0x9F * pulse()).toInt()))
        val lx = (x - Draw.width(s.label) / 2).coerceIn(r.x, r.right - Draw.width(s.label))
        Draw.text(g, s.label, lx, y + 10, if (s.state == StepState.PENDING) Palette.textMuted else Palette.text)
        val dx = (x - Draw.width(s.detail) / 2).coerceIn(r.x, r.right - Draw.width(s.detail))
        Draw.text(g, s.detail, dx, y + 20, Palette.textMuted)
    }
}

fun Ui.keyHints(x: Int, y: Int, hints: List<Pair<String, String>>) {
    var cx = x
    hints.forEach { (keys, action) ->
        keys.split("+").forEachIndexed { i, k ->
            if (i > 0) { Draw.text(g, "+", cx, y + 2, Palette.textMuted); cx += 7 }
            cx += keycap(cx, y, k) + 1
        }
        cx += 2 + Draw.text(g, action, cx + 2, y + 2, Palette.textMuted) - cx
        cx += 10
    }
}

fun Ui.avatar(id: String, x: Int, y: Int, size: Int = 12, online: Boolean? = null) {
    Draw.head(g, id, x, y, size)
    online?.let { Draw.fill(g, Rect(x + size - 3, y + size - 3, 4, 4), if (it) Palette.success else Palette.textDisabled) }
}

fun Ui.attention(r: Rect, active: Boolean) {
    if (!active) return
    val a = (0x50 + 0x8F * pulse()).toInt()
    Draw.outline(g, r.grow(1), Palette.alpha(Palette.warning, a))
}

fun Ui.legendItem(x: Int, y: Int, color: Int, label: String, hatched: Boolean = false): Int {
    Draw.fill(g, Rect(x, y + 1, 7, 7), color)
    if (hatched) Draw.hatch(g, Rect(x, y + 1, 7, 7), Palette.alpha(0, 0x90), 3)
    return Draw.text(g, label, x + 10, y, Palette.textSecondary) - x + 10
}

fun Ui.itemSlot(r: Rect, stack: ItemStack, count: String? = null, selected: Boolean = false, enabled: Boolean = true, key: Any = "slot:${r.x}:${r.y}"): Boolean {
    val hover = hover(key, r)
    panel(r, sunken = true)
    if (hover && enabled) Draw.fill(g, r.inset(1), Palette.alpha(0xFFFFFF, 0x1C))
    if (selected) Draw.outline(g, r, Palette.brass)
    val x = r.x + (r.w - 16) / 2
    val y = r.y + (r.h - 16) / 2
    g.renderItem(stack, x, y)
    g.renderItemDecorations(Draw.font, stack, x, y, count)
    if (hover && !stack.isEmpty) overlay(5) { g.renderTooltip(Draw.font, stack, mouseX, mouseY) }
    if (hover && enabled) cursor = Cursor.HAND
    return enabled && pressed(r) != null
}
