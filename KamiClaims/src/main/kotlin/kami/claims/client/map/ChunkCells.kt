package kami.claims.client.map

object ChunkCells {
    val SIDES = listOf(0 to -1, 0 to 1, -1 to 0, 1 to 0)

    fun cell(key: Long) = (key shr 32).toInt() to key.toInt()
}
