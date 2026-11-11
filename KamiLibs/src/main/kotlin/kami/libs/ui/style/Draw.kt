package kami.libs.ui.style

import com.mojang.blaze3d.systems.RenderSystem
import kami.libs.KamiLibs
import kami.libs.ui.core.Rect
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.Font
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.client.gui.components.PlayerFaceRenderer
import net.minecraft.client.resources.DefaultPlayerSkin
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.Style
import net.minecraft.resources.ResourceLocation
import net.minecraft.util.FormattedCharSequence
import kami.libs.util.toUuidOrNull
import java.util.UUID
import kotlin.math.abs
import kotlin.math.max

object Sprites {
    private fun lib(path: String): ResourceLocation = ResourceLocation.fromNamespaceAndPath(KamiLibs.ID, path)

    val WINDOW = lib("panel/window")
    val PANEL = lib("panel/panel")
    val SUNKEN = lib("panel/sunken")
    val CARD = lib("panel/card")
    val CARD_HOVER = lib("panel/card_hover")
    val TOPBAR = lib("panel/topbar")
    val SIDEBAR = lib("panel/sidebar")
    val TOOLTIP = lib("panel/tooltip")
    val POPOVER = lib("panel/popover")
    val MODAL = lib("panel/modal")
    val MODAL_DANGER = lib("panel/modal_danger")
    val TOAST = lib("panel/toast")
    val HAZARD = lib("pattern/hazard")

    enum class Look(private val base: String) {
        SECONDARY("widget/button"), PRIMARY("widget/button_primary"), DANGER("widget/button_danger"), GHOST("widget/button_ghost");

        fun of(hovered: Boolean, pressed: Boolean, enabled: Boolean): ResourceLocation = lib(
            when {
                !enabled -> "${base}_disabled"
                pressed -> "${base}_pressed"
                hovered -> "${base}_hover"
                else -> base
            }
        )
    }

    fun input(focused: Boolean, invalid: Boolean, enabled: Boolean, hovered: Boolean) = lib(
        when {
            !enabled -> "widget/input_disabled"
            invalid -> "widget/input_invalid"
            focused -> "widget/input_focus"
            hovered -> "widget/input_hover"
            else -> "widget/input"
        }
    )

    val CHECKBOX = lib("widget/checkbox")
    val CHECKBOX_ON = lib("widget/checkbox_on")
    val CHECKBOX_MIXED = lib("widget/checkbox_mixed")
    val RADIO = lib("widget/radio")
    val RADIO_ON = lib("widget/radio_on")
    val TOGGLE = lib("widget/toggle")
    val TOGGLE_ON = lib("widget/toggle_on")
    val KNOB = lib("widget/knob")
    val TRACK = lib("widget/track")
    val FILL = lib("widget/fill")
    val SCROLL_THUMB = lib("widget/scroll_thumb")
    val TAB = lib("widget/tab")
    val TAB_ACTIVE = lib("widget/tab_active")
    val CHIP = lib("widget/chip")
    val BADGE = lib("widget/badge")
    val KEYCAP = lib("widget/keycap")
}

enum class TextStyle(val color: () -> Int, val bold: Boolean = false, val upper: Boolean = false, val scale: Int = 1, val shadow: Boolean = false) {
    DISPLAY({ Palette.text }, scale = 2),
    TITLE({ Palette.text }, bold = true),
    HEADING({ Palette.text }, bold = true),
    BODY({ Palette.textSecondary }),
    LABEL({ Palette.textMuted }, upper = true),
    VALUE({ Palette.text }),
    CAPTION({ Palette.textMuted }),
    LINK({ Palette.link })
}

object Draw {
    val font: Font get() = Minecraft.getInstance().font
    const val LINE = 10
    const val ICON = 14
    private const val NATIVE = 16

    fun textY(y: Int, h: Int) = y + (h - 8) / 2
    fun textY(r: Rect) = textY(r.y, r.h)

