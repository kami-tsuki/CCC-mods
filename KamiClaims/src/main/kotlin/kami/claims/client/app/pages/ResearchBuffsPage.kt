package kami.claims.client.app.pages

import kami.claims.client.app.ClaimsApp
import kami.claims.client.app.ClaimsPage
import kami.claims.client.app.ClientLocks
import kami.claims.client.app.Dialogs
import kami.claims.client.store.ClientResearch
import kami.claims.net.BuffView
import kami.claims.net.BuffsView
import kami.libs.text.Phrase
import kami.libs.ui.app.Consequence
import kami.libs.ui.app.Route
import kami.libs.ui.core.Rect
import kami.libs.ui.core.Memo
import kami.libs.ui.core.Stack
import kami.libs.ui.core.Ui
import kami.libs.ui.style.Draw
import kami.libs.ui.style.Format
import kami.libs.ui.style.Icons
import kami.libs.ui.style.Palette
import kami.libs.ui.style.Severity
import kami.libs.ui.text.tr
import kami.libs.ui.widget.*

private const val TREE = "buffs"
private const val VIEW_NODE = "buffs:buffs_view"
private const val PULSE_EFFECT = "minecraft:instant_health"
private const val DEFAULT_COOLDOWN = 300L

private class BuffCard(
    val key: String, val effect: String, val title: String, val mode: String, val stats: String, val enabled: Boolean,
    val buff: BuffView?, val lock: Lock?, val toggleLock: Lock?
) {
    val detail = if (buff != null) "$mode, $stats" else stats
    val revealKey = "buff-reveal:$key"
    val lockKey = "buff-lock:$key"
    val buttonKey = "buff-toggle:$key"
    val exampleKey = "buff-example:$key"
    val detailKey = "buff-detail:$key"
}

private class Group(val title: String, val cards: List<BuffCard>)

class ResearchBuffsPage(app: ClaimsApp) : ClaimsPage(app) {
    override val title get() = tr("kami_claims.nav.buffs")

    private var scrollHeight = 0
    private val built = Memo()
    private var groups = emptyList<Group>()
    private var examples = emptyList<BuffCard>()
    private var surchargeText = ""
    private var explainText = ""
    private var emptyText = ""
    private var surchargeH = 0
    private var explainH = 0

    override fun draw(ui: Ui, r: Rect) {
        val buffs = ClientResearch.buffs
        val lock = ClientLocks.buffsTeaser()
        if (lock != null) {
            rebuild(buffs, r.w)
            return teaser(ui, r, lock)
        }
        ui.scroll("buffs-page", r, scrollHeight) { area ->
            rebuild(buffs, area.w)
            val stack = Stack(area.x, area.y, area.w, CARD_GAP)
            val pointsBar = stack.take(PROGRESS_LABELLED_H)
            ui.progressBar(pointsBar, buffs.used.toLong(), buffs.points.toLong(), tr("kami_claims.buffs.points"), key = "buffs-points")
            ui.tooltip("buffs-explain", pointsBar, explainText)
            Draw.paragraph(ui.g, surchargeText, stack.x, stack.take(surchargeH).y, stack.w, if (buffs.surchargePerBorderChunk > 0) Palette.warning else Palette.textSecondary)
            val cols = cardColumns(stack.w, MIN_CARD_W, MAX_COLUMNS)
            var index = 0
            groups.forEach { group ->
                ui.section(stack, group.title, group.cards.size.toString())
                stack.cardGrid(group.cards.size, cols, { CARD_H }) { rect, i -> drawCard(ui, rect, group.cards[i], index++, false) }
            }
            if (groups.isEmpty()) Draw.text(ui.g, emptyText, stack.x, stack.take(Draw.LINE).y, Palette.textMuted)
            scrollHeight = stack.bottom - area.y
        }
    }

