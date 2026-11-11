package kami.libs.ui.widget

import kami.libs.ui.core.Rect
import kami.libs.ui.core.Tip
import kami.libs.ui.core.Ui
import kami.libs.ui.style.Draw
import kami.libs.ui.style.Icons
import kami.libs.ui.style.Palette
import kami.libs.ui.text.tr
import java.util.Locale
import kotlin.math.pow

const val COLOR_PICKER_H = 60
private const val SWATCH_COLUMNS = 8
private const val SWATCH_H = 12
private const val MIN_CONTRAST = 3.0
private const val BLOCK_CONTRAST = 2.0
private const val DIMMED_ALPHA = 70
private const val OPAQUE = 0xFF000000.toInt()

val DEFAULT_COLORS = intArrayOf(
    0xE53935, 0xD81B60, 0xAB47BC, 0x7E57C2, 0x5C6BC0, 0x1E88E5, 0x039BE5, 0x00ACC1,
    0x00897B, 0x43A047, 0x7CB342, 0xC0CA33, 0xFDD835, 0xFFB300, 0xFB8C00, 0xF4511E,
    0x8D6E63, 0x757575, 0x546E7A, 0xFFFFFF, 0xBDBDBD, 0xFF7043, 0xEC407A, 0x26A69A
)

private fun luminance(rgb: Int): Double {
    fun channel(v: Int) = (v / 255.0).let { if (it <= 0.03928) it / 12.92 else ((it + 0.055) / 1.055).pow(2.4) }
    return 0.2126 * channel(rgb shr 16 and 0xFF) + 0.7152 * channel(rgb shr 8 and 0xFF) + 0.0722 * channel(rgb and 0xFF)
}

fun contrastRatio(a: Int, b: Int): Double {
    val la = luminance(a)
    val lb = luminance(b)
    return (maxOf(la, lb) + 0.05) / (minOf(la, lb) + 0.05)
}

fun colorProblem(rgb: Int): String? =
    contrastRatio(rgb, Palette.canvas).takeIf { it < BLOCK_CONTRAST }?.let { tr("kami_libs.color.blocked", "%.1f:1".format(Locale.ROOT, it), "%.1f:1".format(Locale.ROOT, BLOCK_CONTRAST)) }

internal fun parseHex(text: String): Int? = text.removePrefix("#").takeIf { it.length == 6 }?.toIntOrNull(16)

internal fun formatHex(rgb: Int) = "#%06X".format(Locale.ROOT, rgb and 0xFFFFFF)

private val SWATCH_HEX = Array(DEFAULT_COLORS.size) { formatHex(DEFAULT_COLORS[it]) }
private var blockedCanvas = 0
private var blockedSwatches = BooleanArray(0)

private fun blockedFor(canvas: Int): BooleanArray {
    if (blockedSwatches.size != DEFAULT_COLORS.size || blockedCanvas != canvas) {
        blockedSwatches = BooleanArray(DEFAULT_COLORS.size) { contrastRatio(DEFAULT_COLORS[it], canvas) < BLOCK_CONTRAST }
        blockedCanvas = canvas
    }
    return blockedSwatches
}

fun Ui.colorPicker(r: Rect, color: Int, key: Any = "color", enabled: Boolean = true): Int? {
    var result: Int? = null
    val field = remember("hex:$key") { TextState(formatHex(color)) }
    val synced = remember("hexSynced:$key") { intArrayOf(color) }
    if (synced[0] != color) {
        synced[0] = color
        if (parseHex(field.text) != color) field.set(formatHex(color))
    }
    val blocked = blockedFor(Palette.canvas)
    val cells = r.grid(SWATCH_COLUMNS, SWATCH_H, DEFAULT_COLORS.size, 2)
    for (i in DEFAULT_COLORS.indices) {
        val rgb = DEFAULT_COLORS[i]
        val cell = cells[i]
        val k = "$key:swatch:$i"
        val usable = enabled && !blocked[i]
        if (clickable(k, cell, usable)) { result = rgb; field.set(SWATCH_HEX[i]); field.error = null }
        Draw.fill(g, cell, if (usable) OPAQUE or rgb else Palette.alpha(rgb, DIMMED_ALPHA))
        Draw.outline(g, cell, if (rgb == color) Palette.text else Palette.borderSubtle)
        tooltip(k, cell) { Tip.text(if (blocked[i]) colorProblem(rgb) ?: SWATCH_HEX[i] else SWATCH_HEX[i]) }
        focusRing(k, cell)
    }
    val row = Rect(r.x, r.bottom - 16, r.w, 16)
    val preview = Rect(row.x, row.y, 16, 16)
    Draw.fill(g, preview, OPAQUE or color)
    Draw.outline(g, preview, Palette.border)
    val hex = textField(Rect(preview.right + 4, row.y, 66, 16), field, "#RRGGBB", enabled = enabled, maxLength = 7, allow = { it == '#' || it.isDigit() || it.lowercaseChar() in 'a'..'f' }, key = "$key:hex")
    if (enabled && hex.changed) {
        val parsed = parseHex(field.text)
        field.touched = true
        field.error = if (parsed == null) tr("kami_libs.color.invalid") else null
        if (parsed != null && parsed != color) result = parsed
    }
    val ratio = contrastRatio(color, Palette.canvas)
    val ratioText = "%.1f:1".format(Locale.ROOT, ratio)
    val low = ratio < MIN_CONTRAST
    var x = preview.right + 4 + 66 + 6
    x += Draw.text(g, ratioText, x, Draw.textY(row), if (low) Palette.warning else Palette.textMuted) + 6
    if (low) chip(x, row.y + 2, tr("kami_libs.color.low_contrast"), Palette.warning, Icons.WARNING, tr("kami_libs.color.low_contrast.tooltip", ratioText), key = "$key:warn", maxWidth = (r.right - x).coerceAtLeast(0))
    return result
}
