package kami.claims.client.app.pages

import kami.claims.client.app.ClaimsApp
import kami.claims.client.app.ClaimsPage
import kami.claims.client.stackOf
import kami.claims.client.store.ClientResearch
import kami.claims.net.LevelView
import kami.claims.net.NodeView
import kami.claims.net.UnlockView
import kami.libs.ui.anim.anim
import kami.libs.ui.anim.feel
import kami.libs.ui.anim.burst
import kami.libs.ui.anim.pulse
import kami.libs.ui.anim.reveal
import kami.libs.ui.anim.since
import kami.libs.ui.app.Route
import kami.libs.ui.core.Rect
import kami.libs.ui.core.Stack
import kami.libs.ui.core.Ui
import kami.libs.ui.style.Draw
import kami.libs.ui.core.Memo
import kami.libs.ui.style.Format
import kami.libs.ui.style.Icons
import kami.libs.ui.style.Palette
import kami.libs.ui.style.Severity
import kami.libs.ui.style.TextStyle
import kami.libs.ui.style.UiSound
import kami.libs.ui.text.tr
import kami.libs.ui.widget.*
import kotlin.math.PI
import kotlin.math.sin

class ResearchLevelsPage(app: ClaimsApp) : ClaimsPage(app) {
    override val title get() = tr("kami_claims.nav.levels")

    override val subtitle: String?
        get() = if (ClientResearch.state.country.isEmpty()) null else tr("kami_libs.lock.ui.level", ClientResearch.state.level) + " · " + xpText()

    private var selected: Int? = null
    private var centered = false
    private var detailHeight = 0
    private var levelUp = false
    private var popTrigger = 0L
    private val cardRewards = HashMap<Int, CardContent>()
    private val unlockedNodes = HashMap<Int, NodeChips>()
    private val localeSeen = Memo()

    private fun freshLocale() = localeSeen.of(Format.locale) { cardRewards.clear(); unlockedNodes.clear() }

    override fun opened(route: Route) {
        subscribe(
            ClientResearch.listen { cardRewards.clear(); unlockedNodes.clear() },
            ClientResearch.onChange { change -> if (change.leveledUp && app.isOpen) levelUp = true }
        )
        cardRewards.clear()
        unlockedNodes.clear()
        popTrigger = 0L
        centered = false
        selected = null
    }

    override fun draw(ui: Ui, r: Rect) {
        val levels = ClientResearch.defs.levels
        if (levels.isEmpty()) {
            ui.emptyState(r, tr("kami_claims.research.empty.title"), tr("kami_claims.research.empty.body"), key = "levels-empty")
            return
        }
        val compact = r.h < COMPACT_H
        val cardH = if (compact) CARD_H_COMPACT else CARD_H
        val strip = r.top(TRACK_H + cardH + STRIP_BAR_H)
        strip(ui, strip, levels, cardH, compact)
        val shown = selected ?: ClientResearch.state.level
        details(ui, r.dropTop(strip.h + 8), levels.firstOrNull { it.level == shown } ?: levels.first())
    }

    private fun strip(ui: Ui, r: Rect, levels: List<LevelView>, cardH: Int, compact: Boolean) {
        val state = ClientResearch.state
        val width = PAD * 2 + levels.size * CARD_W + (levels.size - 1) * CARD_GAP
        val celebrate = levelUp
        if (celebrate) { levelUp = false; centered = false; popTrigger++ }
        val scroll = ui.hstrip("levels-strip", r, width, CARD_W + CARD_GAP, snap = true) { area ->
            track(ui, area, levels.size)
            levels.forEach { level ->
                val card = Rect(area.x + PAD + (level.level - 1) * (CARD_W + CARD_GAP), area.y + TRACK_H, CARD_W, cardH)
                if (level.level == state.level && celebrate) {
                    ui.burst(card.centerX, card.centerY, Palette.brass, 28)
                    ui.burst(card.centerX, card.y, Palette.money, 18)
                    UiSound.levelUp()
                }
                if (card.right < r.x || card.x > r.right) return@forEach
                val content = content(level)
                if (ui.clickable(content.cardKey, card)) selected = level.level
                drawCard(ui, card, level, content, state.level, compact)
            }
        }
        if (!centered) { scroll.centerOn(PAD + (state.level - 1) * (CARD_W + CARD_GAP) + CARD_W / 2); centered = true }
    }