    private fun teaser(ui: Ui, r: Rect, lock: Lock) {
        val panel = r.top(TEASER_H.coerceAtMost(r.h))
        ui.lockedPanel(panel, tr("kami_claims.nav.buffs"), tr("kami_claims.buffs.teaser"), lock, Icons.LOCK, "buffs-locked")
        val rest = r.dropTop(panel.h + 8)
        if (rest.h < CARD_H) return
        val cols = cardColumns(rest.w, MIN_CARD_W, MAX_COLUMNS)
        Stack(rest.x, rest.y, rest.w).cardGrid(minOf(cols, examples.size), cols, { CARD_H }) { rect, i ->
            drawCard(ui, rect, examples[i], i, true)
            if (ui.lockVeil(rect, lock, examples[i].exampleKey)) app.navigate(Route("research", focus = VIEW_NODE))
        }
    }

    private fun rebuild(buffs: BuffsView, width: Int) = built.of(buffs, ClientResearch.defs, ClientResearch.state, width, Format.locale) {
        val state = ClientResearch.state
        val tree = ClientResearch.defs.trees.firstOrNull { it.id == TREE }
        val owned = buffs.list.mapTo(HashSet()) { it.key }
        val manage = if (state.canManage) null else Lock(tr("kami_claims.buffs.lock.rights"), tr("kami_claims.research.reason.rights"))
        val free = buffs.points - buffs.used
        val cards = ArrayList<Pair<String, BuffCard>>()
        buffs.list.forEach { buff ->
            val toggleLock = manage ?: if (!buff.enabled && buff.cost > free) Lock(tr("kami_claims.buffs.lock.points"), tr("kami_claims.buffs.lock.points.how", buff.cost, free)) else null
            cards += ClientResearch.node(buff.key)?.category.orEmpty() to BuffCard(
                buff.key, buff.effect, name(buff.effect, buff.amplifier), modeText(buff.mode == "pulse", buff.cooldownSeconds.toLong()),
                tr("kami_claims.buffs.stats", buff.cost, buff.tax), buff.enabled, buff, null, toggleLock
            )
        }
        val unowned = tree?.nodes.orEmpty().filter { node -> node.key !in owned && node.unlocks.any { it.kind == "buff" } }.sortedBy { it.level }
        val locked = ArrayList<BuffCard>()
        unowned.forEach { node ->
            val unlock = node.unlocks.first { it.kind == "buff" }
            val lock = if (state.level < node.level) Lock.level(node.level, node.label().resolve()) else Lock.research(node.label().resolve())
            val card = BuffCard(
                node.key, unlock.id, name(unlock.id, unlock.amplifier), modeText(unlock.id == PULSE_EFFECT, if (unlock.cooldownSeconds > 0) unlock.cooldownSeconds.toLong() else DEFAULT_COOLDOWN),
                tr("kami_libs.lock.ui.level", node.level), false, null, lock, null
            )
            cards += node.category to card
            locked += card
        }
        examples = locked.take(MAX_COLUMNS)
        val order = tree?.categories?.sortedBy { it.order }.orEmpty()
        val known = order.mapTo(HashSet()) { it.id }
        val grouped = ArrayList<Group>()
        order.forEach { category ->
            val inGroup = cards.filter { it.first == category.id }.map { it.second }
            if (inGroup.isNotEmpty()) grouped += Group(Phrase.or("kami_claims.research.category.${category.id}", category.title).resolve(), inGroup)
        }
        val rest = cards.filter { it.first !in known }.map { it.second }
        if (rest.isNotEmpty()) grouped += Group(tr("kami_claims.buffs.category.other"), rest)
        groups = grouped
        val per = buffs.surchargePerBorderChunk
        surchargeText = tr("kami_claims.buffs.surcharge", Format.number(per), Format.number(per.toLong() * buffs.borderChunks), Format.number(buffs.borderChunks))
        explainText = tr("kami_claims.buffs.explain")
        emptyText = tr("kami_claims.buffs.empty")
        surchargeH = Draw.paragraphHeight(surchargeText, width)
        explainH = Draw.paragraphHeight(explainText, width)
    }

