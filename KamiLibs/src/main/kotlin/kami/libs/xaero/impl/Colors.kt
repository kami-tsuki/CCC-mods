package kami.libs.xaero.impl

object Colors {
    private val chat = intArrayOf(
        0x000000, 0x0000AA, 0x00AA00, 0x00AAAA, 0xAA0000, 0xAA00AA, 0xFFAA00, 0xAAAAAA,
        0x555555, 0x5555FF, 0x55FF55, 0x55FFFF, 0xFF5555, 0xFF55FF, 0xFFFF55, 0xFFFFFF
    )

    fun toRgba(argb: Int) = (argb shl 8) or (argb ushr 24)

    fun over(dst: Int, src: Int): Int {
        val sa = src ushr 24
        val da = dst ushr 24
        if (sa == 0) return dst
        if (da == 0 || sa == 255) return src
        val keep = da * (255 - sa) / 255
        val out = sa + keep
        fun channel(shift: Int) = ((((src shr shift) and 255) * sa + ((dst shr shift) and 255) * keep) / out).coerceIn(0, 255)
        return (out shl 24) or (channel(16) shl 16) or (channel(8) shl 8) or channel(0)
    }

    fun combine(hashes: IntArray): Int {
        var h = 0
        var any = false
        hashes.forEachIndexed { i, v ->
            if (v != 0) {
                any = true
                h = (h * 31 + v) * 31 + i + 1
            }
        }
        return if (!any) 0 else if (h == 0) 1 else h
    }

    fun nearestChatColor(rgb: Int): Int {
        val r = rgb shr 16 and 255
        val g = rgb shr 8 and 255
        val b = rgb and 255
        var best = 0
        var bestD = Int.MAX_VALUE
        chat.forEachIndexed { i, c ->
            val dr = r - (c shr 16 and 255)
            val dg = g - (c shr 8 and 255)
            val db = b - (c and 255)
            val d = dr * dr + dg * dg + db * db
            if (d < bestD) { bestD = d; best = i }
        }
        return best
    }

    class Diff(val removed: Set<String>, val upserted: Set<String>)

    fun diff(old: Map<String, Int>, new: Map<String, Int>) =
        Diff(old.keys - new.keys, new.filter { (k, v) -> old[k] != v }.keys)
}
