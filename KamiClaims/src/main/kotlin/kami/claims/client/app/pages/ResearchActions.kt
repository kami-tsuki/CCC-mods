package kami.claims.client.app.pages

import kami.claims.client.app.ClaimsPage
import kami.claims.client.app.Dialogs
import kami.claims.client.store.ClientResearch
import kami.claims.client.store.NodeStatus
import kami.claims.net.NodeView
import kami.claims.net.QueueView
import kami.claims.research.Capacity
import kami.claims.research.NodeState
import kami.libs.ui.app.Consequence
import kami.libs.ui.style.Format
import kami.libs.ui.style.Icons
import kami.libs.ui.text.tr

class ResearchActions(private val page: ClaimsPage) {
    fun pending(node: NodeView) = page.pending("research:${node.key}")

    fun enqueue(node: NodeView) { page.act("research_enqueue", node.key, key = "research:${node.key}") }

    fun pause(node: NodeView) { page.act("research_pause", node.key, key = "research:${node.key}") }

    fun deposit(node: NodeView, task: Int) { page.act("research_deposit", node.key, task.toString(), key = "research:${node.key}:$task") }

    fun move(node: NodeView, to: Int) { page.act("research_move", node.key, to.toString(), key = "research:${node.key}") }

    fun start(node: NodeView, queue: QueueView) {
        if (queue.paid) { page.act("research_start", node.key, key = "research:${node.key}"); return }
        Dialogs.confirm(
            page.app, tr("kami_claims.research.start.title", node.label().resolve()), tr("kami_claims.research.start.subtitle"), Icons.TREE,
            listOf(Consequence(tr("kami_claims.research.start.pay", Format.money(node.cost))), Consequence(tr("kami_claims.research.start.time", Format.duration(node.timeMs)))),
            tr("kami_claims.research.action.start"), "research_start", arrayOf(node.key)
        )
    }

    fun enqueueReason(status: NodeStatus): String? {
        val s = ClientResearch.state
        return when {
            !s.canManage -> tr("kami_claims.research.reason.rights")
            status == NodeStatus.LOCKED -> tr("kami_claims.research.reason.locked")
            s.used(Capacity.QUEUE_SLOTS) >= s.max(Capacity.QUEUE_SLOTS) -> tr("kami_claims.research.reason.queue_full")
            else -> null
        }
    }

    fun startReason(node: NodeView, queue: QueueView): String? {
        val s = ClientResearch.state
        return when {
            !s.canManage -> tr("kami_claims.research.reason.rights")
            queue.state == NodeState.QUEUED -> tr("kami_claims.research.reason.tasks")
            !queue.paid && s.treasury < node.cost -> tr("kami_claims.research.reason.treasury", Format.money(node.cost))
            s.used(Capacity.RESEARCH_SLOTS) >= s.max(Capacity.RESEARCH_SLOTS) -> tr("kami_claims.research.reason.slots")
            else -> null
        }
    }

    fun depositReason(queue: QueueView?): String? = if (queue?.state == NodeState.QUEUED) null else tr("kami_claims.research.reason.deposit_closed")
}
