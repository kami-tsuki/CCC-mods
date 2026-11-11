package kami.libs.ui.press

import kami.libs.press.Markup
import kami.libs.press.Ref

const val SPUR = ""

class ChipSpec(
    val ref: Ref, val label: String, val value: String = "", val note: String = "", val known: Boolean = true,
    val valueMuted: Boolean = false, val look: FlagLook? = null
)

object Chips {
    private const val LABEL_MAX = 24
    private const val RAW_MAX = 16

    fun glyphWidth(ref: Ref) = when (ref) {
        is Ref.Flag -> 12
        is Ref.Head -> 8
        is Ref.Item, is Ref.Price -> 10
        is Ref.Icon -> 12
    }

    private fun humanize(id: String) = id.substringAfterLast(':').replace('_', ' ')

    private fun clip(text: String, max: Int) = if (text.length <= max) text else text.take(max - 1).trimEnd() + "…"

    fun spec(ref: Ref, data: NewsData): ChipSpec = when (ref) {
        is Ref.Price -> {
            val name = data.item(ref.item)
            val stamp = data.price(ref.item)
            ChipSpec(
                ref, clip(name ?: humanize(ref.item), LABEL_MAX), stamp?.let { "${it.value} $SPUR" } ?: "n/a",
                stamp?.date.orEmpty(), name != null, stamp == null
            )
        }
        is Ref.Flag -> {
            val look = data.flag(ref.country)
            val name = data.country(ref.country)
            ChipSpec(ref, clip(name ?: ref.country.take(RAW_MAX), LABEL_MAX), known = look != null || name != null, look = look)
        }
        is Ref.Head -> {
            val name = data.head(ref.player)
            ChipSpec(ref, clip(name ?: ref.player.take(RAW_MAX), LABEL_MAX), known = name != null)
        }
        is Ref.Item -> {
            val name = data.item(ref.id)
            ChipSpec(ref, clip(name ?: humanize(ref.id), LABEL_MAX), known = name != null)
        }
        is Ref.Icon -> ChipSpec(ref, "")
    }

    fun spec(token: String, data: NewsData): ChipSpec? = Markup.refs(Markup.parse(token)).firstOrNull()?.let { spec(it, data) }

    fun width(spec: ChipSpec, m: TextMeasure, pad: Int, notes: Boolean): Int {
        var w = pad * 2 + glyphWidth(spec.ref)
        if (spec.label.isNotEmpty()) w += 2 + m.width(spec.label, false)
        if (spec.value.isNotEmpty()) w += 3 + m.width(spec.value, true)
        if (notes && spec.note.isNotEmpty()) w += 3 + m.width(spec.note, false) * 3 / 4
        return w
    }
}
