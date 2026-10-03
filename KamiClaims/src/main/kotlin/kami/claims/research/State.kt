package kami.claims.research

import kami.claims.now
import kotlinx.serialization.Serializable

@Serializable
enum class NodeState { QUEUED, READY, RESEARCHING, PAUSED }

@Serializable
class QueueEntry(
    val node: String,
    var state: NodeState = NodeState.QUEUED,
    var remainingMs: Long = 0,
    var penaltyMs: Long = 0,
    var paid: Boolean = false,
    val tasks: MutableMap<Int, Long> = mutableMapOf(),
    val by: String? = null,
    val since: Long = now()
)

@Serializable
class BuffState(val enabled: MutableSet<String> = mutableSetOf(), val billed: MutableSet<String> = mutableSetOf())

@Serializable
class ResearchState(
    val done: MutableMap<String, Long> = mutableMapOf(),
    val queue: MutableList<QueueEntry> = mutableListOf(),
    val visited: MutableSet<String> = mutableSetOf()
)
