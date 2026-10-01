package kami.claims.client.app.pages

import kami.claims.client.app.ClaimsApp
import kami.claims.client.app.ClaimsPage
import kami.claims.client.app.ClientLocks
import kami.claims.client.stackOf
import kami.claims.client.store.ClientResearch
import kami.claims.net.NodeView
import kami.claims.net.QueueView
import kami.claims.research.Capacity
import kami.claims.research.NodeState
import kami.libs.ui.anim.countUp
import kami.libs.ui.anim.spring
import kami.libs.ui.app.Route
import kami.libs.ui.core.Cursor
import kami.libs.ui.core.Rect
import kami.libs.ui.core.Row
import kami.libs.ui.core.Stack
import kami.libs.ui.core.Ui
import kami.libs.ui.style.Draw
import kami.libs.ui.style.Format
import kami.libs.ui.style.Icons
import kami.libs.ui.style.Palette
import kami.libs.ui.style.Icon
import kami.libs.ui.core.Tip
import kami.libs.ui.style.Severity
import kami.libs.ui.text.tr
import kami.libs.ui.widget.*

class ResearchQueuePage(app: ClaimsApp) : ClaimsPage(app) {
    override val title get() = tr("kami_claims.nav.queue")

    private val actions = ResearchActions(this)
    private var splitFor: List<QueueView>? = null
    private var running = emptyList<QueueView>()
    private var waiting = emptyList<QueueView>()

    private fun slotTile(cap: Capacity, icon: Icon): KpiTile {
        val state = ClientResearch.state
        val full = state.used(cap) >= state.max(cap)
        val raise = ClientLocks.raise(cap)
        return KpiTile(
            ResearchLook.capacity(cap.name.lowercase()), "${state.used(cap)}/${state.max(cap)}", icon, if (full) Palette.warning else Palette.text,
            sub = raise?.label, tip = raise?.how?.let { Tip.text(it) }
        )
    }

    override fun draw(ui: Ui, r: Rect) {
        val state = ClientResearch.state
        ui.kpiRow(r.top(TILE_H), listOf(
            slotTile(Capacity.RESEARCH_SLOTS, Icons.TOOL),
            slotTile(Capacity.QUEUE_SLOTS, Icons.SCROLL),
            KpiTile(tr("kami_claims.kpi.treasury"), Format.number(ui.countUp("queue-treasury", state.treasury)), Icons.TREASURY, Palette.money, flashValue = state.treasury)
        ), key = "queue-kpi")
        val body = r.dropTop(TILE_H + 6)
        if (state.queue !== splitFor) {
            splitFor = state.queue
            val (active, idle) = state.queue.partition { it.state == NodeState.RESEARCHING }
            running = active
            waiting = idle
        }
        val runningSlots = maxOf(state.max(Capacity.RESEARCH_SLOTS), running.size)
        val waitingSlots = maxOf(state.max(Capacity.QUEUE_SLOTS), waiting.size)
        val nobodyOnline = info?.members?.none { it.online } == true
        val height = SECTION_H + runningSlots * ROW_H + SECTION_H + waitingSlots * ROW_H + (if (nobodyOnline && running.isNotEmpty()) NOTICE_H else 0) + (if (state.queue.isEmpty()) EMPTY_H else 0)
        ui.scroll("research-queue", body, height) { area ->
            val stack = Stack(area.x, area.y, area.w, 0)
            if (nobodyOnline && running.isNotEmpty()) ui.callout(stack.take(NOTICE_H), Severity.WARNING, tr("kami_claims.research.queue.offline"))
            ui.section(stack.take(SECTION_H), tr("kami_claims.research.status.researching"), "${running.size}/$runningSlots")
            repeat(runningSlots) { i -> slot(ui, stack.take(ROW_H), area.y, running.getOrNull(i), nobodyOnline, "running:$i") }
            ui.section(stack.take(SECTION_H), tr("kami_claims.research.queue.waiting"), "${waiting.size}/$waitingSlots")
            repeat(waitingSlots) { i -> slot(ui, stack.take(ROW_H), area.y, waiting.getOrNull(i), nobodyOnline, "waiting:$i") }
            if (state.queue.isEmpty() && ui.emptyState(stack.take(EMPTY_H), tr("kami_claims.research.queue.empty.title"), tr("kami_claims.research.queue.empty"), action = tr("kami_claims.research.queue.open_tree"), key = "queue-empty")) {
                app.navigate(Route("research"))
            }
        }
    }

