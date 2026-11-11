package kami.libs.ui.style

import kami.libs.ui.core.Rect
import net.minecraft.client.gui.GuiGraphics

object Painters {
    private const val GLOSS = 0x30
    private const val SCAN_STEP = 3
    private const val SCAN_ALPHA = 0x14

    private fun corners(r: Rect, s: ShapeDef) = Geometry.fit(Geometry.corner(s.radius, s.chamfer), r.w, r.h)

    private fun visible(color: Int) = color ushr 24 != 0

    private const val SPAN_CACHE = 256
    private val spanCache = object : LinkedHashMap<Long, List<Geometry.Span>>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, List<Geometry.Span>>) = size > SPAN_CACHE
    }

    private fun spans(r: Rect, s: ShapeDef, edge: Int): List<Geometry.Span> {
        val key = (r.w.toLong() shl 40) or (r.h.toLong() shl 20) or (edge.toLong() shl 8) or (s.radius.toLong() shl 4) or s.chamfer.toLong()
        return spanCache.getOrPut(key) { Geometry.spans(r.w, r.h, corners(r, s), edge) }
    }

    private fun band(g: GuiGraphics, x: Int, y: Int, w: Int, h: Int, top: Int, bottom: Int) {
        if (top == bottom) g.fill(x, y, x + w, y + h, top) else g.fillGradient(x, y, x + w, y + h, top, bottom)
    }

    fun body(g: GuiGraphics, r: Rect, fill: Int, border: Int, fillBottom: Int = fill) {
        if (r.isEmpty) return
        val s = Palette.shape
        val edge = if (visible(border)) s.border else 0
        spans(r, s, edge).forEach { span ->
            if (span.border) band(g, r.x + span.x, r.y + span.y, span.w, span.h, border, border)
            else if (visible(fill)) band(g, r.x + span.x, r.y + span.y, span.w, span.h, Palette.mix(fill, fillBottom, span.y / r.h.toFloat()), Palette.mix(fill, fillBottom, (span.y + span.h) / r.h.toFloat()))
        }
    }

    fun solid(g: GuiGraphics, r: Rect, color: Int) {
        if (r.isEmpty) return
        val s = Palette.shape
        if (s.bevel == Bevel.GLASS) {
            body(g, r, Palette.lighten(color, 0.18f), 0, Palette.darken(color, 0.12f))
            gloss(g, r, s)
        } else body(g, r, color, 0)
    }

    private fun gloss(g: GuiGraphics, r: Rect, s: ShapeDef) {
        val n = corners(r, s).size
        val glass = Rect(r.x + s.border + n, r.y + s.border, r.w - 2 * (s.border + n), (r.h - 2 * s.border) / 2)
        if (!glass.isEmpty) band(g, glass.x, glass.y, glass.w, glass.h, Palette.alpha(Palette.washLight, GLOSS), Palette.alpha(Palette.washLight, GLOSS / 4))
    }

    private fun lines(g: GuiGraphics, r: Rect, s: ShapeDef, hasEdge: Boolean, topLeft: Int, bottomRight: Int) {
        val b = if (hasEdge) r.inset(s.border) else r
        val n = maxOf(0, corners(r, s).size - (if (hasEdge) s.border else 0))
        if (b.w <= 2 * n || b.h <= 0) return
        Draw.hline(g, b.x + n, b.y, b.w - 2 * n, topLeft)
        if (b.h > 2 * n) Draw.vline(g, b.x, b.y + n, b.h - 2 * n, topLeft)
        Draw.hline(g, b.x + n, b.bottom - 1, b.w - 2 * n, bottomRight)
        if (b.h > 2 * n) Draw.vline(g, b.right - 1, b.y + n, b.h - 2 * n, bottomRight)
    }

    fun surface(g: GuiGraphics, r: Rect, fill: Int, topLeft: Int, bottomRight: Int, outline: Int, elevated: Boolean = false, scan: Boolean = false) {
        if (r.isEmpty) return
        val s = Palette.shape
        if (elevated && s.shadow > 0) shadow(g, r, s)
        val edge = visible(outline)
        when (s.bevel) {
            Bevel.NONE -> body(g, r, fill, outline)
            Bevel.RAISED -> { body(g, r, fill, outline); lines(g, r, s, edge, topLeft, bottomRight) }
            Bevel.GLASS -> {
                body(g, r, Palette.mix(fill, topLeft, 0.35f), outline, Palette.mix(fill, bottomRight, 0.35f))
                gloss(g, r, s)
            }
            Bevel.NEON -> {
                body(g, r, fill, outline)
                if (elevated && s.glow > 0 && edge) Draw.glow(g, r, outline, s.glow / 3f)
            }
        }
        if (scan && s.scanlines) scanlines(g, r)
    }

    private fun shadow(g: GuiGraphics, r: Rect, s: ShapeDef) {
        for (i in 1..s.shadow) g.fill(r.x + i, r.y + i, r.right + i, r.bottom + i, Palette.alpha(Palette.shadow, (Palette.shadow ushr 24) / (i + 1)))
    }

    private fun scanlines(g: GuiGraphics, r: Rect) {
        var y = r.y + 1
        while (y < r.bottom - 1) {
            Draw.hline(g, r.x + 1, y, r.w - 2, Palette.alpha(Palette.washLight, SCAN_ALPHA))
            y += SCAN_STEP
        }
    }

    fun focusRing(g: GuiGraphics, r: Rect, color: Int) {
        val s = Palette.shape
        for (i in 0 until s.focus - 1) Draw.outline(g, r.inset(s.border + i), color)
    }

    fun tab(g: GuiGraphics, r: Rect, accent: Int, fill: Int) {
        if (Palette.shape.underlineTabs) {
            Draw.fill(g, r, Palette.alpha(fill, 0x60))
            Draw.fill(g, r.bottom(2), accent)
        } else Draw.fill(g, r, fill)
    }
}
