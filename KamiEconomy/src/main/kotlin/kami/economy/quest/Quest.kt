package kami.economy.quest

import kotlinx.serialization.Serializable

enum class QuestState { OPEN, DONE, EXPIRED, CANCELLED }

@Serializable
data class QuestBoost(val kind: String, val pct: Int, val hours: Int)

class Quest(
    val id: Long,
    val item: String,
    val rarity: Int,
    var lotPrice: Int,
    val lot: Int,
    var amount: Int,
    var filled: Int,
    val course: Double,
    val offset: Double,
    val createdAt: Long,
    var expiresAt: Long,
    var state: QuestState,
    var boosts: List<QuestBoost>,
    var winner: String? = null,
    var endedAt: Long? = null,
    val pick: String = "{}"
) {
    val open get() = state == QuestState.OPEN
    val fillPct: Long get() = if (amount > 0) filled * 100L / amount else 0L
    val left get() = amount - filled
    val total get() = amount.toLong() / lot.coerceAtLeast(1) * lotPrice
}

class QuestDelivery(val quest: Long, val player: String, val country: String?, val qty: Int, val paid: Long, val at: Long, val key: String)

class QuestClock(var next: Long = 0, val last: LongArray = LongArray(3))

@Serializable
class PickFactor(val item: String, val weight: Double, val age: Double, val volume: Double, val value: Double, val demand: Double, val cooldown: Double)

@Serializable
class PickInfo(val chosen: String, val total: Int, val top: List<PickFactor>)

class QuestSpec(
    val item: String, val rarity: Int, val amount: Int, val hours: Int,
    val lotPrice: Int? = null, val offset: Double? = null, val boosts: List<QuestBoost>? = null
)

class QuestPreview(val rarity: Int, val pick: PickInfo?, val offset: Double, val lotPrice: Int, val amount: Int, val boosts: List<QuestBoost>)

object Rarity {
    val names = listOf("common", "uncommon", "rare", "mythic", "legendary")
    val colors = listOf(0x5555FF, 0x55FF55, 0xFFFF55, 0xAA55FF, 0xFF5555)
    val emoji = listOf("🟦", "🟩", "🟨", "🟪", "🌈")
    const val MAX = 4
}
