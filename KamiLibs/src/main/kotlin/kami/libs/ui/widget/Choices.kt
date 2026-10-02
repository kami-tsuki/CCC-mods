package kami.libs.ui.widget

import kami.libs.ui.anim.anim
import kami.libs.ui.core.Cursor
import kami.libs.ui.core.Rect
import kami.libs.ui.core.Tip
import kami.libs.ui.core.Ui
import kami.libs.ui.style.Draw
import kami.libs.ui.style.Icon
import kami.libs.ui.style.Icons
import kami.libs.ui.style.Palette
import kami.libs.ui.style.Sprites
import kami.libs.ui.style.UiSound
import kami.libs.ui.text.tr
import kotlin.math.max
import kotlin.math.min
import org.lwjgl.glfw.GLFW

class Option<T>(
    val value: T, val label: String, val icon: Icon? = null, val description: String? = null,
    val color: Int? = null, val disabledReason: String? = null, val lock: Lock? = null
) {
    val reason: String? get() = disabledReason ?: lock?.reason
}

enum class PopoverAlign { START, END }

fun Ui.popover(anchor: Rect, w: Int, h: Int, align: PopoverAlign = PopoverAlign.START, shadow: Int = 2, onOutside: () -> Unit = {}, content: Ui.(Rect) -> Unit) {
    val below = anchor.bottom + 2 + h <= screen.bottom - 4
    val x = when (align) {
        PopoverAlign.START -> min(anchor.x, screen.right - w - 4)
        PopoverAlign.END -> anchor.right - w
    }
    val box = Rect(x, if (below) anchor.bottom + 2 else anchor.y - 2 - h, w, h)
    block(box)
    Draw.shadow(g, box, shadow)
    Draw.sprite(g, Sprites.POPOVER, box)
    content(box)
    if (input.presses.any { !it.consumed && !box.contains(it.x, it.y) && !anchor.contains(it.x, it.y) }) onOutside()
}

fun Ui.clickable(key: Any, r: Rect, enabled: Boolean = true): Boolean {
    focusable(key)
    val hover = hover(key, r)
    if (enabled && hover) cursor = Cursor.HAND
    val hit = enabled && (pressed(r) != null || activatedByKey(key))
    if (hit) UiSound.click()
    return hit
}

fun Ui.checkbox(r: Rect, label: String, checked: Boolean?, enabled: Boolean = true, disabledReason: String? = null, tip: String? = null, key: Any = "check:$label"): Boolean? {
    val box = Rect(r.x, r.y + (r.h - 10) / 2, 10, 10)
    val hit = clickable(key, r, enabled)
    Draw.sprite(g, when (checked) { true -> Sprites.CHECKBOX_ON; null -> Sprites.CHECKBOX_MIXED; false -> Sprites.CHECKBOX }, box)
    if (hovering(r) && enabled) Draw.outline(g, box, Palette.borderStrong)
    val shown = Draw.fit(label, r.w - 14)
    if (label.isNotEmpty()) Draw.text(g, shown, r.x + 14, r.y + (r.h - 8) / 2, if (enabled) Palette.textSecondary else Palette.textDisabled)
    controlTip(key, r, enabled, disabledReason, tip ?: label.takeIf { it != shown })
    focusRing(key, box)
    return if (hit) checked != true else null
}

fun Ui.toggle(r: Rect, on: Boolean, label: String = "", enabled: Boolean = true, disabledReason: String? = null, tip: String? = null, key: Any = "toggle:$label"): Boolean? {
    val track = Rect(r.x, r.y + (r.h - 10) / 2, 20, 10)
    val hit = clickable(key, r, enabled)
    val t = anim("toggle:$key", if (on) 1f else 0f)
    Draw.sprite(g, if (on) Sprites.TOGGLE_ON else Sprites.TOGGLE, track)
    Draw.sprite(g, Sprites.KNOB, Rect(track.x + 1 + (10 * t).toInt(), track.y + 1, 8, 8))
    val shown = Draw.fit(label, r.w - 26)
    if (label.isNotEmpty()) Draw.text(g, shown, r.x + 25, r.y + (r.h - 8) / 2, if (enabled) Palette.textSecondary else Palette.textDisabled)
    controlTip(key, r, enabled, disabledReason, tip ?: label.takeIf { it != shown })
    focusRing(key, track)
    return if (hit) !on else null
}

