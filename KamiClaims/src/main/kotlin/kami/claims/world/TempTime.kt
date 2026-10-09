package kami.claims.world

object TempTime {
    fun seconds(blockId: String, tagIds: Collection<String>, fixed: Map<String, Double>, hardness: Double, perHardness: Double, min: Double): Double =
        fixed[blockId] ?: tagIds.mapNotNull { fixed["#$it"] }.minOrNull() ?: maxOf(min, hardness * perHardness)
}
