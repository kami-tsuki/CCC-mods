package kami.libs.ui.style

import kami.libs.ui.core.Rect
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.resources.ResourceLocation

object Skin {
    private val painters = HashMap<ResourceLocation, (GuiGraphics, Rect) -> Unit>()
    private val solids = HashSet<ResourceLocation>()

    fun paint(g: GuiGraphics, id: ResourceLocation, r: Rect): Boolean {
        val painter = painters[id] ?: return false
        painter(g, r)
        return true
    }

    fun paintTinted(g: GuiGraphics, id: ResourceLocation, r: Rect, color: Int): Boolean {
        if (id !in solids) return false
        Draw.fill(g, r, color)
        return true
    }

    private fun box(id: ResourceLocation, fill: () -> Int, border: () -> Int) { painters[id] = { g, r -> Draw.box(g, r, fill(), border()) } }
    private fun paint(id: ResourceLocation, painter: (GuiGraphics, Rect) -> Unit) { painters[id] = painter }
    private fun bevel(id: ResourceLocation, fill: () -> Int, topLeft: () -> Int, bottomRight: () -> Int, outline: () -> Int = { 0 }) {
        painters[id] = { g, r -> bevelPaint(g, r, fill(), topLeft(), bottomRight(), outline()) }
    }

    private fun bevelPaint(g: GuiGraphics, r: Rect, fill: Int, topLeft: Int, bottomRight: Int, outline: Int) {
        if (r.isEmpty) return
        var b = r
        if (outline ushr 24 != 0) { Draw.outline(g, r, outline); b = r.inset(1) }
        if (b.isEmpty) return
        if (fill ushr 24 != 0) Draw.fill(g, b, fill)
        Draw.hline(g, b.x, b.y, b.w, topLeft)
        Draw.vline(g, b.x, b.y, b.h, topLeft)
        Draw.hline(g, b.x, b.bottom - 1, b.w, bottomRight)
        Draw.vline(g, b.right - 1, b.y, b.h, bottomRight)
    }

    private fun button(
        look: Sprites.Look, fill: () -> Int, hover: () -> Int, pressed: () -> Int, light: () -> Int, dark: () -> Int,
        disabled: () -> Int, disabledBorder: () -> Int, flat: Boolean = false
    ) {
        if (flat) {
            box(look.of(false, false, true), fill, { 0 })
            box(look.of(true, false, true), hover, { Palette.borderSubtle })
            box(look.of(false, true, true), pressed, { Palette.borderSubtle })
        } else {
            bevel(look.of(false, false, true), fill, light, dark, { Palette.outline })
            bevel(look.of(true, false, true), hover, light, dark, { Palette.outline })
            bevel(look.of(false, true, true), pressed, dark, light, { Palette.outline })
        }
        box(look.of(false, false, false), disabled, disabledBorder)
    }

    private fun check(g: GuiGraphics, r: Rect, color: Int) {
        val x = r.x + (r.w - 7) / 2
        val y = r.y + (r.h - 5) / 2
        for (i in 0..1) g.fill(x + i, y + 2 + i, x + i + 1, y + 4 + i, color)
        for (i in 0..4) g.fill(x + 2 + i, y + 3 - i, x + 3 + i, y + 5 - i, color)
    }

