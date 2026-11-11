package kami.libs.ui.style

import kotlin.math.max
import kotlin.math.roundToInt

enum class Density(val key: String, val factor: Float) {
    COMPACT("compact", 0.85f),
    COMFORTABLE("comfortable", 1f);

    companion object {
        fun parse(value: String): Density = entries.firstOrNull { it.key.equals(value.trim(), true) } ?: COMFORTABLE
    }
}

object Metrics {
    const val REFRESH_FAST_MS = 5_000L
    const val REFRESH_MS = 10_000L
    const val REFRESH_SLOW_MS = 30_000L

    @Volatile
    var density = Density.COMFORTABLE
        private set

    fun use(next: Density) { density = next }

    fun px(base: Int, density: Density = this.density): Int = max(base - 2, (base * density.factor).roundToInt())

    val controlH get() = Palette.shape.height(px(18))
    val smallH get() = px(16)
    val rowH get() = px(16)
    val cardHeaderH get() = px(16)
    val sectionRowH get() = px(13)
    val topbarH get() = px(24)
    val crumbsH get() = px(16)
    val groupH get() = px(14)
    val railToggleH get() = px(14)
    val modulesH get() = px(18)
    val tableRowH get() = px(18)
    val gap get() = px(6)
    val kpiH get() = px(34)
    val tileH get() = px(44)
    val cardH get() = px(124)
    val chartH get() = px(132)
    val chartCompactH get() = px(96)
}
