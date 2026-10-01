package kami.libs.ui.anim

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.pow

fun interface Curve { fun at(t: Float): Float }

object Ease {
    private const val BACK = 1.70158f

    val outQuad = Curve { val t = it.coerceIn(0f, 1f); 1f - (1f - t) * (1f - t) }
    val outCubic = Curve { val t = it.coerceIn(0f, 1f); 1f - (1f - t).pow(3) }
    val inOutCubic = Curve { val t = it.coerceIn(0f, 1f); if (t < 0.5f) 4f * t * t * t else 1f - (-2f * t + 2f).pow(3) / 2f }
    val outBack = Curve { val t = it.coerceIn(0f, 1f) - 1f; 1f + (BACK + 1f) * t * t * t + BACK * t * t }
    val outExpo = Curve { val t = it.coerceIn(0f, 1f); if (t >= 1f) 1f else 1f - 2f.pow(-10f * t) }

    fun blend(speed: Float, dt: Float) = 1f - exp(-speed * dt)

    fun approach(current: Float, target: Float, speed: Float, dt: Float): Float {
        val next = current + (target - current) * blend(speed, dt)
        return if (abs(target - next) < 0.001f) target else next
    }
}
