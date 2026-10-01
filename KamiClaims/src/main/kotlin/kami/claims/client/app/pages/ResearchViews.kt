package kami.claims.client.app.pages

import kami.claims.client.app.ClientLocks
import kami.claims.client.app.capacityRow
import kami.claims.client.stackOf
import kami.claims.client.cyclingStacksOf
import kami.claims.client.store.ClientResearch
import kami.claims.client.store.NodeStatus
import kami.claims.net.NodeView
import kami.claims.net.QueueView
import kami.claims.net.TaskView
import kami.claims.net.UnlockView
import kami.claims.research.Capacity
import kami.claims.research.NodeState
import kami.libs.ui.core.Cursor
import kami.libs.ui.core.Rect
import kami.libs.ui.core.Stack
import kami.libs.ui.core.Ui
import kami.libs.ui.style.Draw
import kami.libs.ui.style.Format
import kami.libs.ui.style.Icon
import kami.libs.ui.style.Icons
import kami.libs.ui.style.Palette
import kami.libs.ui.style.Severity
import kami.libs.ui.text.tr
import kami.libs.ui.text.trJson
import kami.libs.ui.widget.ChipSpec
import kami.libs.ui.widget.CAPACITY_BAR_H
import kami.libs.ui.widget.CARD_HEADER_H
import kami.libs.ui.widget.CAPACITY_HINT_H
import kami.libs.ui.widget.PROGRESS_LABELLED_H
import kami.libs.ui.widget.card
import kami.libs.ui.widget.itemSlot
import kami.libs.ui.widget.chipFlow
import kami.libs.ui.widget.effectName
import kami.libs.ui.widget.roman
import kami.libs.ui.widget.progressBar
import net.minecraft.client.resources.language.I18n
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.resources.ResourceLocation
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items

private val UPPERCASE = Regex("[A-Z]")
private val capacityKeys = HashMap<String, String>()
private const val ITEM_CELL = 22
const val REQUIREMENT_ROW_H = 11
private const val STACK_GAP = 4
private const val LEVEL_BAR_SPACER = 2
private const val CARD_BODY_MARGIN = 12

object ResearchLook {
    fun color(status: NodeStatus) = when (status) {
        NodeStatus.DONE -> Palette.success
        NodeStatus.RESEARCHING -> Palette.info
        NodeStatus.PAUSED -> Palette.warning
        NodeStatus.READY -> Palette.money
        NodeStatus.QUEUED -> Palette.textSecondary
        NodeStatus.AVAILABLE -> Palette.text
        NodeStatus.LOCKED -> Palette.textMuted
    }

    fun icon(status: NodeStatus): Icon? = when (status) {
        NodeStatus.DONE -> Icons.CHECK
        NodeStatus.RESEARCHING -> Icons.CLOCK
        NodeStatus.PAUSED -> Icons.PENDING
        NodeStatus.READY -> Icons.STAR
        NodeStatus.QUEUED -> Icons.SCROLL
        NodeStatus.AVAILABLE -> null
        NodeStatus.LOCKED -> Icons.LOCK
    }

    fun severity(status: NodeStatus) = when (status) {
        NodeStatus.DONE -> Severity.SUCCESS
        NodeStatus.RESEARCHING, NodeStatus.QUEUED -> Severity.INFO
        NodeStatus.PAUSED -> Severity.WARNING
        NodeStatus.READY, NodeStatus.AVAILABLE -> Severity.NEUTRAL
        NodeStatus.LOCKED -> Severity.DANGER
    }

    fun label(status: NodeStatus) = tr("kami_claims.research.status.${status.name.lowercase()}")

    fun capacity(id: String) = tr(capacityKeys.getOrPut(id) { "kami_claims.research.capacity.${id.replace(UPPERCASE) { "_" + it.value.lowercase() }}" })
}

private fun humanize(path: String) = path.substringAfterLast(':').replace("*", "").replace('_', ' ').replace('/', ' ').trim().replaceFirstChar { it.uppercase() }

private fun tagName(tag: String): String {
    val id = ResourceLocation.tryParse(tag.removePrefix("#")) ?: return humanize(tag)
    val path = "${id.namespace}.${id.path.replace('/', '.')}"
    listOf("tag.item.$path", "tag.block.$path").firstOrNull(I18n::exists)?.let { return tr("kami_claims.research.tag.any", I18n.get(it)) }
    return tr("kami_claims.research.tag.any", humanize(id.path))
}

