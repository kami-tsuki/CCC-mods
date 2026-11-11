package kami.libs.ui.press

import kami.libs.KamiLibs
import kami.libs.mc.ItemSpec
import kami.libs.press.Dye
import kami.libs.press.Ref
import kami.libs.ui.core.Memo
import kami.libs.ui.core.Rect
import kami.libs.ui.core.Ui
import kami.libs.ui.style.Draw
import kami.libs.ui.style.Icon
import kami.libs.ui.style.Icons
import kami.libs.ui.style.TextStyle
import kami.libs.ui.widget.SCROLL_GUTTER
import kami.libs.ui.widget.ScrollState
import kami.libs.ui.widget.scroll
import kami.libs.ui.widget.Flags
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.Style
import net.minecraft.world.item.ItemStack

object McMeasure : TextMeasure {
    override fun width(text: String, bold: Boolean) = Draw.width(text, if (bold) TextStyle.TITLE else TextStyle.BODY)
}

object Newsprint {
    const val PAPER = 0xFFE7E1CF.toInt()
    const val INK = 0xFF1B1A17.toInt()
    const val MUTED = 0xFF5A564B.toInt()
    private const val RULE_SOFT = 0x801B1A17.toInt()
    private const val MASTHEAD_PAD = 4
    private const val ORNAMENT = 14
    private val iconNames = setOf(
        "star", "crown", "shield", "scroll", "globe", "flag", "house", "pickaxe", "wheat", "tree", "fire", "water", "coin", "scales",
        "handshake", "gear", "town", "tool", "chest", "treasury", "person", "people", "check", "cross", "warning", "info", "clock"
    )
    private val stacks = HashMap<String, ItemStack>()

    fun signature(time: String, country: String, authors: List<String>): String =
        listOf(time, country, authors.filter { it.isNotBlank() }.joinToString(", ")).filter { it.isNotBlank() }.joinToString(", ")

    private val itemNames = HashMap<String, String>()

    private fun itemName(id: String): String? = itemNames.getOrPut(id) {
        ItemSpec.stack(id).takeIf { !it.isEmpty }?.hoverName?.string.orEmpty()
    }.takeIf { it.isNotEmpty() }

    internal fun named(data: NewsData) = NewsData(data.flag, data.price, data.head, data.country) { data.item(it) ?: itemName(it) }

    fun layout(article: Article, width: Int, measure: TextMeasure = McMeasure): Layout =
        NewsLayout.layout(article.copy(data = named(article.data)), width, measure)

    fun height(article: Article, width: Int, measure: TextMeasure = McMeasure): Int = layout(article, width, measure).height

    internal fun cached(ui: Ui, key: Any, slot: String, article: Article, width: Int): Layout =
        ui.remember("news:$key:$slot") { Memo() }.of(article, width) { layout(article, width) }

    fun draw(ui: Ui, r: Rect, article: Article, key: Any = "news") = paint(ui.g, ui, r, cached(ui, key, "d", article, r.w))

    internal fun paint(g: GuiGraphics, ui: Ui, r: Rect, layout: Layout) {
        Draw.fill(g, r, PAPER)
        Draw.outline(g, r, RULE_SOFT)
        val limit = ui.screen.bottom
        for (row in layout.rows) {
            val x = r.x + row.x
            val y = r.y + row.y
            if (y + row.h < 0 || y > limit) continue
            when (row.kind) {
                RowKind.TEXT, RowKind.CAP -> row.pieces.forEach { piece(g, it, x + it.x, y, row.h) }
                RowKind.RULE -> Draw.fill(g, Rect(x, y, row.w, 1), INK)
                RowKind.DOUBLE -> { Draw.fill(g, Rect(x, y, row.w, 2), INK); Draw.fill(g, Rect(x, y + 3, row.w, 1), INK) }
                RowKind.VRULE -> Draw.fill(g, Rect(x, y, 1, row.h), RULE_SOFT)
                RowKind.BAR -> Draw.fill(g, Rect(x, y, row.w, row.h), INK)
            }
        }
    }

    private fun paintedDye(dye: Dye?) = dye?.takeIf { !it.free }

    private fun inkOf(p: Piece) = paintedDye(p.color)?.let { PressColors.argb(it) } ?: if (p.muted) MUTED else INK

    private fun text(g: GuiGraphics, text: String, x: Int, y: Int, bold: Boolean, italic: Boolean, color: Int, scale: Float) {
        val c = Component.literal(text).withStyle(Style.EMPTY.withBold(bold).withItalic(italic))
        g.pose().pushPose()
        g.pose().translate(x.toFloat(), y.toFloat(), 0f)
        if (scale != 1f) g.pose().scale(scale, scale, 1f)
        g.drawString(Draw.font, c, 0, 0, color, false)
        g.pose().popPose()
    }