    private fun track(ui: Ui, area: Rect, count: Int) {
        val state = ClientResearch.state
        val position = ui.anim("levels-track", state.level - 1 + ClientResearch.xpFraction, speed = 6f)
        val y = area.y + TRACK_H / 2
        val x0 = area.x + PAD + CARD_W / 2
        val x1 = x0 + (count - 1) * (CARD_W + CARD_GAP)
        Draw.hline(ui.g, x0, y, x1 - x0, Palette.border)
        Draw.fill(ui.g, Rect(x0, y - 1, (position * (CARD_W + CARD_GAP)).toInt(), TRACK_FILL_H), Palette.brass)
        repeat(count) { i ->
            val color = when { i + 1 < state.level -> Palette.success; i + 1 == state.level -> Palette.brass; else -> Palette.borderStrong }
            Draw.fill(ui.g, Rect(x0 + i * (CARD_W + CARD_GAP) - TRACK_DOT / 2, y - TRACK_DOT / 2, TRACK_DOT, TRACK_DOT), color)
        }
        notch(ui, x0 + (state.level - 1) * (CARD_W + CARD_GAP), area.y + TRACK_H - NOTCH_H)
    }

    private fun notch(ui: Ui, centerX: Int, top: Int) {
        for (row in 0 until NOTCH_H) {
            val half = NOTCH_H - 1 - row
            ui.g.fill(centerX - half, top + row, centerX + half + 1, top + row + 1, Palette.brass)
        }
    }

    private fun drawCard(ui: Ui, slot: Rect, level: LevelView, content: CardContent, current: Int, compact: Boolean) {
        val isCurrent = level.level == current
        val pop = if (isCurrent && popTrigger > 0) (sin((ui.since("level-pop", popTrigger) / 1000f / POP_SECONDS).coerceIn(0f, 1f) * PI) * POP_PX).toInt() else 0
        val card = slot.grow(pop)
        val feel = ui.feel(content.feelKey, ui.hovering(slot), false, selected == level.level)
        val reached = level.level <= current
        val accent = when { isCurrent -> Palette.brass; reached -> Palette.success; else -> Palette.border }
        if (isCurrent) Draw.glow(ui.g, card, Palette.brass, 0.5f + 0.3f * ui.pulse(CURRENT_PULSE_MS))
        Draw.box(ui.g, card, Palette.mix(if (isCurrent) Palette.selected else Palette.raised, Palette.hover, feel.hover), Palette.mix(accent, Palette.brass, feel.focus))
        val icon = when { isCurrent -> Icons.STAR; reached -> Icons.CHECK; else -> Icons.LOCK }
        Draw.leadIcon(ui.g, icon, card.right - STATE_ICON_X, card.y + STATE_ICON_Y, if (reached) accent else Palette.textMuted)
        Draw.text(ui.g, content.title, card.x + CARD_PAD, card.y + TITLE_Y, if (reached) Palette.text else Palette.textSecondary)
        Draw.text(ui.g, Draw.fit(content.xp, CARD_W - 2 * CARD_PAD - Draw.ICON), card.x + CARD_PAD, card.y + XP_Y, Palette.textMuted)
        rewards(ui, card, content, if (compact) 1 else LINES, if (compact) REQUIREMENT_LINES_COMPACT else REQUIREMENT_LINES)
        if (isCurrent && (ClientResearch.xpTracked || ClientResearch.atMaxLevel)) progressStrip(ui, card)
        if (!reached) Draw.fill(ui.g, card.inset(1), Palette.alpha(Palette.surface, 0x70))
    }

