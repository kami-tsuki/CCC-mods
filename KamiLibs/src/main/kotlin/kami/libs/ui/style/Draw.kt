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
    val PARCHMENT = lib("pattern/parchment")
    val HATCH = lib("pattern/hatch")

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
    DISPLAY({ Palette.text }, scale = 2, shadow = true),
    TITLE({ Palette.text }, bold = true, upper = true),
    HEADING({ Palette.text }, bold = true),
    BODY({ Palette.textSecondary }),
    LABEL({ Palette.textMuted }, upper = true),
    VALUE({ Palette.text }),
    CAPTION({ Palette.textMuted }),
    LINK({ Palette.link })
}

object Draw {
    val font: Font get() = Minecraft.getInstance().font
    const val LINE = 11

    fun fill(g: GuiGraphics, r: Rect, color: Int) { if (!r.isEmpty) g.fill(r.x, r.y, r.right, r.bottom, color) }
    fun hline(g: GuiGraphics, x: Int, y: Int, w: Int, color: Int) = g.fill(x, y, x + w, y + 1, color)
    fun vline(g: GuiGraphics, x: Int, y: Int, h: Int, color: Int) = g.fill(x, y, x + 1, y + h, color)
    fun outline(g: GuiGraphics, r: Rect, color: Int) { if (!r.isEmpty) g.renderOutline(r.x, r.y, r.w, r.h, color) }

    fun sprite(g: GuiGraphics, id: ResourceLocation, r: Rect) { if (!r.isEmpty) g.blitSprite(id, r.x, r.y, r.w, r.h) }

    fun tinted(g: GuiGraphics, id: ResourceLocation, r: Rect, color: Int) {
        RenderSystem.enableBlend()
        RenderSystem.setShaderColor(((color shr 16) and 0xFF) / 255f, ((color shr 8) and 0xFF) / 255f, (color and 0xFF) / 255f, ((color ushr 24) and 0xFF) / 255f)
        sprite(g, id, r)
        RenderSystem.setShaderColor(1f, 1f, 1f, 1f)
    }

    fun icon(g: GuiGraphics, icon: Icon, x: Int, y: Int, size: Int = 16) = g.blitSprite(icon.sprite, x, y, size, size)
    fun icon(g: GuiGraphics, icon: Icon, r: Rect, size: Int = 16) = icon(g, icon, r.x + (r.w - size) / 2, r.y + (r.h - size) / 2, size)
    fun tintedIcon(g: GuiGraphics, icon: Icon, x: Int, y: Int, size: Int, color: Int) = tinted(g, icon.sprite, Rect(x, y, size, size), color)

    fun shadow(g: GuiGraphics, r: Rect, depth: Int) {
        for (i in 1..depth) g.fill(r.x + i, r.y + i, r.right + i, r.bottom + i, Palette.alpha(0, 0x50 / i))
    }

    fun styled(text: String, style: TextStyle): Component {
        val body = if (style.upper) text.uppercase() else text
        return Component.literal(body).withStyle(Style.EMPTY.withBold(style.bold))
    }

    fun width(text: String, style: TextStyle = TextStyle.BODY) = font.width(styled(text, style)) * style.scale

    fun text(g: GuiGraphics, text: String, x: Int, y: Int, color: Int = Palette.text, shadow: Boolean = false): Int = g.drawString(font, text, x, y, color, shadow)

    fun text(g: GuiGraphics, text: String, x: Int, y: Int, style: TextStyle, color: Int = style.color()): Int {
        val c = styled(text, style)
        if (style.scale == 1) return g.drawString(font, c, x, y, color, style.shadow)
        g.pose().pushPose()
        g.pose().translate(x.toFloat(), y.toFloat(), 0f)
        g.pose().scale(style.scale.toFloat(), style.scale.toFloat(), 1f)
        g.drawString(font, c, 0, 0, color, style.shadow)
        g.pose().popPose()
        return x + font.width(c) * style.scale
    }

    fun component(g: GuiGraphics, c: Component, x: Int, y: Int, color: Int = Palette.text) = g.drawString(font, c, x, y, color, false)

    fun textRight(g: GuiGraphics, text: String, right: Int, y: Int, color: Int = Palette.text, style: TextStyle = TextStyle.BODY) =
        text(g, text, right - width(text, style), y, style, color)

    fun textCentered(g: GuiGraphics, text: String, r: Rect, color: Int = Palette.text, style: TextStyle = TextStyle.BODY) =
        text(g, text, r.x + (r.w - width(text, style)) / 2, r.y + (r.h - 8 * style.scale) / 2, style, color)

    fun fit(text: String, width: Int, style: TextStyle = TextStyle.BODY): String {
        if (width(text, style) <= width) return text
        var t = text
        while (t.isNotEmpty() && width("$t…", style) > width) t = t.dropLast(1)
        return "$t…"
    }

    fun wrap(text: String, width: Int): List<FormattedCharSequence> = font.split(Component.literal(text), max(10, width))

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
        g.drawManaged {
            for (i in 0..steps) {
                val x = x0 + dx * i / steps
                val y = y0 + dy * i / steps
                g.fill(x, y, x + thickness, y + thickness, color)
            }
        }
    }

    fun dashed(g: GuiGraphics, x0: Int, y0: Int, x1: Int, y1: Int, color: Int, dash: Int = 3) {
        val steps = max(abs(x1 - x0), abs(y1 - y0))
        if (steps == 0) return
        g.drawManaged {
            for (i in 0..steps) if ((i / dash) % 2 == 0) {
                val x = x0 + (x1 - x0) * i / steps
                val y = y0 + (y1 - y0) * i / steps
                g.fill(x, y, x + 1, y + 1, color)
            }
        }
    }

    fun marching(g: GuiGraphics, r: Rect, color: Int, phase: Int) {
        g.drawManaged {
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
        }
    }

    fun hatch(g: GuiGraphics, r: Rect, color: Int, spacing: Int = 4, phase: Int = 0) {
        if (r.isEmpty) return
        g.enableScissor(r.x, r.y, r.right, r.bottom)
        g.drawManaged {
            var k = -r.h + (phase % spacing)
            while (k < r.w) {
                for (i in 0 until r.h) {
                    val x = r.x + k + i
                    if (x >= r.x && x < r.right) g.fill(x, r.bottom - 1 - i, x + 1, r.bottom - i, color)
                }
                k += spacing
            }
        }
        g.disableScissor()
    }

    fun head(g: GuiGraphics, id: String, x: Int, y: Int, size: Int) {
        val uuid = runCatching { UUID.fromString(id) }.getOrNull()
        val mc = Minecraft.getInstance()
        val skin = uuid?.let { mc.connection?.getPlayerInfo(it)?.skin } ?: DefaultPlayerSkin.get(uuid ?: UUID(0, 0))
        PlayerFaceRenderer.draw(g, skin, x, y, size)
    }
}
