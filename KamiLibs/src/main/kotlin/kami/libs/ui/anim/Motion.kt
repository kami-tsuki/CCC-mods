package kami.libs.ui.anim

import kami.libs.ui.core.Ui
import kami.libs.ui.style.Palette
import kotlin.math.PI
import kotlin.math.sin
import kotlin.math.sqrt

private const val FAR_PAST = Long.MAX_VALUE / 4
private const val FLASH_SECONDS = 0.9
private const val FLASH_ALPHA = 0x60
private const val HOVER_SPEED = 18f
private const val PRESS_SPEED = 32f
private const val DEFAULT_SPEED = 14f
private const val TWEEN_MS = 220
private const val REVEAL_MS = 180

fun Ui.anim(key: Any, target: Float, speed: Float = DEFAULT_SPEED, start: Float = target): Float {
    val s = motion.slot(id(key), frame, start)
    s.value = if (reduceMotion) target else Ease.approach(s.value, target, speed, dt)
    return s.value
}

fun Ui.spring(key: Any, target: Float, stiffness: Float = DEFAULT_STIFFNESS, damping: Float = 2f * sqrt(stiffness)): Float {
    val s = motion.slot(id(key), frame, target)
    val spring = s.spring ?: Spring(target).also { s.spring = it }
    if (reduceMotion) { spring.value = target; spring.velocity = 0f } else spring.step(target, dt, stiffness, damping)
    s.value = spring.value
    return spring.value
}

fun Ui.tween(key: Any, target: Float, ms: Int = TWEEN_MS, curve: Curve = Ease.outCubic, from: Float = target): Float {
    val s = motion.slot(id(key), frame, from)
    if (reduceMotion) { s.value = target; s.target = target; return target }
    if (s.target != target) { s.from = s.value; s.target = target; s.start = time }
    val k = ((time - s.start) * 1000.0 / ms).toFloat()
    s.value = if (k >= 1f) target else s.from + (target - s.from) * curve.at(k)
    return s.value
}

fun Ui.transition(key: Any, visible: Boolean, inMs: Int = 160, outMs: Int = 120, curve: Curve = Ease.outCubic): Float =
    tween(key, if (visible) 1f else 0f, if (visible) inMs else outMs, curve, from = 0f)

fun Ui.countUp(key: Any, value: Long, ms: Int = 600): Long {
    val s = motion.slot(id(key), frame, 0f)
    if (s.fresh || reduceMotion) {
        s.fresh = false; s.mark = value; s.prev = value; s.start = -1e9
        return value
    }
    if (value != s.mark) { s.prev = shownCount(s, ms); s.mark = value; s.start = time }
    return shownCount(s, ms)
}

private fun Ui.shownCount(s: Slot, ms: Int): Long {
    val k = ((time - s.start) * 1000.0 / ms).toFloat()
    return if (k >= 1f) s.mark else s.prev + ((s.mark - s.prev).toDouble() * Ease.outCubic.at(k)).toLong()
}

private fun change(s: Slot, value: Long): Long {
    if (s.fresh) { s.fresh = false; s.mark = value; return 0L }
    val delta = value - s.mark
    s.mark = value
    return delta
}

fun Ui.gained(key: Any, value: Long): Long = change(motion.slot(id(key), frame, 0f), value).coerceAtLeast(0L)

fun Ui.flash(key: Any, value: Long): Int {
    val s = motion.slot(id(key), frame, 0f)
    val delta = change(s, value)
    if (delta != 0L) { s.start = time; s.sign = if (delta > 0) 1 else -1 }
    val age = time - s.start
    if (s.sign == 0 || age > FLASH_SECONDS) return 0
    val alpha = if (reduceMotion) FLASH_ALPHA / 2 else ((1 - age / FLASH_SECONDS) * FLASH_ALPHA).toInt()
    return Palette.alpha(if (s.sign > 0) Palette.success else Palette.danger, alpha)
}

fun Ui.since(key: Any, trigger: Long): Long {
    val s = motion.slot(id(key), frame, 0f)
    if (s.fresh || s.mark != trigger) { s.fresh = false; s.mark = trigger; s.start = time }
    return if (reduceMotion) FAR_PAST else ((time - s.start) * 1000.0).toLong()
}

fun Ui.reveal(key: Any, trigger: Long = 0L, delayMs: Long = 0L, ms: Int = REVEAL_MS): Float =
    Ease.outCubic.at((since(key, trigger) - delayMs).toFloat() / ms)

fun Ui.feel(key: Any, hovered: Boolean, down: Boolean, focused: Boolean = false): Feel {
    val s = motion.slot(id(key), frame, 0f)
    val f = s.feel ?: Feel().also { s.feel = it }
    f.hover = step(f.hover, hovered, HOVER_SPEED)
    f.press = step(f.press, down, PRESS_SPEED)
    f.focus = step(f.focus, focused, HOVER_SPEED)
    return f
}

private fun Ui.step(current: Float, on: Boolean, speed: Float): Float {
    val target = if (on) 1f else 0f
    return if (reduceMotion) target else Ease.approach(current, target, speed, dt)
}

fun Ui.pulse(periodMs: Long = 1200, phaseMs: Long = 0): Float {
    if (reduceMotion) return 0.5f
    val t = (((time * 1000).toLong() + phaseMs) % periodMs) / periodMs.toFloat()
    return (sin(t * PI * 2).toFloat() + 1f) / 2f
}
