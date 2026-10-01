package kami.libs.ui.anim

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.pow

fun interface Curve { fun at(t: Float): Float }

object Ease {
    val outCubic = Curve { val t = it.coerceIn(0f, 1f); 1f - (1f - t).pow(3) }

    fun blend(speed: Float, dt: Float) = 1f - exp(-speed * dt)

    fun approach(current: Float, target: Float, speed: Float, dt: Float): Float {
        val next = current + (target - current) * blend(speed, dt)
        return if (abs(target - next) < 0.001f) target else next
    }
}
