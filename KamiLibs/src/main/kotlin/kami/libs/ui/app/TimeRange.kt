package kami.libs.ui.app

import kami.libs.ui.core.Rect
import kami.libs.ui.core.Ui
import kami.libs.ui.style.Draw
import kami.libs.ui.text.tr
import kami.libs.ui.widget.Option
import kami.libs.ui.widget.segmented
import kami.libs.ui.widget.select
import kami.libs.util.Time

const val SELECT_W = 80
private const val DRIFT = 20

fun segmentWidth(options: List<Option<*>>) = options.maxOf { Draw.width(it.label) + 8 } * options.size

class TimeRange(private val key: String) {
    private var choice = "all"
    private var span: Long? = null
    private var start: Long? = null
    private val options get() = listOf(
        Option("all", tr("kami_libs.range.all")), Option("30d", tr("kami_libs.range.30d")),
        Option("7d", tr("kami_libs.range.7d")), Option("24h", tr("kami_libs.range.24h"))
    )

    val from: Long? get() = span?.let { s ->
        val now = System.currentTimeMillis()
        start?.takeIf { now - it <= s + s / DRIFT } ?: (now - s).also { start = it }
    }

    fun width(narrow: Boolean) = if (narrow) SELECT_W else segmentWidth(options)

    fun draw(ui: Ui, r: Rect) {
        val options = options
        val picked = if (r.w < segmentWidth(options)) ui.select(r, options, choice, key = key) else ui.segmented(r, options, choice, key = key)
        picked?.let { c ->
            choice = c
            span = when (c) {
                "30d" -> 30 * Time.DAY_MS
                "7d" -> 7 * Time.DAY_MS
                "24h" -> Time.DAY_MS
                else -> null
            }
            start = null
        }
    }
}