fun <T> Ui.radioGroup(r: Rect, options: List<Option<T>>, selected: T, enabled: Boolean = true, rowHeight: Int = 14, key: Any = "radio"): T? {
    var result: T? = null
    var y = r.y
    options.forEach { o ->
        val descH = o.description?.let { Draw.paragraphHeight(it, r.w - 16) } ?: 0
        val row = Rect(r.x, y, r.w, rowHeight + descH)
        val usable = enabled && o.reason == null
        if (clickable("$key:${o.label}", row, usable)) result = o.value
        Draw.sprite(g, if (o.value == selected) Sprites.RADIO_ON else Sprites.RADIO, Rect(r.x, y + (rowHeight - 10) / 2, 10, 10))
        var tx = r.x + 14
        o.icon?.let { tx += Draw.leadIcon(g, it, tx, y + rowHeight / 2) }
        Draw.text(g, o.label, tx, y + (rowHeight - 8) / 2, if (usable) (if (o.value == selected) Palette.text else Palette.textSecondary) else Palette.textDisabled)
        o.description?.let { Draw.paragraph(g, it, r.x + 14, y + rowHeight, r.w - 14, Palette.textMuted) }
        o.reason?.let { reason -> tooltip("$key:${o.label}", row) { Tip.disabled(reason) } }
        y += row.h + 2
    }
    return result
}

fun radioGroupHeight(options: List<Option<*>>, width: Int, rowHeight: Int = 14) =
    options.sumOf { rowHeight + (it.description?.let { d -> Draw.paragraphHeight(d, width - 14) } ?: 0) + 2 }

fun <T> Ui.segmented(r: Rect, options: List<Option<T>>, selected: T, enabled: Boolean = true, key: Any = "segmented"): T? {
    var result: T? = null
    val cells = r.columns(options.size, 0)
    Draw.sprite(g, Sprites.SUNKEN, r)
    options.forEachIndexed { i, o ->
        val cell = cells[i]
        val usable = enabled && o.reason == null
        val chosen = o.value == selected
        val hover = hovering(cell) && usable
        if (clickable("$key:$i", cell, usable)) result = o.value
        if (chosen) Draw.sprite(g, Sprites.Look.SECONDARY.of(hover, false, true), cell.inset(1))
        else if (hover) Draw.fill(g, cell.inset(1), Palette.hover)
        val iconW = if (o.icon == null && o.lock == null) 0 else if (o.label.isEmpty()) Draw.ICON - 4 else Draw.ICON
        val content = iconW + Draw.width(o.label)
        var x = cell.x + max(3, (cell.w - content) / 2)
        (if (o.lock != null) Icons.LOCK else o.icon)?.let { Draw.leadIcon(g, it, x, cell.centerY, if (usable) null else Palette.iconOff) }
        x += iconW
        val shown = Draw.fit(o.label, cell.right - x - 3)
        if (o.label.isNotEmpty()) Draw.text(g, shown, x, cell.y + (cell.h - 8) / 2, when {
            !usable -> Palette.textDisabled
            chosen -> Palette.text
            else -> Palette.textMuted
        })
        if (chosen) Draw.hline(g, cell.x + 2, cell.bottom - 2, cell.w - 4, Palette.brass)
        if (i > 0 && !chosen && options.getOrNull(i - 1)?.value != selected) Draw.vline(g, cell.x, cell.y + 3, cell.h - 6, Palette.borderSubtle)
        val tip = o.reason?.let { Tip.disabled(it) } ?: o.description?.let { Tip.text(it, o.label.ifEmpty { null }) } ?: o.label.takeIf { it != shown }?.let { Tip.text(it) }
        tooltip("$key:$i", cell) { tip }
    }
    return result
}