    private fun name(effect: String, amplifier: Int) = effectName(effect) + " " + roman(amplifier + 1)

    private fun modeText(pulse: Boolean, cooldown: Long) =
        if (pulse) tr("kami_claims.buffs.mode.pulse", Format.number(cooldown / 60)) else tr("kami_claims.buffs.mode.aura")

    private fun drawCard(ui: Ui, r: Rect, card: BuffCard, index: Int, dim: Boolean) = ui.staggered(r, card.revealKey, index) { box ->
        val muted = card.buff == null || dim
        Draw.box(ui.g, box, if (card.enabled) Palette.selected else Palette.raised, if (card.enabled) Palette.success else Palette.border)
        val slot = Rect(box.x + PAD, box.y + PAD, SLOT, SLOT)
        ui.panel(slot, sunken = true)
        ui.effectIcon(slot, card.effect, if (muted) DIM else 0f)
        val x = slot.right + PAD
        val reserve = if (card.enabled) Draw.ICON else if (muted) CHIP_RESERVE else 0
        Draw.text(ui.g, Draw.fit(card.title, box.right - x - PAD - reserve - if (card.buff != null && !dim) BUTTON_W else 0), x, box.y + PAD, if (muted) Palette.textSecondary else Palette.text)
        if (card.enabled) Draw.leadIcon(ui.g, Icons.CHECK, box.right - PAD - Draw.ICON + 2, box.y + PAD + 4, Palette.success)
        val textW = box.right - x - PAD
        Draw.text(ui.g, Draw.fit(if (card.buff != null) card.mode else card.stats, textW - if (card.buff != null && !dim) BUTTON_W else 0), x, box.y + PAD + Draw.LINE + 1, Palette.textMuted)
        ui.tooltip(card.detailKey, box, card.detail)
        val buff = card.buff
        if (buff != null && !dim) {
            val button = Rect(box.right - PAD - BUTTON_W, box.y + (box.h - SMALL_H) / 2, BUTTON_W, SMALL_H)
            val label = tr(if (buff.enabled) "kami_claims.buffs.disable" else "kami_claims.buffs.enable")
            val busy = pending("buff_toggle")
            val style = if (buff.enabled) ButtonStyle.SECONDARY else ButtonStyle.PRIMARY
            if (ui.lockedButton(button, label, card.toggleLock, if (buff.enabled) Icons.CROSS else Icons.CHECK, style, !busy, pending = busy, key = card.buttonKey)) toggle(buff, card.title)
        } else if (card.lock != null && !dim) {
            if (ui.lockVeil(box, card.lock, card.lockKey)) app.navigate(Route("research", focus = card.key))
        }
    }

    private fun toggle(buff: BuffView, title: String) {
        if (buff.enabled) { act("buff_toggle", buff.key, key = "buff_toggle"); return }
        val buffs = ClientResearch.buffs
        Dialogs.confirm(
            app, tr("kami_claims.buffs.confirm.title", title), tr("kami_claims.buffs.confirm.subtitle"), Icons.SHIELD,
            listOf(
                Consequence(tr("kami_claims.buffs.confirm.points", buff.cost, buffs.points - buffs.used)),
                Consequence(tr("kami_claims.buffs.confirm.tax", Format.number(buff.tax), Format.number(buff.tax.toLong() * buffs.borderChunks)), Severity.WARNING)
            ),
            tr("kami_claims.buffs.enable"), "buff_toggle", arrayOf(buff.key)
        )
    }
}

private const val CARD_H = 36
private const val MIN_CARD_W = 190
private const val MAX_COLUMNS = 3
private const val PAD = 5
private const val SLOT = 24
private const val BUTTON_W = 84
private const val CHIP_RESERVE = 70
private const val DIM = 0.55f
