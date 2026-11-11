package kami.libs.ui.widget

import kami.libs.ui.anim.flash
import kami.libs.ui.anim.pulse
import kami.libs.ui.text.tr
import kami.libs.ui.core.Cursor
import kami.libs.ui.core.Rect
import kami.libs.ui.core.Stack
import kami.libs.ui.core.Tip
import kami.libs.ui.core.Ui
import kami.libs.ui.core.UiScale
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
    val caption = label.uppercase()
    Draw.text(g, caption, r.x, r.y + 1, Palette.textMuted)
    val labelW = Draw.width(caption) + 4
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
    r: Rect, label: String, value: String?, icon: Icon? = null, color: Int = Palette.text, sub: String? = null, trend: Trend? = null,
    spark: List<Long>? = null, flashValue: Long? = null, tip: Tip? = null, key: Any = "tile:$label"
): Boolean {
    val hover = hover(key, r)
    Draw.sprite(g, if (hover) Sprites.CARD_HOVER else Sprites.CARD, r)
    flashValue?.let { v -> flash(key, v).takeIf { it != 0 }?.let { Draw.fill(g, r.inset(1), it) } }
    var x = r.x + 5
    icon?.let { x += Draw.leadIcon(g, it, x, r.y + 9) }
    fitText(x, r.y + 5, r.right - x - 4, label.uppercase(), Palette.textMuted, key = "$key:label")
    val valueY = tileValueY(r)
    val sparkW = if ((spark?.size ?: 0) >= 2) 46 else 0
    if (value == null) skeletonValue(r) else fitText(r.x + 5, valueY, r.w - 10 - sparkW, value, color, TextStyle.HEADING, key = "$key:value")
    var infoY = valueY + 12
    trend?.let { t ->
        val up = t.delta > 0
        val good = if (t.delta == 0L) null else up == t.goodWhenUp
        val c = when (good) { true -> Palette.success; false -> Palette.danger; null -> Palette.textMuted }
        val glyph = if (t.delta > 0) Glyphs.Glyph.UP else if (t.delta < 0) Glyphs.Glyph.DOWN else Glyphs.Glyph.FLAT
        val tx = Draw.component(g, Glyphs.component(glyph), r.x + 5, infoY, c)
        fitText(tx + 3, infoY, r.right - tx - 8, t.label, c, key = "$key:trend")
        infoY += 10
    }
    sub?.let { if (infoY + 8 <= r.bottom - 3) fitText(r.x + 5, infoY, r.w - 10, it, Palette.textMuted, key = "$key:sub") }
    spark?.takeIf { it.size >= 2 }?.let { sparkline(Rect(r.right - 45, valueY - 2, 40, 12), it, color) }
    tip?.let { t -> tooltip(key, r) { t } }
    if (hover && tip != null) cursor = Cursor.HAND
    return pressed(r) != null
}

internal fun tileValueY(r: Rect) = r.y + if (r.h >= 44) 19 else 17

class KpiTile(
    val label: String, val value: String?, val icon: Icon? = null, val color: Int = Palette.text, val sub: String? = null,
    val trend: Trend? = null, val spark: List<Long>? = null, val flashValue: Long? = null, val tip: Tip? = null, val onClick: (() -> Unit)? = null
)

fun Ui.kpiTile(r: Rect, t: KpiTile, key: Any = "kpi") {
    if (statTile(r, t.label, t.value, t.icon, t.color, t.sub, t.trend, t.spark, t.flashValue, t.tip, key = "$key:${t.label}")) t.onClick?.invoke()
}

fun Ui.kpiRow(r: Rect, tiles: List<KpiTile>, gap: Int = 6, key: Any = "kpi") {
    val cells = r.columns(tiles.size, gap)
    tiles.forEachIndexed { i, t -> kpiTile(cells[i], t, key) }
}

fun Ui.progress(r: Rect, fraction: Double, color: Int = Palette.brass) {
    drawBar(r, fraction.toFloat(), color)
}

