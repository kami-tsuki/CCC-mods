package kami.libs.ui.anim

class Feel {
    var hover = 0f
    var press = 0f
    var focus = 0f
}

class Slot(initial: Float) {
    var value = initial
    var from = initial
    var target = initial
    var start = 0.0
    var sign = 0
    var mark = 0L
    var prev = 0L
    var seen = 0L
    var fresh = true
    var spring: Spring? = null
    var feel: Feel? = null
}

class MotionStore(private val sweepEvery: Int = 120, private val maxAge: Int = 300) {
    private val slots = HashMap<String, Slot>()

    fun slot(key: String, frame: Long, initial: Float): Slot {
        var s = slots[key]
        if (s == null) { s = Slot(initial); slots[key] = s }
        s.seen = frame
        return s
    }

    fun sweep(frame: Long): Int {
        if (frame % sweepEvery != 0L) return 0
        val before = slots.size
        slots.values.removeIf { frame - it.seen > maxAge }
        return before - slots.size
    }

    fun forget(prefix: String) { slots.keys.removeIf { it.startsWith(prefix) } }
}
