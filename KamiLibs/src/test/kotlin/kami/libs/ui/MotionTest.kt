package kami.libs.ui

import kami.libs.ui.anim.Countdown
import kami.libs.ui.anim.Curve
import kami.libs.ui.anim.EdgePath
import kami.libs.ui.anim.Ease
import kami.libs.ui.anim.MotionStore
import kami.libs.ui.anim.Sparks
import kami.libs.ui.anim.Spring
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MotionTest {
    private val curves: List<Curve> = listOf(Ease.outCubic)

    @Test
    fun curvesHitBothEndpoints() {
        curves.forEach {
            assertEquals(0f, it.at(0f), 1e-6f)
            assertEquals(1f, it.at(1f), 1e-6f)
            assertEquals(0f, it.at(-3f), 1e-6f)
            assertEquals(1f, it.at(4f), 1e-6f)
        }
    }

    @Test
    fun curvesNeverDecrease() {
        curves.forEach { curve ->
            var last = 0f
            for (i in 0..100) {
                val v = curve.at(i / 100f)
                assertTrue(v >= last - 1e-6f)
                last = v
            }
        }
    }

    @Test
    fun springSettlesAtSixtyAndTenFramesPerSecond() {
        listOf(1f / 60f, 0.1f).forEach { dt ->
            val spring = Spring(0f)
            var peak = 0f
            var steps = 0
            while (!spring.settled(100f) && steps < 400) { spring.step(100f, dt); peak = maxOf(peak, spring.value); steps++ }
            assertTrue(spring.settled(100f) || spring.value == 100f)
            assertTrue(peak <= 100.01f)
            assertTrue(steps * dt < 2f)
        }
    }

    @Test
    fun underdampedSpringOvershoots() {
        val spring = Spring(0f)
        var peak = 0f
        repeat(120) { spring.step(100f, 1f / 60f, 180f, 6f); peak = maxOf(peak, spring.value) }
        assertTrue(peak > 101f)
    }

    @Test
    fun countdownRunsFromReceiptAndStopsWhenPaused() {
        val running = Countdown(10_000, 6_000, at = 1_000, running = true)
        assertEquals(6_000, running.remaining(1_000))
        assertEquals(4_000, running.remaining(3_000))
        assertEquals(0, running.remaining(99_000))
        assertEquals(0.4f, running.fraction(1_000), 1e-6f)
        assertEquals(1f, running.fraction(99_000), 1e-6f)
        val paused = Countdown(10_000, 6_000, at = 1_000, running = false)
        assertEquals(6_000, paused.remaining(50_000))
        assertEquals(1f, Countdown(0, 0, 0, true).fraction(5), 1e-6f)
    }

    @Test
    fun sweepDropsOnlyStaleSlotsOnSweepFrames() {
        val store = MotionStore(sweepEvery = 120, maxAge = 300)
        store.slot("old", 10, 0f)
        store.slot("fresh", 10, 0f)
        store.slot("fresh", 500, 0f)
        assertEquals(0, store.sweep(481))
        assertEquals(1, store.sweep(600))
    }

    @Test
    fun forgetRemovesByPrefix() {
        val store = MotionStore()
        store.slot("page/a", 1, 0f).value = 5f
        store.slot("other", 1, 0f).value = 5f
        store.forget("page/")
        assertEquals(7f, store.slot("page/a", 2, 7f).value)
        assertEquals(5f, store.slot("other", 2, 7f).value)
    }

    @Test
    fun sparksRingOverwritesAndExpires() {
        val sparks = Sparks(capacity = 16)
        sparks.emit(0f, 0f, 0xFFFFFF, 40)
        assertEquals(16, sparks.alive)
        sparks.update(2f)
        assertEquals(0, sparks.alive)
        sparks.emit(1f, 1f, 0xFFFFFF, 3)
        assertEquals(3, sparks.alive)
        assertEquals(1f, sparks.fade(8), 1e-6f)
    }

    @Test
    fun edgePointsFollowTheThreeSegments() {
        val p = IntArray(2)
        fun at(d: Int) = EdgePath.pointAt(d, 0, 0, 10, 20, 10, p).let { p[0] to p[1] }
        assertEquals(30, EdgePath.length(0, 0, 10, 20, 10))
        assertEquals(0 to 0, at(0))
        assertEquals(5 to 0, at(5))
        assertEquals(10 to 0, at(10))
        assertEquals(10 to 7, at(17))
        assertEquals(10 to 10, at(20))
        assertEquals(15 to 10, at(25))
        assertEquals(20 to 10, at(30))
        assertEquals(20 to 10, at(99))
    }

    @Test
    fun edgePointsHandleBackwardAndUpwardRoutes() {
        val p = IntArray(2)
        EdgePath.pointAt(3, 20, 10, 10, 0, 0, p)
        assertEquals(17 to 10, p[0] to p[1])
        EdgePath.pointAt(15, 20, 10, 10, 0, 0, p)
        assertEquals(10 to 5, p[0] to p[1])
        EdgePath.pointAt(25, 20, 10, 10, 0, 0, p)
        assertEquals(5 to 0, p[0] to p[1])
    }
}