    private fun slot(ui: Ui, r: Rect, originY: Int, entry: QueueView?, nobodyOnline: Boolean, key: String) {
        val node = entry?.let { ClientResearch.node(it.node) }
        if (entry == null || node == null) {
            freeSlot(ui, r.inset(0, 1), key)
            return
        }
        val y = originY + ui.spring("queue-row:${node.key}", (r.y - originY).toFloat(), stiffness = ROW_STIFFNESS).toInt()
        val row = Rect(r.x, y, r.w, r.h).inset(0, 1)
        ui.panel(row, sunken = true)
        val inner = row.inset(3)
        val line = Row(inner, 4)
        val stack = stackOf(node.icon)
        if (!stack.isEmpty) ui.itemSlot(line.take(inner.h), stack, key = "queue-icon:${node.key}")
        val manage = ClientResearch.state.canManage
        if (ui.iconButton(line.iconSlot(), Icons.LOCATE, tr("kami_claims.research.action.show"), key = "queue-show:${node.key}")) app.navigate(Route("research", focus = node.key))
        if (manage) controls(ui, line, node, entry, inner.w < NARROW_W)
        val spec = barFor(node, entry, nobodyOnline, ui.wallMillis)
        val status = ClientResearch.status(node.key)
        ui.progressBar(line.remaining(), spec.value, spec.max, node.label().resolve(), spec.remaining, ResearchLook.color(status), shimmer = spec.stalled == false, paused = spec.stalled == true, key = "queue-bar:${node.key}")
    }

    private fun freeSlot(ui: Ui, row: Rect, key: String) {
        val hover = ui.hover("queue-free:$key", row)
        ui.focusable("queue-free:$key")
        val color = if (hover) Palette.textSecondary else Palette.border
        if (hover) { ui.cursor = Cursor.HAND; Draw.fill(ui.g, row, Palette.alpha(Palette.hover, 0x80)) }
        Draw.dashedRect(ui.g, row, color)
        Draw.textCentered(ui.g, Draw.fit(tr("kami_claims.research.queue.free"), row.w - 8), row, if (hover) Palette.text else Palette.textMuted)
        ui.focusRing("queue-free:$key", row)
        if (ui.pressed(row) != null || ui.activatedByKey("queue-free:$key")) app.navigate(Route("research"))
    }

    private fun controls(ui: Ui, line: Row, node: NodeView, entry: QueueView, narrow: Boolean) {
        val queue = ClientResearch.state.queue
        val index = queue.indexOf(entry)
        if (entry.state == NodeState.RESEARCHING) {
            val label = tr("kami_claims.research.action.pause")
            val pending = actions.pending(node)
            val fired = if (narrow) ui.iconButton(line.iconSlot(), Icons.PENDING, label, enabled = !pending, key = "queue-pause:${node.key}")
            else ui.edgeButton(line, label, Icons.PENDING, pending = pending, key = "queue-pause:${node.key}")
            if (fired) actions.pause(node)
            return
        }
        if (ui.iconButton(line.iconSlot(), Icons.SORT_DOWN, tr("kami_claims.research.action.down"), enabled = index < queue.lastIndex, key = "queue-down:${node.key}")) actions.move(node, index + 1)
        if (ui.iconButton(line.iconSlot(), Icons.SORT_UP, tr("kami_claims.research.action.up"), enabled = index > 0, key = "queue-up:${node.key}")) actions.move(node, index - 1)
        val reason = actions.startReason(node, entry)
        val label = tr("kami_claims.research.action.start")
        val pending = actions.pending(node)
        val fired = if (narrow) ui.iconButton(line.iconSlot(), Icons.CHECK, label, enabled = reason == null && !pending, disabledReason = reason, key = "queue-start:${node.key}")
        else ui.edgeButton(line, label, Icons.CHECK, ButtonStyle.PRIMARY, reason == null, reason, pending = pending, key = "queue-start:${node.key}")
        if (fired) actions.start(node, entry)
    }
}

private const val TILE_H = 44
private const val ROW_H = 30
private const val SECTION_H = 16
private const val NOTICE_H = 28
private const val EMPTY_H = 96
private const val NARROW_W = 300
private const val ROW_STIFFNESS = 220f

private fun Row.iconSlot() = takeFromRight(SMALL_H).centered(SMALL_H, SMALL_H)