    init {
        bevel(Sprites.WINDOW, { Palette.canvas }, { Palette.bevelLight }, { Palette.bevelDark }, { Palette.outline })
        box(Sprites.PANEL, { Palette.surface }, { Palette.border })
        bevel(Sprites.SUNKEN, { Palette.sunken }, { Palette.bevelInsetDark }, { Palette.bevelLight })
        bevel(Sprites.CARD, { Palette.raised }, { Palette.bevelLight }, { Palette.border })
        bevel(Sprites.CARD_HOVER, { Palette.hover }, { Palette.bevelLight }, { Palette.bevelDark })
        paint(Sprites.TOPBAR) { g, r -> Draw.fill(g, r, Palette.surface); Draw.hline(g, r.x, r.bottom - 1, r.w, Palette.border) }
        paint(Sprites.SIDEBAR) { g, r -> Draw.fill(g, r, Palette.surface); Draw.vline(g, r.right - 1, r.y, r.h, Palette.border) }
        bevel(Sprites.TOOLTIP, { Palette.overlay }, { Palette.bevelLight }, { Palette.bevelDark }, { Palette.outline })
        bevel(Sprites.POPOVER, { Palette.overlay }, { Palette.bevelLight }, { Palette.bevelDark }, { Palette.outline })
        bevel(Sprites.MODAL, { Palette.canvas }, { Palette.bevelLight }, { Palette.bevelDark }, { Palette.outline })
        bevel(Sprites.MODAL_DANGER, { Palette.canvas }, { Palette.bevelLight }, { Palette.bevelDark }, { Palette.danger })
        bevel(Sprites.TOAST, { Palette.overlay }, { Palette.bevelLight }, { Palette.bevelDark }, { Palette.outline })
        paint(Sprites.HAZARD) { g, r -> Draw.fill(g, r, Palette.alpha(Palette.danger, 0x30)); Draw.hline(g, r.x, r.bottom - 1, r.w, Palette.danger) }

        button(Sprites.Look.SECONDARY, { Palette.raised }, { Palette.hover }, { Palette.mix(Palette.raised, Palette.sunken, 0.5f) }, { Palette.bevelLight }, { Palette.bevelDark }, { Palette.surface }, { Palette.borderSubtle })
        button(Sprites.Look.PRIMARY, { Palette.brass }, { Palette.lighten(Palette.brass, 0.15f) }, { Palette.darken(Palette.brass, 0.2f) },
            { Palette.lighten(Palette.brass, 0.4f) }, { Palette.darken(Palette.brass, 0.4f) }, { Palette.alpha(Palette.brass, 0x40) }, { Palette.alpha(Palette.brass, 0x60) })
        button(Sprites.Look.DANGER, { Palette.danger }, { Palette.lighten(Palette.danger, 0.15f) }, { Palette.darken(Palette.danger, 0.2f) },
            { Palette.lighten(Palette.danger, 0.4f) }, { Palette.darken(Palette.danger, 0.4f) }, { Palette.surface }, { Palette.alpha(Palette.danger, 0x70) })
        button(Sprites.Look.GHOST, { 0 }, { Palette.hover }, { Palette.sunken }, { 0 }, { 0 }, { 0 }, { 0 }, flat = true)

        bevel(Sprites.input(focused = false, invalid = false, enabled = true, hovered = false), { Palette.field }, { Palette.bevelInsetDark }, { Palette.bevelLight }, { Palette.border })
        bevel(Sprites.input(focused = false, invalid = false, enabled = true, hovered = true), { Palette.field }, { Palette.bevelInsetDark }, { Palette.bevelLight }, { Palette.borderStrong })
        bevel(Sprites.input(focused = true, invalid = false, enabled = true, hovered = false), { Palette.field }, { Palette.bevelInsetDark }, { Palette.bevelLight }, { Palette.focus })
        bevel(Sprites.input(focused = false, invalid = true, enabled = true, hovered = false), { Palette.field }, { Palette.bevelInsetDark }, { Palette.bevelLight }, { Palette.danger })
        box(Sprites.input(focused = false, invalid = false, enabled = false, hovered = false), { Palette.surface }, { Palette.borderSubtle })

        bevel(Sprites.CHECKBOX, { Palette.field }, { Palette.bevelInsetDark }, { Palette.bevelLight }, { Palette.borderStrong })
        paint(Sprites.CHECKBOX_ON) { g, r -> Draw.box(g, r, Palette.brass, Palette.brass); check(g, r, Palette.textInverse) }
        paint(Sprites.CHECKBOX_MIXED) { g, r -> Draw.box(g, r, Palette.brass, Palette.brass); Draw.fill(g, Rect(r.x + 3, r.centerY - 1, r.w - 6, 2), Palette.textInverse) }
        bevel(Sprites.RADIO, { Palette.field }, { Palette.bevelInsetDark }, { Palette.bevelLight }, { Palette.borderStrong })
        paint(Sprites.RADIO_ON) { g, r -> Draw.box(g, r, Palette.field, Palette.brass); Draw.fill(g, r.inset(3), Palette.brass) }
        bevel(Sprites.TOGGLE, { Palette.sunken }, { Palette.bevelInsetDark }, { Palette.bevelLight }, { Palette.borderStrong })
        box(Sprites.TOGGLE_ON, { Palette.brass }, { Palette.darken(Palette.brass, 0.3f) })
        bevel(Sprites.KNOB, { Palette.raised }, { Palette.bevelLight }, { Palette.bevelDark }, { Palette.borderStrong })
        bevel(Sprites.TRACK, { Palette.sunken }, { Palette.bevelInsetDark }, { Palette.bevelLight })
        box(Sprites.SCROLL_THUMB, { Palette.border }, { 0 })
        paint(Sprites.TAB) { _, _ -> }
        paint(Sprites.TAB_ACTIVE) { g, r -> Draw.fill(g, r, Palette.raised) }
        bevel(Sprites.KEYCAP, { Palette.raised }, { Palette.bevelLight }, { Palette.bevelDark }, { Palette.borderStrong })
        listOf(Sprites.FILL, Sprites.CHIP, Sprites.BADGE).forEach { id -> solids += id; paint(id) { g, r -> Draw.fill(g, r, Palette.text) } }
    }
}