fun Ui.meter(r: Rect, value: Int, max: Int, severityAt: (Int) -> Severity) {
    val segments = max.coerceAtLeast(1)
    val cells = r.columns(segments, 2)
    cells.forEachIndexed { i, c ->
        val filled = i < value
        Draw.fill(g, c, if (filled) severityAt(i + 1).color else Palette.alpha(Palette.border, 0xC0))
    }
}

fun Ui.chip(
    x: Int, y: Int, label: String, color: Int = Palette.textSecondary, icon: Icon? = null, tip: String? = null, key: Any? = null,
    selected: Boolean = false, interactive: Boolean = false, maxWidth: Int? = null
): Int {
    val k = key ?: autoKey("chip:$label")
    val extra = 10 + (if (icon != null) Draw.ICON - 1 else 0)
    val textW = Draw.width(label).let { if (maxWidth != null) minOf(it, (maxWidth - extra).coerceAtLeast(8)) else it }
    val w = textW + extra
    val r = Rect(x, y, w, 13)
    val fill = when {
        selected -> 0x50
        interactive && hovering(r) -> 0x3C
        else -> 0x24
    }
    Draw.tinted(g, Sprites.CHIP, r, Palette.alpha(color, fill))
    if (selected) Draw.outline(g, r, Palette.alpha(color, 0xC0))
    var tx = x + 5
    icon?.let { tx += Draw.leadIcon(g, it, tx - 1, r.centerY) - 1 }
    fitText(tx, y + 3, textW, label, color, key = "$k:text")
    tooltip(k, r, tip)
    return w
}

fun Ui.statusPill(x: Int, y: Int, label: String, severity: Severity, tip: String? = null, key: Any = "pill:$label", maxWidth: Int? = null): Int {
    val textW = Draw.width(label).let { if (maxWidth != null) minOf(it, (maxWidth - 16).coerceAtLeast(8)) else it }
    val w = textW + 16
    val r = Rect(x, y, w, 13)
    Draw.tinted(g, Sprites.CHIP, r, severity.tint)
    Draw.fill(g, Rect(x + 4, y + 4, 5, 5), severity.color)
    fitText(x + 12, y + 3, textW, label, severity.color, key = "$key:text")
    tooltip(key, r, tip)
    return w
}

fun Ui.money(x: Int, y: Int, amount: Long, signed: Boolean = false, color: Int? = null): Int {
    val text = if (signed) Format.signed(amount) else Format.number(amount)
    val c = color ?: when {
        signed && amount > 0 -> Palette.success
        signed && amount < 0 -> Palette.danger
        amount < 0 -> Palette.danger
        else -> Palette.money
    }
    val end = Draw.text(g, text, x, y, c)
    return Draw.component(g, Glyphs.component(Glyphs.Glyph.COIN), end + 1, y, Palette.money) - x
}

fun moneyWidth(amount: Long, signed: Boolean = false) = Draw.width(if (signed) Format.signed(amount) else Format.number(amount)) + 10

fun Ui.moneyRight(right: Int, y: Int, amount: Long, signed: Boolean = false, color: Int? = null) =
    money(right - moneyWidth(amount, signed), y, amount, signed, color)

fun Ui.banner(r: Rect, severity: Severity, title: String, body: String? = null, action: String? = null, key: Any = "banner:$title"): Boolean {
    Draw.fill(g, r, severity.tint)
    Draw.outline(g, r, severity.edge)
    Draw.fill(g, r.left(2), severity.color)
    Draw.icon(g, iconFor(severity), r.x + 5, r.y + (r.h - 16) / 2)
    val actionW = action?.let { buttonWidth(it) } ?: 0
    val textW = (r.w - 34 - actionW).coerceAtLeast(20)
    if (body == null) fitText(r.x + 25, Draw.textY(r), textW, title, severity.color, TextStyle.HEADING, key = "$key:title")
    else {
        fitText(r.x + 25, r.y + (r.h - 18) / 2, textW, title, severity.color, TextStyle.HEADING, key = "$key:title")
        fitText(r.x + 25, r.y + (r.h - 18) / 2 + 10, textW, body, Palette.textSecondary, key = "$key:body")
    }
    return action != null && button(Rect(r.right - actionW - 4, r.y + (r.h - SMALL_H) / 2, actionW, SMALL_H), action, key = "$key:action")
}

