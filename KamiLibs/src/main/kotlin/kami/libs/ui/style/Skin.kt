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

    private fun button(look: Sprites.Look, fill: () -> Int, border: () -> Int, hover: () -> Int, pressed: () -> Int, disabled: () -> Int, disabledBorder: () -> Int, hoverBorder: () -> Int = border) {
        box(look.of(false, false, true), fill, border)
        box(look.of(true, false, true), hover, hoverBorder)
        box(look.of(false, true, true), pressed, border)
        box(look.of(false, false, false), disabled, disabledBorder)
    }

    private fun check(g: GuiGraphics, r: Rect, color: Int) {
        val x = r.x + (r.w - 7) / 2
        val y = r.y + (r.h - 5) / 2
        for (i in 0..1) g.fill(x + i, y + 2 + i, x + i + 1, y + 4 + i, color)
        for (i in 0..4) g.fill(x + 2 + i, y + 3 - i, x + 3 + i, y + 5 - i, color)
    }

    init {
        box(Sprites.WINDOW, { Palette.canvas }, { Palette.borderStrong })
        box(Sprites.PANEL, { Palette.surface }, { Palette.borderSubtle })
        box(Sprites.SUNKEN, { Palette.sunken }, { Palette.borderSubtle })
        box(Sprites.CARD, { Palette.surface }, { Palette.border })
        box(Sprites.CARD_HOVER, { Palette.hover }, { Palette.borderStrong })
        paint(Sprites.TOPBAR) { g, r -> Draw.fill(g, r, Palette.surface); Draw.hline(g, r.x, r.bottom - 1, r.w, Palette.border) }
        paint(Sprites.SIDEBAR) { g, r -> Draw.fill(g, r, Palette.surface); Draw.vline(g, r.right - 1, r.y, r.h, Palette.border) }
        box(Sprites.TOOLTIP, { Palette.overlay }, { Palette.borderStrong })
        box(Sprites.POPOVER, { Palette.overlay }, { Palette.borderStrong })
        box(Sprites.MODAL, { Palette.surface }, { Palette.borderStrong })
        box(Sprites.MODAL_DANGER, { Palette.surface }, { Palette.danger })
        box(Sprites.TOAST, { Palette.overlay }, { Palette.border })
        paint(Sprites.HAZARD) { g, r -> Draw.fill(g, r, Palette.alpha(Palette.danger, 0x30)); Draw.hline(g, r.x, r.bottom - 1, r.w, Palette.danger) }

        button(Sprites.Look.SECONDARY, { Palette.raised }, { Palette.border }, { Palette.hover }, { Palette.sunken }, { Palette.surface }, { Palette.borderSubtle }, { Palette.borderStrong })
        button(Sprites.Look.PRIMARY, { Palette.brass }, { Palette.lighten(Palette.brass, 0.15f) }, { Palette.lighten(Palette.brass, 0.12f) }, { Palette.darken(Palette.brass, 0.2f) },
            { Palette.alpha(Palette.brass, 0x40) }, { Palette.alpha(Palette.brass, 0x60) })
        button(Sprites.Look.DANGER, { Palette.mix(Palette.raised, Palette.danger, 0.35f) }, { Palette.danger }, { Palette.mix(Palette.raised, Palette.danger, 0.5f) },
            { Palette.mix(Palette.sunken, Palette.danger, 0.3f) }, { Palette.surface }, { Palette.alpha(Palette.danger, 0x50) })
        button(Sprites.Look.GHOST, { 0 }, { 0 }, { Palette.hover }, { Palette.sunken }, { 0 }, { 0 })

        box(Sprites.input(focused = false, invalid = false, enabled = true, hovered = false), { Palette.sunken }, { Palette.border })
        box(Sprites.input(focused = false, invalid = false, enabled = true, hovered = true), { Palette.sunken }, { Palette.borderStrong })
        box(Sprites.input(focused = true, invalid = false, enabled = true, hovered = false), { Palette.sunken }, { Palette.focus })
        box(Sprites.input(focused = false, invalid = true, enabled = true, hovered = false), { Palette.sunken }, { Palette.danger })
        box(Sprites.input(focused = false, invalid = false, enabled = false, hovered = false), { Palette.surface }, { Palette.borderSubtle })

        box(Sprites.CHECKBOX, { Palette.sunken }, { Palette.borderStrong })
        paint(Sprites.CHECKBOX_ON) { g, r -> Draw.box(g, r, Palette.brass, Palette.brass); check(g, r, Palette.text) }
        paint(Sprites.CHECKBOX_MIXED) { g, r -> Draw.box(g, r, Palette.brass, Palette.brass); Draw.fill(g, Rect(r.x + 3, r.centerY - 1, r.w - 6, 2), Palette.text) }
        box(Sprites.RADIO, { Palette.sunken }, { Palette.borderStrong })
        paint(Sprites.RADIO_ON) { g, r -> Draw.box(g, r, Palette.sunken, Palette.brass); Draw.fill(g, r.inset(3), Palette.brass) }
        box(Sprites.TOGGLE, { Palette.sunken }, { Palette.borderStrong })
        box(Sprites.TOGGLE_ON, { Palette.brass }, { Palette.brass })
        box(Sprites.KNOB, { Palette.text }, { Palette.borderStrong })
        box(Sprites.TRACK, { Palette.sunken }, { Palette.borderSubtle })
        box(Sprites.SCROLL_THUMB, { Palette.textMuted }, { 0 })
        paint(Sprites.TAB) { _, _ -> }
        paint(Sprites.TAB_ACTIVE) { g, r -> Draw.fill(g, r, Palette.raised) }
        box(Sprites.KEYCAP, { Palette.raised }, { Palette.borderStrong })
        listOf(Sprites.FILL, Sprites.CHIP, Sprites.BADGE).forEach { id -> solids += id; paint(id) { g, r -> Draw.fill(g, r, Palette.text) } }
    }
}
