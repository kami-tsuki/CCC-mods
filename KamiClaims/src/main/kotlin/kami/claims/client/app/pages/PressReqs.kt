package kami.claims.client.app.pages

import kami.libs.mc.ItemSpec
import kami.libs.ui.core.Rect
import kami.libs.ui.core.Ui
import kami.libs.ui.style.Draw
import kami.libs.ui.style.Format
import kami.libs.ui.style.Icons
import kami.libs.ui.style.Palette
import kami.libs.ui.text.tr
import net.minecraft.world.item.ItemStack

object PressReqs {
    enum class Kind { ITEM, FEE, CAP }

    data class Cell(val kind: Kind, val item: String, val have: Long, val need: Long) {
        val ok get() = if (kind == Kind.CAP) have < need else have >= need
    }

    class Slot(val x: Int, val row: Int)

    const val CELL_H = 16
    const val ROW_STEP = 18
    private const val GAP = 6
    private const val ICON = 16
    private val stacks = HashMap<String, ItemStack>()

    fun cells(need: Map<String, Int>, have: Map<String, Int>, fee: Long, treasury: Long, published: Int, cap: Int): List<Cell> =
        need.filterValues { it > 0 }.map { (id, n) -> Cell(Kind.ITEM, id, (have[id] ?: 0).toLong(), n.toLong()) } +
            listOfNotNull(Cell(Kind.FEE, "", treasury, fee).takeIf { fee > 0 }) +
            Cell(Kind.CAP, "", published.toLong(), cap.toLong())

    fun wrap(widths: List<Int>, w: Int): List<Slot> {
        var x = 0
        var row = 0
        return widths.map { cw ->
            if (x > 0 && x + cw > w) { x = 0; row++ }
            Slot(x, row).also { x += cw + GAP }
        }
    }

    fun height(slots: List<Slot>) = if (slots.isEmpty()) 0 else (slots.last().row + 1) * ROW_STEP - (ROW_STEP - CELL_H)

    private fun text(c: Cell) = if (c.kind == Kind.FEE) "${Format.number(c.have)}/${Format.number(c.need)}" else "${c.have}/${c.need}"

    private fun width(c: Cell) = ICON + 2 + Draw.width(text(c))

    private fun tip(c: Cell) = when (c.kind) {
        Kind.ITEM -> tr("kami_claims.press.req.item", PressModel.itemName(c.item), c.have, c.need)
        Kind.FEE -> tr("kami_claims.press.req.fee", Format.money(c.have), Format.money(c.need))
        Kind.CAP -> tr("kami_claims.press.req.cap", c.have, c.need)
    }

    fun measure(cells: List<Cell>, w: Int) = height(wrap(cells.map(::width), w))

    fun Ui.strip(x: Int, y: Int, w: Int, cells: List<Cell>, key: String): Int {
        val slots = wrap(cells.map(::width), w)
        cells.forEachIndexed { i, c ->
            val cx = x + slots[i].x
            val cy = y + slots[i].row * ROW_STEP
            when (c.kind) {
                Kind.ITEM -> stacks.getOrPut(c.item) { ItemSpec.stack(c.item) }.takeIf { !it.isEmpty }?.let { g.renderItem(it, cx, cy) } ?: Draw.text(g, "?", cx + 5, cy + 4, Palette.textMuted)
                Kind.FEE -> Draw.icon(g, Icons.COIN, cx, cy, ICON)
                Kind.CAP -> Draw.icon(g, Icons.SCROLL, cx, cy, ICON)
            }
            Draw.text(g, text(c), cx + ICON + 2, cy + 4, if (c.ok) Palette.success else Palette.danger)
            tooltip("$key:$i", Rect(cx, cy, width(c), CELL_H), tip(c))
        }
        return height(slots)
    }
}
