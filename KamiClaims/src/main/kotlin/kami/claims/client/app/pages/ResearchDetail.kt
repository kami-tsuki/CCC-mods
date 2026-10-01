package kami.claims.client.app.pages

import kami.claims.client.app.ClaimsPage
import kami.claims.client.stackOf
import kami.claims.client.store.ClientResearch
import kami.claims.client.store.NodeStatus
import kami.claims.net.NodeView
import kami.claims.net.QueueView
import kami.claims.net.TreeView
import kami.claims.research.NodeState
import kami.libs.ui.anim.reveal
import kami.libs.ui.core.Rect
import kami.libs.ui.core.Stack
import kami.libs.ui.core.Ui
import kami.libs.ui.style.Draw
import kami.libs.ui.style.Format
import kami.libs.ui.style.Icons
import kami.libs.ui.style.Palette
import kami.libs.ui.style.Severity
import kami.libs.ui.style.TextStyle
import kami.libs.ui.text.tr
import kami.libs.ui.widget.*

class ResearchDetail(private val page: ClaimsPage) {
    private val actions = ResearchActions(page)
    private var contentHeight = 0
    private var shownNode: String? = null
    private var facts: NodeFacts? = null

    fun draw(ui: Ui, r: Rect, node: NodeView?, onClose: (() -> Unit)?, select: (String) -> Unit) {
        val appear = ui.reveal("research-detail-open", (node?.key ?: "").hashCode().toLong())
        val box = r.slideIn(appear, APPEAR_SHIFT, 0)
        val area = ui.sidePanel(box).rest
        onClose?.let { close -> if (ui.closeButton(Rect(box.right - CLOSE_SIZE - 3, box.y + 3, CLOSE_SIZE, CLOSE_SIZE), "research-detail-close")) close() }
        if (node == null) {
            empty(ui, area)
            return
        }
        if (shownNode != node.key) {
            shownNode = node.key
            ui.forget(ui.id("scroll:research-detail"))
            contentHeight = 0
        }
        val status = ClientResearch.status(node.key)
        val queue = ClientResearch.queued(node.key)
        val footer = area.bottom(CONTROL_H)
        val body = area.dropBottom(CONTROL_H, 6)
        ui.scroll("research-detail", body, contentHeight) { view -> contentHeight = content(ui, view, node, status, queue, onClose != null, select) }
        if (status == NodeStatus.DONE) completed(ui, footer) else footer(ui, footer, node, status, queue)
        Draw.veilBox(ui.g, box.inset(1), appear, strength = APPEAR_VEIL)
    }

    private fun factsOf(node: NodeView): NodeFacts {
        val trees = ClientResearch.trees
        facts?.takeIf { it.node === node && it.trees === trees }?.let { return it }
        val tree = trees.firstOrNull { it.id == node.tree }
        val index = tree?.nodes?.indexOf(node)
        return NodeFacts(
            node, trees,
            node.summary().resolve().ifEmpty { null },
            tree?.let { t -> node.requires.map { t.nodes[it] } },
            node.conditionTexts().withIndex().filter { it.value.key != NODE_CONDITION }.map { it.index to it.value.resolve() },
            tree?.nodes?.filter { index in it.requires }.orEmpty() + trees.filter { it.id != node.tree }.flatMap { t -> t.nodes.filter { node.key in it.external } },
            node.tasks.map(::taskText)
        ).also { facts = it }
    }

    private fun empty(ui: Ui, area: Rect) {
        val top = area.top(EMPTY_H)
        ui.emptyState(top, tr("kami_claims.research.detail.empty.title"), tr("kami_claims.research.detail.pick"), key = "research-detail-empty")
        val stack = Stack(area.x, area.y + EMPTY_H + 6, area.w, CHIP_GAP)
        ui.section(stack, tr("kami_claims.research.legend"))
        ui.chipFlow(stack, NodeStatus.entries.map { ChipSpec(ResearchLook.label(it), ResearchLook.color(it), ResearchLook.icon(it)) }, "research-legend")
    }

    private fun completed(ui: Ui, r: Rect) {
        Draw.box(ui.g, r, Severity.SUCCESS.tint, Severity.SUCCESS.edge)
        val label = tr("kami_claims.research.detail.completed")
        val x = r.centerX - (Draw.width(label) + Draw.ICON) / 2
        Draw.leadIcon(ui.g, Icons.CHECK, x, r.centerY, Palette.success)
        Draw.text(ui.g, label, x + Draw.ICON, r.y + (r.h - 8) / 2, Palette.success)
    }