private fun selectorName(selector: String): String {
    if (selector.startsWith("#")) return tagName(selector)
    val id = ResourceLocation.tryParse(selector) ?: return humanize(selector)
    val item = BuiltInRegistries.ITEM.getOptional(id).filter { it != Items.AIR }.orElse(null)
    if (item != null) return ItemStack(item).hoverName.string
    BuiltInRegistries.BLOCK.getOptional(id).orElse(null)?.let { return it.name.string }
    BuiltInRegistries.ENTITY_TYPE.getOptional(id).orElse(null)?.let { return it.description.string }
    return humanize(selector)
}

fun subjectName(kind: String, subject: String): String = when (kind) {
    "process" -> subjectName("", subject.substringAfter('|').ifEmpty { subject.substringBefore('|') })
    "hold" -> trJson(subject)
    "visit" -> humanize(subject.removePrefix("#"))
    else -> subject.split(',').map { it.trim() }.filter { it.isNotEmpty() }.joinToString(tr("kami_claims.research.task.or")) { selectorName(it) }
}

private val iconlessTasks = setOf("hold", "visit", "process", "structure", "taxes", "trade", "trade_value")

fun taskStacks(task: TaskView, now: Long): List<ItemStack> = if (task.kind in iconlessTasks) emptyList() else cyclingStacksOf(task.subject, now)

fun taskText(task: TaskView) = tr("kami_claims.research.task.${task.kind}", Format.number(task.target), subjectName(task.kind, task.subject))

fun tasksProgress(node: NodeView, queue: QueueView?): Pair<Long, Long> {
    val total = node.tasks.sumOf { it.target }
    val done = node.tasks.indices.sumOf { i -> (queue?.tasks?.getOrNull(i) ?: 0L).coerceAtMost(node.tasks[i].target) }
    return done to total
}

class BarSpec(val value: Long, val max: Long, val remaining: String?, val stalled: Boolean? = null)

fun barFor(node: NodeView, queue: QueueView, nobodyOnline: Boolean, now: Long): BarSpec = when (queue.state) {
    NodeState.RESEARCHING, NodeState.PAUSED -> {
        val countdown = ClientResearch.countdown(queue, node, ticking = !nobodyOnline)
        val left = countdown.remaining(now)
        val stalled = queue.state == NodeState.PAUSED || nobodyOnline
        BarSpec(
            node.timeMs - left, node.timeMs,
            when {
                queue.state == NodeState.RESEARCHING && nobodyOnline -> tr("kami_claims.research.queue.offline_short")
                left == 0L && queue.state == NodeState.RESEARCHING -> tr("kami_claims.research.queue.finishing")
                else -> tr("kami_claims.research.queue.left", Format.duration(left))
            },
            stalled
        )
    }
    NodeState.READY -> BarSpec(1, 1, ResearchLook.label(NodeStatus.READY))
    NodeState.QUEUED -> tasksProgress(node, queue).let { (done, total) ->
        BarSpec(done, total, if (total == 0L) ResearchLook.label(NodeStatus.QUEUED) else tr("kami_claims.research.queue.tasks", Format.number(done), Format.number(total)))
    }
}

fun xpText(xp: Long = ClientResearch.state.xp): String = when {
    ClientResearch.xpTracked -> tr("kami_claims.research.levels.xp", Format.number(xp), Format.number(ClientResearch.state.xpCeiling))
    ClientResearch.atMaxLevel -> tr("kami_claims.research.levels.max")
    else -> tr("kami_claims.research.levels.requirements_only")
}

fun Ui.levelBar(r: Rect) {
    val s = ClientResearch.state
    val label = tr("kami_claims.research.level", s.level)
    val tracked = ClientResearch.xpTracked
    val value = if (tracked) s.xp - s.xpFloor else if (ClientResearch.atMaxLevel) 1L else 0L
    progressBar(r, value, if (tracked) s.xpCeiling - s.xpFloor else 1L, label, xpText(), key = "level-bar")
}

fun Ui.levelRequirements(stack: Stack, level: Int) {
    ClientResearch.levelRequirements(level).forEach { (phrase, met) -> requirementRow(stack.take(REQUIREMENT_ROW_H), phrase.resolve(), met) }
}

