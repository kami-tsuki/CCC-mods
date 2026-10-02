package kami.claims.world

/** What a block above a plant means for the sky rule. */
enum class Cover { CLEAR, SELF, SOLID }

/** Minecraft-free column check, so it can be unit tested. */
object SkyColumn {
    /** True when no block in [from, top) is [Cover.SOLID]. */
    fun clear(from: Int, top: Int, cover: (Int) -> Cover): Boolean {
        for (y in from until top) if (cover(y) == Cover.SOLID) return false
        return true
    }
}
