package kami.claims.world

enum class Cover { CLEAR, SELF, SOLID }

object SkyColumn {
    fun clear(from: Int, top: Int, cover: (Int) -> Cover): Boolean {
        for (y in from until top) if (cover(y) == Cover.SOLID) return false
        return true
    }
}
