package kami.libs.ui.widget

import kami.libs.ui.anim.Countdown
import kami.libs.ui.core.Tip
import kami.libs.ui.graph.Cell
import kami.libs.ui.style.Icon
import kami.libs.ui.style.Icons
import net.minecraft.world.item.ItemStack

const val TECH_NODE_W = 108
const val TECH_NODE_H = 28
internal const val GAP_X = 30
internal const val GAP_Y = 10
internal const val TEXT_PAD = 6
internal const val ITEM_TEXT_X = 27

class TechNode(
    val label: String,
    val accent: Int,
    val parents: List<Int>,
    val dashed: Set<Int> = emptySet(),
    val done: Boolean = false,
    val dim: Boolean = false,
    val status: Icon? = null,
    val progress: Double? = null,
    val highlight: Boolean = false,
    val pinned: Cell? = null,
    val tip: (() -> Tip)? = null,
    val ready: Boolean = false,
    val flowing: Boolean = false,
    val countdown: Countdown? = null,
    val band: Int = 0,
    val item: ItemStack = ItemStack.EMPTY,
    val hidden: Set<Int> = emptySet(),
    val group: Int = 0,
    val badge: Icon? = null
) {
    val locked get() = status == Icons.LOCK
    val textX get() = if (item.isEmpty) TEXT_PAD else ITEM_TEXT_X
}

class TechBands(val labels: Map<Int, String>, val current: Int)

internal fun worldX(cell: Cell) = cell.col * (TECH_NODE_W + GAP_X)
internal fun worldY(cell: Cell) = cell.row * (TECH_NODE_H + GAP_Y)
internal fun bandEdge(col: Int) = col * (TECH_NODE_W + GAP_X) - GAP_X / 2.0
