package kami.libs.press

import kotlinx.serialization.Serializable

@Serializable
data class CostTable(
    val basePaper: Int = 2, val baseInk: Int = 3, val includedChars: Int = 400,
    val subtitlePaper: Int = 1, val subtitleInk: Int = 2,
    val extraChars: Int = 500, val extraPaper: Int = 1, val extraInk: Int = 1,
    val inkPerElement: Int = 1, val inkPerStyle: Int = 1,
    val dyeChars: Int = 150, val dyePerRef: Int = 1
)

data class Cost(val paper: Int, val ink: Int, val dyes: Map<Dye, Int>)

object PressCost {
    @Suppress("UNUSED_PARAMETER")
    fun of(title: String, subtitle: String, doc: Doc, table: CostTable = CostTable()): Cost {
        val spans = doc.blocks.flatMap { b -> b.lines().flatten() }
        val extra = ceil(Markup.length(doc) - table.includedChars, table.extraChars)
        val sub = subtitle.isNotBlank()
        val elements = doc.blocks.count { it !is Block.Para } + spans.count { it.ref != null }
        val styles = spans.count { it.bold } + spans.count { it.italic }
        val paper = table.basePaper + extra * table.extraPaper + if (sub) table.subtitlePaper else 0
        val ink = table.baseInk + extra * table.extraInk + elements * table.inkPerElement + styles * table.inkPerStyle + if (sub) table.subtitleInk else 0
        val painted = spans.filter { it.color?.free == false }.groupBy { it.color!! }
        val dyes = painted.mapValues { (_, list) ->
            ceil(list.filter { it.ref == null }.sumOf { it.text.length }, table.dyeChars) + list.count { it.ref != null } * table.dyePerRef
        }.filterValues { it > 0 }
        return Cost(paper, ink, dyes)
    }

    private fun ceil(amount: Int, per: Int) = if (amount <= 0 || per <= 0) 0 else (amount + per - 1) / per
}
