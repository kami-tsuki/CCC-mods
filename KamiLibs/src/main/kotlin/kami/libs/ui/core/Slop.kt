package kami.libs.ui.core

import kotlin.math.abs

object Slop {
    const val PX = 4

    fun exceeded(dx: Int, dy: Int) = abs(dx) > PX || abs(dy) > PX
}