    private fun piece(g: GuiGraphics, p: Piece, x: Int, y: Int, h: Int) {
        val chip = p.chip ?: return text(g, p.text, x, y + (h - 8 * p.scale) / 2, p.bold, p.italic, inkOf(p), p.scale.toFloat())
        val dye = paintedDye(p.color)
        val ink = if (chip.known) inkOf(p) else MUTED
        glyph(g, chip, x, y, dye, ink)
        var cx = x + Chips.glyphWidth(chip.ref)
        val tight = Chips.width(chip, McMeasure, 0, true) + 2 > p.w
        val valueW = if (chip.value.isEmpty()) 0 else 3 + McMeasure.width(chip.value, true)
        if (chip.label.isNotEmpty()) {
            val label = if (tight) Draw.fit(chip.label, (p.w - (cx - x) - 2 - valueW).coerceAtLeast(0)) else chip.label
            if (label.isNotEmpty()) {
                text(g, label, cx + 2, y + 1, false, false, ink, 1f)
                cx += 2 + McMeasure.width(label, false)
            }
        }
        if (chip.value.isNotEmpty()) {
            text(g, chip.value, cx + 3, y + 1, true, false, if (chip.valueMuted) MUTED else ink, 1f)
            cx += valueW
        }
        if (!tight && chip.note.isNotEmpty()) text(g, chip.note, cx + 3, y + 3, false, false, MUTED, 0.75f)
    }

    private fun stackOf(id: String) = stacks.getOrPut(id) { ItemSpec.stack(id) }

    internal fun glyph(g: GuiGraphics, chip: ChipSpec, x: Int, y: Int, dye: Dye?, ink: Int) {
        when (val ref = chip.ref) {
            is Ref.Flag -> {
                val r = Rect(x, y + 1, 12, 8)
                val look = chip.look
                if (look != null) Flags.draw(g, r, Gray.tone(look.color, dye), look.pattern, look.emblem, Gray.tone(look.secondary, dye))
                else Draw.box(g, r, Gray.tone(0xFF9A9688.toInt(), dye), ink)
            }
            is Ref.Head -> Gray.head(g, ref.player, Rect(x, y + 1, 8, 8), dye)
            is Ref.Item -> item(g, ref.id, Rect(x, y, 10, 10), dye)
            is Ref.Price -> item(g, ref.item, Rect(x, y, 10, 10), dye)
            is Ref.Icon -> Draw.tintedIcon(g, if (ref.name in iconNames) Icon.of(KamiLibs.ID, ref.name) else Icons.GENERIC, x, y - 2, Draw.ICON, ink)
        }
    }

    private fun item(g: GuiGraphics, id: String, r: Rect, dye: Dye?) {
        val stack = stackOf(id)
        if (stack.isEmpty) text(g, "?", r.x + 2, r.y + 1, true, false, MUTED, 1f) else Gray.item(g, stack, r, dye)
    }

    private fun titleScale(title: String, width: Int) = (3 downTo 1).firstOrNull { McMeasure.width(title.uppercase(), true) * it <= width - 2 * (ORNAMENT + 8) } ?: 1

    fun mastheadHeight(width: Int, title: String, tagline: String = ""): Int =
        MASTHEAD_PAD + 5 + 4 + 8 * titleScale(title, width) + 4 + (if (tagline.isBlank()) 0 else 1 + 3 + 8 + 3) + 5 + MASTHEAD_PAD

    fun masthead(g: GuiGraphics, r: Rect, title: String, tagline: String = "") {
        Draw.fill(g, r, PAPER)
        val scale = titleScale(title, r.w)
        var y = r.y + MASTHEAD_PAD
        Draw.fill(g, Rect(r.x + 6, y, r.w - 12, 2), INK)
        Draw.fill(g, Rect(r.x + 6, y + 3, r.w - 12, 1), INK)
        y += 5 + 4
        val shown = title.uppercase()
        val w = McMeasure.width(shown, true) * scale
        text(g, shown, r.x + (r.w - w) / 2, y, true, false, INK, scale.toFloat())
        val cy = y + 4 * scale
        Draw.tintedIcon(g, Icons.STAR, r.x + 8, cy - ORNAMENT / 2, ORNAMENT, INK)
        Draw.tintedIcon(g, Icons.STAR, r.right - 8 - ORNAMENT, cy - ORNAMENT / 2, ORNAMENT, INK)
        y += 8 * scale + 4
        if (tagline.isNotBlank()) {
            Draw.fill(g, Rect(r.x + 6, y, r.w - 12, 1), INK)
            val line = tagline.uppercase()
            text(g, line, r.x + (r.w - McMeasure.width(line, false)) / 2, y + 4, false, false, MUTED, 1f)
            y += 1 + 3 + 8 + 3
        }
        Draw.fill(g, Rect(r.x + 6, y, r.w - 12, 1), INK)
        Draw.fill(g, Rect(r.x + 6, y + 2, r.w - 12, 2), INK)
    }
}

fun Ui.newsprintView(r: Rect, article: Article, key: Any = "news"): ScrollState {
    val full = Newsprint.cached(this, key, "a", article, r.w)
    val laid = if (full.height > r.h) Newsprint.cached(this, key, "b", article, r.w - SCROLL_GUTTER) else full
    return scroll(key, r, laid.height) { c -> Newsprint.paint(g, this, Rect(c.x, c.y, laid.width, laid.height), laid) }
}