    private fun rewards(ui: Ui, card: Rect, content: CardContent, lines: Int, requirementLines: Int) {
        val shownRequirements = content.requirements.take(requirementLines)
        shownRequirements.forEachIndexed { i, (text, met) ->
            ui.requirementRow(Rect(card.x + CARD_PAD, card.y + REQUIREMENT_Y + i * REQUIREMENT_STEP, CARD_W - 2 * CARD_PAD, REQUIREMENT_STEP), text, met)
        }
        val itemsTop = card.y + REQUIREMENT_Y + shownRequirements.size * REQUIREMENT_STEP + if (shownRequirements.isEmpty()) 0 else REQUIREMENT_GAP
        content.items.forEachIndexed { i, unlock ->
            ui.itemSlot(Rect(card.x + CARD_PAD + i * ITEM_STEP, itemsTop, ITEM_SLOT, ITEM_SLOT), stackOf(unlock.id), key = content.itemKeys[i])
        }
        val rest = content.rest
        val hidden = rest.size - lines
        val shown = if (hidden > 0) rest.take(lines - 1) else rest
        val top = if (content.items.isEmpty()) itemsTop else itemsTop + ITEM_SLOT + ITEM_GAP
        shown.forEachIndexed { i, unlock ->
            val lineY = top + i * Draw.LINE
            val iconW = unlockIcon(unlock)?.let { Draw.leadIcon(ui.g, it, card.x + CARD_PAD, lineY + 4, unlockColor(unlock)) } ?: 0
            Draw.text(ui.g, Draw.fit(unlockText(unlock), CARD_W - 2 * CARD_PAD - iconW), card.x + CARD_PAD + iconW, lineY, unlockColor(unlock))
        }
        if (hidden > 0) Draw.text(ui.g, tr("kami_claims.research.levels.more", hidden + 1), card.x + CARD_PAD, top + shown.size * Draw.LINE, Palette.textMuted)
    }

    private fun content(level: LevelView): CardContent { freshLocale(); return cardRewards.getOrPut(level.level) { cardContent(level) } }

    private fun cardContent(level: LevelView): CardContent {
        val (items, others) = level.rewards.partition(::isItemUnlock)
        val requirements = ClientResearch.levelRequirements(level.level).map { (phrase, met) -> phrase.resolve() to met }
        val shownItems = items.take(ITEMS_PER_CARD)
        val xp = if (level.xp == 0L) tr("kami_claims.research.levels.requirements_only") else tr("kami_claims.research.levels.xp_needed", Format.number(level.xp))
        return CardContent(tr("kami_libs.lock.ui.level", level.level), xp, "level-card:${level.level}", "level:${level.level}", shownItems, shownItems.indices.map { "level-item:${level.level}:$it" }, others + items.drop(ITEMS_PER_CARD), requirements)
    }

    private fun nodeChips(level: LevelView): NodeChips { freshLocale(); return unlockedNodes.getOrPut(level.level) {
        val nodes = ClientResearch.trees.flatMap { it.nodes }.filter { it.level == level.level }
        NodeChips(nodes, nodes.map { nodeChip(it.key, it.label().resolve()) })
    } }

    private fun progressStrip(ui: Ui, card: Rect) {
        val fraction = if (ClientResearch.xpTracked) ClientResearch.xpFraction else 1f
        val shown = ui.anim("level-card-progress", fraction, speed = 8f)
        Draw.thinBar(ui.g, Rect(card.x + STRIP_INSET, card.bottom - STRIP_BOTTOM, card.w - 2 * STRIP_INSET, STRIP_H), shown, Palette.brass)
    }

    private fun details(ui: Ui, r: Rect, level: LevelView) {
        val area = ui.sidePanel(r).rest
        val heading = area.top(HEADING_H)
        val current = ClientResearch.state.level
        val (stateLabel, severity) = when {
            level.level < current -> tr("kami_claims.research.levels.reached") to Severity.SUCCESS
            level.level == current -> tr("kami_claims.research.levels.current") to Severity.INFO
            else -> tr("kami_libs.common.locked") to Severity.NEUTRAL
        }
        val content = content(level)
        val title = content.title
        Draw.text(ui.g, title, heading.x, heading.y + HEADING_TEXT_Y, TextStyle.HEADING)
        ui.statusPill(heading.x + Draw.width(title, TextStyle.HEADING) + PILL_GAP, heading.y + HEADING_TEXT_Y, stateLabel, severity, key = "levels-state")
        Draw.textRight(ui.g, content.xp, heading.right, heading.y + HEADING_XP_Y, Palette.textMuted)
        ui.scroll("levels-detail", area.dropTop(HEADING_H + DETAIL_GAP), detailHeight) { view ->
            val unlocked = nodeChips(level)
            val rewards = { stack: Stack -> requirementsSection(ui, stack, level); rewardsSection(ui, stack, level) }
            val nodes = { stack: Stack -> nodesSection(ui, stack, level, unlocked) }
            if (view.w < SINGLE_COLUMN_W) {
                val middle = staged(ui, level, 0, view.x, view.y, view.w, rewards)
                detailHeight = staged(ui, level, 1, view.x, middle, view.w, nodes) - view.y
            } else {
                val (left, right) = view.columns(2, COLUMN_GAP)
                detailHeight = maxOf(staged(ui, level, 0, left.x, left.y, left.w, rewards), staged(ui, level, 1, right.x, right.y, right.w, nodes)) - view.y
            }
        }
    }

