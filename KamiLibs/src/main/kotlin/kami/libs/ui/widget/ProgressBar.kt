package kami.libs.ui.widget

import kami.libs.ui.anim.anim
import kami.libs.ui.anim.shimmer
import kami.libs.ui.core.Rect
import kami.libs.ui.core.Ui
import kami.libs.ui.style.Draw
import kami.libs.ui.style.Format
import kami.libs.ui.style.Palette
import kami.libs.ui.style.Sprites
import kami.libs.ui.text.tr
import kotlin.math.max

const val PROGRESS_BAR_H = 8
const val PROGRESS_LABELLED_H = 20
private const val PAUSED_HATCH_ALPHA = 0x30
private const val SHIMMER_ALPHA = 0x60

fun Ui.progressBar(
    r: Rect, value: Long, max: Long, label: String? = null, remaining: String? = null, color: Int = Palette.brass,
    doneColor: Int = Palette.success, shimmer: Boolean = false, paused: Boolean = false, key: Any = label ?: "bar"
) {
    val fraction = if (max <= 0) 0f else (value.toFloat() / max).coerceIn(0f, 1f)
    val shown = anim("bar:$key", fraction, speed = 10f)
    val fill = if (fraction >= 1f) doneColor else color
    val count = "${Format.number(value)} / ${Format.number(max)}"
    val labelled = r.h >= PROGRESS_LABELLED_H
    val bar = if (labelled) r.bottom(PROGRESS_BAR_H) else r
    if (labelled) {
        val right = remaining ?: if (paused) tr("kami_libs.progress.paused") else count
        val rightColor = if (paused) Palette.warning else if (remaining != null) Palette.text else Palette.textMuted
        Draw.text(g, Draw.fit(label ?: "", r.w - Draw.width(right) - 8), r.x, r.y + 1, Palette.textSecondary)
        Draw.textRight(g, right, r.right, r.y + 1, rightColor)
    }
    Draw.sprite(g, Sprites.TRACK, bar)
    val filled = bar.withWidth(max(2, (bar.w * shown).toInt()))
    if (shown > 0f) Draw.tinted(g, Sprites.FILL, filled, fill)
    if (shimmer && !paused && shown > 0f && fraction < 1f) shimmer(filled, Palette.alpha(0xFFFFFF, SHIMMER_ALPHA))
    if (paused) Draw.hatch(g, bar.inset(1), Palette.alpha(Palette.warning, PAUSED_HATCH_ALPHA))
    if (!labelled && bar.h >= 10) Draw.textCentered(g, count, bar, Palette.text)
}