    private fun content(ui: Ui, area: Rect, node: NodeView, status: NodeStatus, queue: QueueView?, closable: Boolean, select: (String) -> Unit): Int {
        val stack = Stack(area.x, area.y, area.w)
        val facts = factsOf(node)
        header(ui, stack.take(HEADER_H), node, status, closable)
        facts.summary?.let { text -> stack.take(Draw.paragraph(ui.g, text, stack.x, stack.bottom, area.w, Palette.textSecondary)) }
        properties(ui, stack, node)
        requirements(ui, stack, node, status, facts, select)
        tasks(ui, stack, node, status, queue, facts.taskTexts)
        unlocks(ui, stack, node)
        leadsTo(ui, stack, node, facts, select)
        return stack.bottom - area.y
    }

    private fun header(ui: Ui, row: Rect, node: NodeView, status: NodeStatus, closable: Boolean) {
        val icon = stackOf(node.icon)
        val x = if (icon.isEmpty) row.x else row.x + 24
        if (!icon.isEmpty) ui.itemSlot(Rect(row.x, row.y + 2, 22, 22), icon, key = "research-icon")
        val right = row.right - if (closable) CLOSE_SIZE + 2 else 0
        Draw.text(ui.g, Draw.fit(node.label().resolve(), right - x, TextStyle.HEADING), x, row.y + 1, TextStyle.HEADING)
        ui.statusPill(x, row.y + 12, ResearchLook.label(status), ResearchLook.severity(status), key = "research-status")
    }

    private fun properties(ui: Ui, stack: Stack, node: NodeView) {
        val row = stack.take(INFO_H)
        val locked = node.level > ClientResearch.state.level
        val level = tr("kami_libs.common.level") + " " + node.level
        var x = row.x
        Draw.text(ui.g, level, x, row.y + 2, if (locked) Palette.warning else Palette.textSecondary)
        x += Draw.width(level) + 10
        val cost = Format.money(node.cost)
        Draw.text(ui.g, cost, x, row.y + 2, Palette.money)
        x += Draw.width(cost) + 10
        val time = Format.duration(node.timeMs)
        Draw.text(ui.g, time, x, row.y + 2, Palette.textSecondary)
        if (node.xp >= 0) Draw.textRight(ui.g, Format.number(node.xp) + " " + tr("kami_claims.research.detail.xp"), row.right, row.y + 2, Palette.textMuted)
    }

    private fun requirements(ui: Ui, stack: Stack, node: NodeView, status: NodeStatus, facts: NodeFacts, select: (String) -> Unit) {
        val deps = facts.deps ?: return
        val state = ClientResearch.state
        val flags = ClientResearch.conditionMet(node.key)
        val unknown = if (status == NodeStatus.DONE) true else null
        val levelOk = flags?.firstOrNull() ?: unknown
        if (node.level == 0 && deps.isEmpty() && node.external.isEmpty() && facts.conditions.isEmpty()) return
        ui.section(stack, tr("kami_claims.research.detail.requirements"))
        if (node.level > 0) ui.requirementRow(stack.take(REQUIREMENT_ROW_H), tr("kami_claims.research.cond.level", node.level), levelOk)
        deps.forEach { dep ->
            ui.requirementRow(stack.take(REQUIREMENT_ROW_H), dep.label().resolve(), dep.key in state.done) { select(dep.key) }
        }
        node.external.forEach { key -> ui.requirementRow(stack.take(REQUIREMENT_ROW_H), ResearchGraph.externalLabel(key), key in state.done) { select(key) } }
        facts.conditions.forEach { (index, text) -> ui.requirementRow(stack.take(REQUIREMENT_ROW_H), text, flags?.getOrNull(index + 1) ?: unknown) }
    }