    private fun staged(ui: Ui, level: LevelView, index: Int, x: Int, y: Int, w: Int, draw: (Stack) -> Unit): Int {
        val appear = ui.reveal("levels-detail", level.level.toLong(), delayMs = index * STAGGER_MS)
        val stack = Stack(x + ((1f - appear) * APPEAR_SHIFT).toInt(), y, w)
        draw(stack)
        Draw.veilBox(ui.g, Rect(x, y, w, stack.bottom - y), appear, Palette.surface)
        return stack.bottom
    }

    private fun requirementsSection(ui: Ui, stack: Stack, level: LevelView) {
        if (level.requires.isEmpty()) return
        ui.section(stack, tr("kami_claims.research.detail.requirements"))
        ui.levelRequirements(stack, level.level)
    }

    private fun rewardsSection(ui: Ui, stack: Stack, level: LevelView) {
        ui.section(stack, tr("kami_claims.research.levels.rewards"))
        if (level.rewards.isEmpty()) emptyLine(ui, stack, tr("kami_claims.research.levels.no_rewards"))
        else ui.unlockList(stack, level.rewards, "level-rewards:${level.level}")
    }

    private fun nodesSection(ui: Ui, stack: Stack, level: LevelView, unlocked: NodeChips) {
        ui.section(stack, tr("kami_claims.research.levels.nodes"))
        if (unlocked.nodes.isEmpty()) emptyLine(ui, stack, tr("kami_claims.research.levels.no_nodes"))
        else ui.chipFlow(stack, unlocked.chips, "level-nodes:${level.level}")
            ?.let { app.navigate(Route("research", focus = unlocked.nodes[it].key)) }
    }

    private fun nodeChip(key: String, label: String): ChipSpec {
        val status = ClientResearch.status(key)
        return ChipSpec(label, ResearchLook.color(status), ResearchLook.icon(status))
    }

    private fun emptyLine(ui: Ui, stack: Stack, text: String) {
        Draw.text(ui.g, text, stack.x, stack.take(Draw.LINE).y, Palette.textMuted)
    }
}

private class CardContent(val title: String, val xp: String, val cardKey: String, val feelKey: String, val items: List<UnlockView>, val itemKeys: List<String>, val rest: List<UnlockView>, val requirements: List<Pair<String, Boolean>>)

private class NodeChips(val nodes: List<NodeView>, val chips: List<ChipSpec>)

private const val PAD = 4
private const val CARD_W = 104
private const val CARD_H = 78
private const val CARD_H_COMPACT = 64
private const val COMPACT_H = 240
private const val CARD_GAP = 6
private const val CARD_PAD = 5
private const val TITLE_Y = 4
private const val XP_Y = 16
private const val STATE_ICON_X = 13
private const val STATE_ICON_Y = 9
private const val ITEM_SLOT = 20
private const val ITEM_STEP = 22
private const val REQUIREMENT_Y = 28
private const val REQUIREMENT_STEP = 10
private const val REQUIREMENT_GAP = 3
private const val REQUIREMENT_LINES = 0
private const val REQUIREMENT_LINES_COMPACT = 0
private const val ITEM_GAP = 4
private const val STRIP_INSET = 4
private const val STRIP_BOTTOM = 6
private const val STRIP_H = 3
private const val TRACK_H = 12
private const val TRACK_FILL_H = 3
private const val TRACK_DOT = 5
private const val NOTCH_H = 4
private const val STRIP_BAR_H = 6
private const val ITEMS_PER_CARD = 4
private const val LINES = 2
private const val HEADING_H = 16
private const val HEADING_TEXT_Y = 1
private const val HEADING_XP_Y = 3
private const val PILL_GAP = 8
private const val DETAIL_GAP = 4
private const val COLUMN_GAP = 10
private const val SINGLE_COLUMN_W = 300
private const val POP_SECONDS = 0.45f
private const val POP_PX = 3
private const val CURRENT_PULSE_MS = 2000L
private const val STAGGER_MS = 60L
private const val APPEAR_SHIFT = 4