fun Ui.noticeBar(r: Rect, icon: Icon, text: String, severity: Severity = Severity.WARNING, actionLabel: String? = null, key: Any = "notice:$text", actionIcon: Icon? = null): Boolean {
    Draw.box(g, r, severity.tint, severity.edge)
    Draw.leadIcon(g, icon, r.x + 6, r.centerY)
    val actionW = actionLabel?.let { buttonWidth(it, actionIcon) } ?: 0
    fitText(r.x + 22, Draw.textY(r), (r.w - actionW - 32).coerceAtLeast(0), text, severity.color, key = "$key:text")
    return actionLabel != null && edgeButton(Rect(r.x, r.y + (r.h - SMALL_H) / 2, r.w - 2, SMALL_H), actionLabel, actionIcon, key = "$key:action")
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
        val lx = (x - Draw.width(s.label) / 2).coerceIn(r.x, max(r.x, r.right - Draw.width(s.label)))
        Draw.text(g, s.label, lx, y + 10, if (s.state == StepState.PENDING) Palette.textMuted else Palette.text)
        val dx = (x - Draw.width(s.detail) / 2).coerceIn(r.x, max(r.x, r.right - Draw.width(s.detail)))
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
    if (hover && enabled) Draw.fill(g, r.inset(1), Palette.alpha(Palette.washLight, 0x60))
    if (selected) Draw.outline(g, r, Palette.brass)
    val x = r.x + (r.w - 16) / 2
    val y = r.y + (r.h - 16) / 2
    g.renderItem(stack, x, y)
    g.renderItemDecorations(Draw.font, stack, x, y, count)
    if (hover && !stack.isEmpty) itemTooltip(stack)
    if (hover && enabled) cursor = Cursor.HAND
    return enabled && pressed(r) != null
}

fun Ui.itemIcon(x: Int, y: Int, stack: ItemStack, key: Any) {
    if (stack.isEmpty) return
    g.renderItem(stack, x, y)
    if (hover(key, Rect(x, y, ITEM_ICON, ITEM_ICON))) itemTooltip(stack)
}

const val ITEM_ICON = 16

internal fun Ui.itemTooltip(stack: ItemStack) = overlay(5) {
    val scale = UiScale.factor
    g.pose().pushPose()
    g.pose().setIdentity()
    g.pose().translate(0f, 0f, 100f * layer)
    g.renderTooltip(Draw.font, stack, (mouseX * scale).toInt(), (mouseY * scale).toInt())
    g.pose().popPose()
}

class ChipSpec(val label: String, val color: Int = Palette.textSecondary, val icon: Icon? = null, val selected: Boolean = false)

fun Ui.chipFlow(stack: Stack, chips: List<ChipSpec>, key: String): Int? {
    var clicked: Int? = null
    var row = stack.take(CHIP_H)
    var x = row.x
    chips.forEachIndexed { i, chip ->
        val width = Draw.width(chip.label) + 12 + if (chip.icon != null) Draw.ICON else 0
        if (x + width > row.right && x > row.x) { row = stack.take(CHIP_H); x = row.x }
        val box = Rect(x, row.y, width, CHIP_H)
        val hit = clickable("$key:$i", box)
        chip(x, row.y, chip.label, chip.color, chip.icon, key = "$key:$i", selected = chip.selected, interactive = true)
        focusRing("$key:$i", box)
        if (hit) clicked = i
        x += width + 3
    }
    return clicked
}

fun Ui.property(stack: Stack, label: String, value: String, color: Int = Palette.text): Rect = property(stack.take(PROPERTY_ROW_H), label, value, color)

private const val CHIP_H = 13
private const val PROPERTY_ROW_H = 11
