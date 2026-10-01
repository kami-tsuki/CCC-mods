package kami.libs.ui.anim

import kotlin.math.abs

object EdgePath {
    fun length(x0: Int, y0: Int, mid: Int, x1: Int, y1: Int) = abs(mid - x0) + abs(y1 - y0) + abs(x1 - mid)

    fun pointAt(distance: Int, x0: Int, y0: Int, mid: Int, x1: Int, y1: Int, out: IntArray) {
        val first = abs(mid - x0)
        val second = abs(y1 - y0)
        val d = distance.coerceIn(0, length(x0, y0, mid, x1, y1))
        when {
            d <= first -> { out[0] = x0 + if (mid >= x0) d else -d; out[1] = y0 }
            d <= first + second -> { out[0] = mid; out[1] = y0 + (if (y1 >= y0) d - first else first - d) }
            else -> { out[0] = mid + if (x1 >= mid) d - first - second else first + second - d; out[1] = y1 }
        }
    }
}
