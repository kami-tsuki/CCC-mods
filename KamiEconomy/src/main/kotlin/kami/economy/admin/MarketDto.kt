package kami.economy.admin

import kami.economy.StarterGood
import kotlinx.serialization.Serializable

@Serializable
data class OrderRow(
    val side: String, val item: String, val id: Long, val owner: String, val ownerName: String, val price: Int, val amount: Int, val lot: Int, val placedAt: Long,
    val label: String = ""
) {
    val entry get() = "$side:$item:$id"
}

@Serializable
class StarterRow(val good: StarterGood, val supply: Int, val demand: Int, val course: Double, val items: Int, val status: String = "", val statusReason: String = "", val statusRev: String = "0")

@Serializable
class StarterList(val rows: List<StarterRow>, val rev: String)

@Serializable
class FrozenRow(val id: Long, val type: String, val state: String, val actor: String, val actorName: String, val item: String, val qty: Int, val owed: Long, val payees: String)

@Serializable
class StatusRow(val item: String, val flags: String, val reason: String, val by: String, val byName: String, val at: Long, val rev: String)

@Serializable
class WatchTop(val item: String, val count: Int)

@Serializable
class WatchStats(val total: Int, val players: Int, val items: Int, val top: List<WatchTop>)
