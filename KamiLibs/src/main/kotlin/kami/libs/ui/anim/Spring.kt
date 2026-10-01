package kami.libs.ui.anim

import kotlin.math.abs
import kotlin.math.min
import kotlin.math.sqrt

internal const val DEFAULT_STIFFNESS = 180f

class Spring(var value: Float, var velocity: Float = 0f) {
    fun step(target: Float, dt: Float, stiffness: Float = DEFAULT_STIFFNESS, damping: Float = 2f * sqrt(stiffness)) {
        var left = dt
        while (left > 1e-6f) {
            val h = min(left, SUBSTEP)
            velocity += (stiffness * (target - value) - damping * velocity) * h
            value += velocity * h
            left -= h
        }
        if (settled(target)) { value = target; velocity = 0f }
    }

    fun settled(target: Float) = abs(target - value) < EPSILON && abs(velocity) < EPSILON

    private companion object {
        const val SUBSTEP = 1f / 120f
        const val EPSILON = 0.01f
    }
}
