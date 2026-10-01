package kami.libs.ui.anim

import java.util.SplittableRandom
import kotlin.math.cos
import kotlin.math.sin

class Sparks(val capacity: Int = 160, seed: Long = 7L, private val gravity: Float = 90f) {
    private val random = SplittableRandom(seed)
    val x = FloatArray(capacity)
    val y = FloatArray(capacity)
    private val vx = FloatArray(capacity)
    private val vy = FloatArray(capacity)
    val life = FloatArray(capacity)
    private val span = FloatArray(capacity)
    val color = IntArray(capacity)
    private var next = 0

    internal val alive get() = life.count { it > 0f }

    fun fade(i: Int) = (life[i] / span[i]).coerceIn(0f, 1f)

    fun emit(px: Float, py: Float, rgb: Int, count: Int) {
        repeat(count) {
            val angle = random.nextDouble() * Math.PI * 2
            val speed = 30f + random.nextDouble().toFloat() * 70f
            x[next] = px
            y[next] = py
            vx[next] = cos(angle).toFloat() * speed
            vy[next] = sin(angle).toFloat() * speed - 20f
            span[next] = 0.5f + random.nextDouble().toFloat() * 0.5f
            life[next] = span[next]
            color[next] = rgb
            next = (next + 1) % capacity
        }
    }

    fun update(dt: Float) {
        for (i in 0 until capacity) {
            if (life[i] <= 0f) continue
            life[i] -= dt
            vy[i] += gravity * dt
            x[i] += vx[i] * dt
            y[i] += vy[i] * dt
        }
    }
}

class Floaters(val capacity: Int = 8) {
    val text = arrayOfNulls<String>(capacity)
    val x = IntArray(capacity)
    val y = FloatArray(capacity)
    val age = FloatArray(capacity)
    val color = IntArray(capacity)
    private var next = 0

    fun emit(px: Int, py: Int, label: String, rgb: Int) {
        text[next] = label
        x[next] = px
        y[next] = py.toFloat()
        age[next] = 0f
        color[next] = rgb
        next = (next + 1) % capacity
    }

    fun update(dt: Float) {
        for (i in 0 until capacity) {
            if (text[i] == null) continue
            age[i] += dt
            y[i] -= RISE * dt
            if (age[i] > LIFE) text[i] = null
        }
    }

    fun fade(i: Int) = (1f - age[i] / LIFE).coerceIn(0f, 1f)

    companion object {
        const val LIFE = 1.1f
        const val RISE = 18f
    }
}

class Glows(val capacity: Int = 8) {
    val x = IntArray(capacity)
    val y = IntArray(capacity)
    val color = IntArray(capacity)
    val age = FloatArray(capacity) { LIFE }
    private var next = 0

    fun emit(px: Int, py: Int, rgb: Int) {
        x[next] = px; y[next] = py; color[next] = rgb; age[next] = 0f
        next = (next + 1) % capacity
    }

    fun update(dt: Float) { for (i in 0 until capacity) if (age[i] < LIFE) age[i] += dt }

    fun fade(i: Int) = (1f - age[i] / LIFE).coerceIn(0f, 1f)

    companion object { const val LIFE = 0.3f }
}