    fun fill(g: GuiGraphics, r: Rect, color: Int) { if (!r.isEmpty) g.fill(r.x, r.y, r.right, r.bottom, color) }
    fun hline(g: GuiGraphics, x: Int, y: Int, w: Int, color: Int) = g.fill(x, y, x + w, y + 1, color)
    fun vline(g: GuiGraphics, x: Int, y: Int, h: Int, color: Int) = g.fill(x, y, x + 1, y + h, color)
    fun outline(g: GuiGraphics, r: Rect, color: Int) { if (!r.isEmpty) g.renderOutline(r.x, r.y, r.w, r.h, color) }

    fun box(g: GuiGraphics, r: Rect, fill: Int, border: Int) {
        if (r.isEmpty) return
        if (fill ushr 24 != 0) fill(g, r, fill)
        if (border ushr 24 != 0) outline(g, r, border)
    }

    fun sprite(g: GuiGraphics, id: ResourceLocation, r: Rect) { if (!r.isEmpty && !Skin.paint(g, id, r)) g.blitSprite(id, r.x, r.y, r.w, r.h) }

    fun tinted(g: GuiGraphics, id: ResourceLocation, r: Rect, color: Int) {
        if (r.isEmpty || Skin.paintTinted(g, id, r, color)) return
        RenderSystem.enableBlend()
        RenderSystem.setShaderColor(((color shr 16) and 0xFF) / 255f, ((color shr 8) and 0xFF) / 255f, (color and 0xFF) / 255f, ((color ushr 24) and 0xFF) / 255f)
        sprite(g, id, r)
        RenderSystem.setShaderColor(1f, 1f, 1f, 1f)
    }

    private fun native(size: Int) = if (size % NATIVE == 0 && size > 0) size else ICON

    fun icon(g: GuiGraphics, icon: Icon, x: Int, y: Int, size: Int = ICON) {
        val s = native(size)
        val c = Palette.iconTint
        RenderSystem.enableBlend()
        RenderSystem.setShaderColor(((c shr 16) and 0xFF) / 255f, ((c shr 8) and 0xFF) / 255f, (c and 0xFF) / 255f, 1f)
        g.blitSprite(icon.sprite, x + (size - s) / 2, y + (size - s) / 2, s, s)
        RenderSystem.setShaderColor(1f, 1f, 1f, 1f)
    }
    fun icon(g: GuiGraphics, icon: Icon, r: Rect, size: Int = ICON) = icon(g, icon, r.x + (r.w - size) / 2, r.y + (r.h - size) / 2, size)
    fun tintedIcon(g: GuiGraphics, icon: Icon, x: Int, y: Int, size: Int, color: Int) {
        val s = native(size)
        tinted(g, icon.sprite, Rect(x + (size - s) / 2, y + (size - s) / 2, s, s), color)
    }

    fun leadIcon(g: GuiGraphics, icon: Icon, x: Int, centerY: Int, color: Int? = null): Int {
        if (color == null) icon(g, icon, x - 2, centerY - ICON / 2) else tintedIcon(g, icon, x - 2, centerY - ICON / 2, ICON, color)
        return ICON
    }

    fun marker(g: GuiGraphics, icon: Icon, centerX: Int, centerY: Int) {
        box(g, Rect(centerX - 6, centerY - 6, 12, 12), Palette.alpha(Palette.raised, 0xE0), Palette.alpha(Palette.borderStrong, 0xE0))
        icon(g, icon, centerX - ICON / 2, centerY - ICON / 2)
    }

    fun glow(g: GuiGraphics, r: Rect, color: Int, strength: Float) {
        if (r.isEmpty || strength <= 0.02f) return
        for (i in 1..3) g.renderOutline(r.x - i, r.y - i, r.w + i * 2, r.h + i * 2, Palette.alpha(color, ((0x50 shr (i - 1)) * strength).toInt()))
    }

    fun veil(g: GuiGraphics, r: Rect, color: Int, amount: Float) {
        val shade = Palette.fade(color, amount)
        if (shade != 0) fill(g, r, shade)
    }

    fun veilBox(g: GuiGraphics, r: Rect, appear: Float, color: Int = Palette.canvas, strength: Float = 1f) = veil(g, r, color, (1f - appear) * strength)

    fun thinBar(g: GuiGraphics, r: Rect, fraction: Float, color: Int) {
        fill(g, r, Palette.alpha(Palette.border, 0xC0))
        fill(g, r.withWidth((r.w * fraction.coerceIn(0f, 1f)).toInt()), color)
    }