fun Ui.requirementRow(r: Rect, text: String, met: Boolean?, onClick: (() -> Unit)? = null) {
    val over = onClick != null && hovering(r)
    if (over) { Draw.fill(g, r, Palette.hover); cursor = Cursor.HAND }
    val icon = when (met) { true -> Icons.CHECK; false -> Icons.CROSS; null -> Icons.PENDING }
    val color = when (met) { true -> Palette.success; false -> Palette.danger; null -> Palette.textMuted }
    val x = r.x + Draw.leadIcon(g, icon, r.x, r.centerY, color) + 2
    Draw.text(g, Draw.fit(text, r.right - x), x, r.y + 1, if (over) Palette.text else Palette.textSecondary)
    if (onClick != null && pressed(r) != null) onClick()
}

private val progressCapacities = listOf(Capacity.CHUNKS, Capacity.CITIZENS, Capacity.PROVINCES, Capacity.TREASURY)

fun progressCardHeight() = CARD_HEADER_H + CARD_BODY_MARGIN + PROGRESS_LABELLED_H + 2 * STACK_GAP +
    progressCapacities.sumOf { CAPACITY_BAR_H + STACK_GAP + if (ClientLocks.raise(it) != null) CAPACITY_HINT_H + STACK_GAP else 0 }

fun Ui.progressCard(r: Rect) {
    val body = card(r, tr("kami_claims.dashboard.progress"), Icons.STAR)
    val stack = Stack(body.x, body.y, body.w, STACK_GAP)
    levelBar(stack.take(PROGRESS_LABELLED_H))
    stack.take(LEVEL_BAR_SPACER)
    progressCapacities.forEach { capacityRow(stack, it) }
}

fun unlockText(unlock: UnlockView) = when (unlock.kind) {
    "capacity" -> tr("kami_claims.research.unlock.capacity", unlock.count, ResearchLook.capacity(unlock.id))
    "money" -> tr("kami_claims.research.unlock.money", Format.money(unlock.amount))
    "feature" -> tr("kami_claims.research.unlock.feature", ClientLocks.featureName(unlock.id))
    "token" -> tr("kami_claims.research.unlock.token", ClientLocks.tokenName(unlock.id))
    "buff" -> tr("kami_claims.research.unlock.buff", effectName(unlock.id) + " " + roman(unlock.amplifier + 1))
    "buff_points" -> tr("kami_claims.research.unlock.buff_points", unlock.count)
    "loan" -> tr("kami_claims.research.unlock.loan", Format.money(unlock.amount))
    "loan_slots" -> tr("kami_claims.research.unlock.loan_slots", unlock.count)
    else -> tr("kami_claims.research.unlock.${unlock.kind}", unlock.id.substringAfter(':').replace('_', ' '))
}

fun unlockColor(unlock: UnlockView) = when (unlock.kind) {
    "capacity", "buff_points", "loan_slots" -> Palette.success
    "money", "token", "loan" -> Palette.money
    "feature", "buff" -> Palette.brass
    else -> Palette.textSecondary
}

fun unlockIcon(unlock: UnlockView): Icon? = when (unlock.kind) {
    "feature" -> ClientLocks.featureIcon(unlock.id)
    "buff" -> Icons.SHIELD
    "buff_points" -> Icons.STAR
    "token", "money", "loan", "loan_slots" -> Icons.COIN
    else -> null
}

private val itemUnlockKinds = setOf("output", "block", "recipe")

fun isItemUnlock(unlock: UnlockView) = unlock.kind in itemUnlockKinds && !stackOf(unlock.id).isEmpty

fun Ui.unlockList(stack: Stack, unlocks: List<UnlockView>, key: String) {
    val (items, others) = unlocks.partition(::isItemUnlock)
    if (items.isNotEmpty()) {
        val perRow = (stack.w / ITEM_CELL).coerceAtLeast(1)
        val grid = stack.take((items.size + perRow - 1) / perRow * ITEM_CELL)
        items.forEachIndexed { i, unlock ->
            val slot = Rect(grid.x + i % perRow * ITEM_CELL, grid.y + i / perRow * ITEM_CELL, ITEM_CELL - 2, ITEM_CELL - 2)
            itemSlot(slot, stackOf(unlock.id), key = "$key:item:$i")
        }
    }
    if (others.isNotEmpty()) chipFlow(stack, others.map { ChipSpec(unlockText(it), unlockColor(it), unlockIcon(it)) }, "$key:chip")
}