fun <T> Ui.select(
    r: Rect, options: List<Option<T>>, selected: T?, placeholder: String = tr("kami_libs.common.choose"), enabled: Boolean = true,
    disabledReason: String? = null, key: Any = "select", searchable: Boolean = options.size > 7, menuWidth: Int = r.w
): T? {
    val state = remember("open:$key") { booleanArrayOf(false) }
    val pick = remember("pick:$key") { Picked() }
    @Suppress("UNCHECKED_CAST")
    val deferred = if (pick.has) (pick.value as T).also { pick.clear() } else null
    val filter = remember("filter:$key") { TextState() }
    focusable(key)
    val hover = hover(key, r)
    if (enabled && hover) cursor = Cursor.HAND
    val current = options.firstOrNull { it.value == selected }
    Draw.sprite(g, Sprites.input(state[0], false, enabled, hover), r)
    var tx = r.x + 5
    current?.color?.let { Draw.fill(g, Rect(tx, r.centerY - 3, 6, 6), it); tx += 10 }
    current?.icon?.let { tx += Draw.leadIcon(g, it, tx, r.centerY) }
    Draw.text(g, Draw.fit(current?.label ?: placeholder, r.right - tx - 14), tx, r.y + (r.h - 8) / 2, if (current == null) Palette.textMuted else if (enabled) Palette.text else Palette.textDisabled)
    Draw.tintedIcon(g, Icons.CHEVRON_DOWN, r.right - Draw.ICON - 1, r.y + (r.h - Draw.ICON) / 2, Draw.ICON, if (enabled) Palette.textSecondary else Palette.textDisabled)
    if (!enabled && disabledReason != null) tooltip(key, r) { Tip.disabled(disabledReason) } else current?.description?.let { d -> tooltip(key, r) { Tip.text(d, current.label) } }
    focusRing(key, r)
    if (enabled && (pressed(r) != null || activatedByKey(key))) {
        state[0] = !state[0]
        filter.set("")
        UiSound.click()
    }
    if (!state[0]) return deferred
    onEscape(50) { state[0] = false }
    overlay {
        val visible = options.filter { filter.text.isBlank() || it.label.contains(filter.text, true) }
        val rowH = if (visible.any { it.description != null }) 22 else 14
        val searchH = if (searchable) 20 else 0
        val height = max(min(visible.size * rowH + 6 + searchH, 220), 22)
        popover(r, menuWidth, height, shadow = 1, onOutside = { state[0] = false }) { menu ->
            var area = menu.inset(2)
            if (searchable) {
                textField(area.top(16), filter, tr("kami_libs.common.filter"), Icons.SEARCH, key = "filterField:$key", autoFocus = true)
                area = area.dropTop(16, 2)
            }
            scroll("menu:$key", area, visible.size * rowH) { content ->
                visible.forEachIndexed { i, o ->
                    val row = Rect(content.x, content.y + i * rowH, content.w, rowH)
                    val usable = o.reason == null
                    val over = hovering(row) && usable
                    if (over) { Draw.fill(g, row, Palette.hover); cursor = Cursor.HAND }
                    if (o.value == selected) Draw.fill(g, row.left(2), Palette.brass)
                    var x = row.x + 5
                    o.color?.let { Draw.fill(g, Rect(x, row.y + 4, 6, 6), it); x += 10 }
                    o.icon?.let { x += Draw.leadIcon(g, it, x, row.y + 7) }
                    val chipW = o.lock?.let { lockChipWidth(it) + 4 } ?: 0
                    Draw.text(g, Draw.fit(o.label, row.right - x - 4 - chipW), x, row.y + 3, if (usable) Palette.text else Palette.textDisabled)
                    o.lock?.let { lockChip(row.right - chipW, row.y + 3, it, "opt:$key:$i:lock") }
                    o.description?.let { Draw.text(g, Draw.fit(it, row.right - x - 4 - chipW), x, row.y + 12, Palette.textMuted) }
                    o.reason?.let { reason -> tooltip("opt:$key:$i", row) { Tip.disabled(reason) } }
                    o.lock?.let { lockClick(row, it) }
                    if (usable && pressed(row) != null) {
                        pick.put(o.value)
                        state[0] = false
                        UiSound.click()
                    }
                }
            }
            if (input.takeKey(GLFW.GLFW_KEY_ENTER) != null && visible.size == 1) { pick.put(visible[0].value); state[0] = false }
        }
    }
    return deferred
}

private class Picked {
    var has = false
        private set
    var value: Any? = null
        private set

    fun put(v: Any?) { value = v; has = true }
    fun clear() { has = false }
}