    fun dashedRect(g: GuiGraphics, r: Rect, color: Int) {
        dashed(g, r.x, r.y, r.right - 1, r.y, color)
        dashed(g, r.x, r.bottom - 1, r.right - 1, r.bottom - 1, color)
        dashed(g, r.x, r.y, r.x, r.bottom - 1, color)
        dashed(g, r.right - 1, r.y, r.right - 1, r.bottom - 1, color)
    }

    fun shadow(g: GuiGraphics, r: Rect, depth: Int) {
        for (i in 1..depth.coerceAtMost(2)) g.fill(r.x + i, r.y + i, r.right + i, r.bottom + i, Palette.alpha(0, 0x30 / i))
    }

    private fun styled(text: String, style: TextStyle): Component {
        val body = if (style.upper) text.uppercase() else text
        return Component.literal(body).withStyle(Style.EMPTY.withBold(style.bold))
    }

    private fun plain(style: TextStyle) = !style.bold && !style.upper

    private const val WIDTH_CACHE = 1024
    private val widths = Array(4) { object : LinkedHashMap<String, Int>(256, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Int>) = size > WIDTH_CACHE
    } }
    fun clearWidths() { widths.forEach { it.clear() }; fits.clear() }

    fun width(text: String, style: TextStyle = TextStyle.BODY): Int {
        val cache = widths[(if (style.bold) 1 else 0) + (if (style.upper) 2 else 0)]
        val base = cache.getOrPut(text) { if (plain(style)) font.width(text) else font.width(styled(text, style)) }
        return base * style.scale
    }

    private fun invisible(color: Int) = color ushr 24 < Palette.MIN_VISIBLE_ALPHA

    var hover: ((GuiGraphics, String, Int, Int, Int) -> Unit)? = null

    fun text(g: GuiGraphics, text: String, x: Int, y: Int, color: Int = Palette.text, shadow: Boolean = false): Int =
        if (invisible(color)) x + font.width(text) else g.drawString(font, text, x, y, color, shadow).also { hover?.invoke(g, text, x, y, it - x) }

    fun text(g: GuiGraphics, text: String, x: Int, y: Int, style: TextStyle, color: Int = style.color()): Int {
        val w = width(text, style)
        if (invisible(color)) return x + w
        hover?.invoke(g, text, x, y, w)
        val scaled = style.scale != 1
        if (scaled) {
            g.pose().pushPose()
            g.pose().translate(x.toFloat(), y.toFloat(), 0f)
            g.pose().scale(style.scale.toFloat(), style.scale.toFloat(), 1f)
        }
        val dx = if (scaled) 0 else x
        val dy = if (scaled) 0 else y
        if (plain(style)) g.drawString(font, text, dx, dy, color, style.shadow) else g.drawString(font, styled(text, style), dx, dy, color, style.shadow)
        if (scaled) g.pose().popPose()
        return x + w
    }

    fun component(g: GuiGraphics, c: Component, x: Int, y: Int, color: Int = Palette.text) = g.drawString(font, c, x, y, color, false)

    fun textRight(g: GuiGraphics, text: String, right: Int, y: Int, color: Int = Palette.text, style: TextStyle = TextStyle.BODY) =
        text(g, text, right - width(text, style), y, style, color)

    fun textCentered(g: GuiGraphics, text: String, r: Rect, color: Int = Palette.text, style: TextStyle = TextStyle.BODY) =
        text(g, text, r.x + (r.w - width(text, style)) / 2, r.y + (r.h - 8 * style.scale) / 2, style, color)

    private const val FIT_CACHE = 512
    private class FitKey(val text: String, val width: Int, val style: TextStyle) {
        override fun equals(other: Any?) = other is FitKey && width == other.width && style == other.style && text == other.text
        override fun hashCode() = (text.hashCode() * 31 + width) * 31 + style.hashCode()
    }
    private val fits = object : LinkedHashMap<FitKey, String>(128, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<FitKey, String>) = size > FIT_CACHE
    }

    fun fit(text: String, width: Int, style: TextStyle = TextStyle.BODY): String {
        if (width(text, style) <= width) return text
        if (width <= 0) return ""
        return fits.getOrPut(FitKey(text, width, style)) { truncate(text, width, style) }
    }

    private fun truncate(text: String, width: Int, style: TextStyle): String {
        var lo = 0
        var hi = text.length
        while (lo < hi) {
            val mid = (lo + hi + 1) ushr 1
            if (width(text.substring(0, mid) + "…", style) <= width) lo = mid else hi = mid - 1
        }
        if (lo > 0) return text.substring(0, lo) + "…"
        var keep = text.length
        while (keep > 0 && width(text.substring(0, keep), style) > width) keep--
        return text.substring(0, keep)
    }

    fun wrap(text: String, width: Int): List<FormattedCharSequence> = font.split(Component.literal(text), max(10, width))

    fun lines(text: String, width: Int): List<String> = font.splitter.splitLines(text, max(10, width), Style.EMPTY).map { it.string }

    fun paragraph(g: GuiGraphics, text: String, x: Int, y: Int, width: Int, color: Int = Palette.textSecondary, maxLines: Int = Int.MAX_VALUE): Int {
        val lines = wrap(text, width).take(maxLines)
        lines.forEachIndexed { i, line -> g.drawString(font, line, x, y + i * LINE, color, false) }
        return lines.size * LINE
    }

    fun paragraphHeight(text: String, width: Int) = wrap(text, width).size * LINE

    fun line(g: GuiGraphics, x0: Int, y0: Int, x1: Int, y1: Int, color: Int, thickness: Int = 1) {
        val dx = x1 - x0
        val dy = y1 - y0
        val steps = max(abs(dx), abs(dy))
        if (steps == 0) return g.fill(x0, y0, x0 + thickness, y0 + thickness, color)
        for (i in 0..steps) {
            val x = x0 + dx * i / steps
            val y = y0 + dy * i / steps
            g.fill(x, y, x + thickness, y + thickness, color)
        }
        g.flush()
    }

    fun dashed(g: GuiGraphics, x0: Int, y0: Int, x1: Int, y1: Int, color: Int, dash: Int = 3) {
        val steps = max(abs(x1 - x0), abs(y1 - y0))
        if (steps == 0) return
        for (i in 0..steps) if ((i / dash) % 2 == 0) {
            val x = x0 + (x1 - x0) * i / steps
            val y = y0 + (y1 - y0) * i / steps
            g.fill(x, y, x + 1, y + 1, color)
        }
        g.flush()
    }

    fun marching(g: GuiGraphics, r: Rect, color: Int, phase: Int) {
        val perimeter = 2 * (r.w + r.h)
        for (i in 0 until perimeter) {
            if (((i + phase) / 3) % 2 != 0) continue
            val (x, y) = when {
                i < r.w -> r.x + i to r.y
                i < r.w + r.h -> r.right - 1 to r.y + (i - r.w)
                i < 2 * r.w + r.h -> r.right - 1 - (i - r.w - r.h) to r.bottom - 1
                else -> r.x to r.bottom - 1 - (i - 2 * r.w - r.h)
            }
            g.fill(x, y, x + 1, y + 1, color)
        }
        g.flush()
    }

    fun hatch(g: GuiGraphics, r: Rect, color: Int, spacing: Int = 4, phase: Int = 0) {
        if (r.isEmpty) return
        var k = -r.h + (phase % spacing)
        while (k < r.w) {
            for (i in maxOf(0, -k)..minOf(r.h - 1, r.w - 1 - k)) {
                val x = r.x + k + i
                g.fill(x, r.bottom - 1 - i, x + 1, r.bottom - i, color)
            }
            k += spacing
        }
    }

    fun head(g: GuiGraphics, id: String, x: Int, y: Int, size: Int) {
        val uuid = id.toUuidOrNull()
        val mc = Minecraft.getInstance()
        val skin = uuid?.let { mc.connection?.getPlayerInfo(it)?.skin } ?: DefaultPlayerSkin.get(uuid ?: UUID(0, 0))
        PlayerFaceRenderer.draw(g, skin, x, y, size)
    }
}