    private fun tasks(ui: Ui, stack: Stack, node: NodeView, status: NodeStatus, queue: QueueView?, texts: List<String>) {
        if (node.tasks.isEmpty()) return
        ui.section(stack, tr("kami_claims.research.detail.tasks"))
        node.tasks.forEachIndexed { i, task ->
            val slot = stack.take(TASK_H)
            val icons = taskStacks(task, ui.wallMillis)
            icons.forEachIndexed { k, item -> ui.itemIcon(slot.x + k * TASK_ICON_STEP, slot.y + (slot.h - ITEM_ICON) / 2, item, "task-icon:${node.key}:$i:$k") }
            val row = slot.dropLeft(icons.size * TASK_ICON_STEP)
            val progress = if (status == NodeStatus.DONE) task.target else (queue?.tasks?.getOrNull(i) ?: 0L).coerceAtMost(task.target)
            Draw.text(ui.g, Draw.fit(texts[i], row.w), row.x, row.y - 1, Palette.textSecondary)
            val line = Rect(row.x, row.bottom - SMALL_H, row.w, SMALL_H)
            val showDeposit = task.kind == "deposit" && status != NodeStatus.DONE
            val label = tr("kami_claims.research.action.deposit")
            val bar = if (showDeposit) line.dropRight(buttonWidth(label) + 4) else line
            ui.progressBar(Rect(bar.x, bar.y + 3, bar.w, PROGRESS_BAR_H + 2), progress, task.target, key = "task:${node.key}:$i")
            if (showDeposit) {
                val reason = actions.depositReason(queue) ?: if (progress >= task.target) tr("kami_claims.research.reason.task_done") else null
                if (ui.edgeButton(line, label, enabled = reason == null, disabledReason = reason, pending = page.pending("research:${node.key}:$i"), key = "deposit:${node.key}:$i")) actions.deposit(node, i)
            }
        }
    }

    private fun unlocks(ui: Ui, stack: Stack, node: NodeView) {
        if (node.unlocks.isEmpty()) return
        ui.section(stack, tr("kami_claims.research.detail.unlocks"))
        ui.unlockList(stack, node.unlocks, "unlock:${node.key}")
    }

    private fun leadsTo(ui: Ui, stack: Stack, node: NodeView, facts: NodeFacts, select: (String) -> Unit) {
        val next = facts.next.takeIf { it.isNotEmpty() } ?: return
        ui.section(stack, tr("kami_claims.research.detail.leads"))
        ui.chipFlow(stack, next.map { ChipSpec(if (it.tree == node.tree) it.label().resolve() else ResearchGraph.externalLabel(it.key), ResearchLook.color(ClientResearch.status(it.key))) }, "leads:${node.key}")?.let { select(next[it].key) }
    }

    private fun footer(ui: Ui, r: Rect, node: NodeView, status: NodeStatus, queue: QueueView?) {
        val key = "research-action:${node.key}"
        val pending = actions.pending(node)
        when {
            queue == null -> {
                val reason = actions.enqueueReason(status)
                if (ui.button(r, tr("kami_claims.research.action.enqueue"), Icons.ADD, ButtonStyle.PRIMARY, reason == null, reason, pending = pending, key = key)) actions.enqueue(node)
            }
            queue.state == NodeState.RESEARCHING -> {
                val reason = if (ClientResearch.state.canManage) null else tr("kami_claims.research.reason.rights")
                if (ui.button(r, tr("kami_claims.research.action.pause"), Icons.PENDING, enabled = reason == null, disabledReason = reason, pending = pending, key = key)) actions.pause(node)
            }
            else -> {
                val reason = actions.startReason(node, queue)
                if (ui.button(r, tr("kami_claims.research.action.start"), Icons.CHECK, ButtonStyle.PRIMARY, reason == null, reason, pending = pending, key = key)) actions.start(node, queue)
            }
        }
    }
}

private class NodeFacts(
    val node: NodeView,
    val trees: List<TreeView>,
    val summary: String?,
    val deps: List<NodeView>?,
    val conditions: List<Pair<Int, String>>,
    val next: List<NodeView>,
    val taskTexts: List<String>
)

private const val NODE_CONDITION = "kami_claims.research.cond.node"
private const val HEADER_H = 26
private const val CHIP_GAP = 3
private const val INFO_H = 12
private const val TASK_H = 24
private const val TASK_ICON_STEP = ITEM_ICON + 3
private const val EMPTY_H = 90
private const val CLOSE_SIZE = 16
private const val APPEAR_SHIFT = 8
private const val APPEAR_VEIL = 0.6f
