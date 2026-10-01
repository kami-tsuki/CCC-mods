package kami.claims.client.app.pages

import kami.claims.client.app.ClaimsApp
import kami.claims.client.app.ClaimsPage
import kami.claims.client.app.ClientLocks
import kami.claims.client.app.Dialogs
import kami.claims.client.store.ClientResearch
import kami.claims.net.ActiveLoanView
import kami.claims.net.LoanOfferView
import kami.claims.net.LoansView
import kami.claims.research.Capacity
import kami.libs.ui.app.Consequence
import kami.libs.ui.app.Route
import kami.libs.ui.core.Rect
import kami.libs.ui.core.Memo
import kami.libs.ui.core.Stack
import kami.libs.ui.core.Tip
import kami.libs.ui.core.Ui
import kami.libs.ui.style.Draw
import kami.libs.ui.style.Format
import kami.libs.ui.style.Icons
import kami.libs.ui.style.Palette
import kami.libs.ui.style.Severity
import kami.libs.ui.text.tr
import kami.libs.ui.text.trn
import kami.libs.ui.widget.*

private const val PAD = 5
private const val BUTTON_W = 84
private const val MIN_CARD_W = 250
private const val WIDE_CARD_W = 320
private const val ROW_H = 11
private const val TITLE_H = SMALL_H + 3

private class OfferCard(
    val offer: LoanOfferView, val title: String, val lines: Array<String>, val colors: IntArray, val twoCol: Boolean, val h: Int,
    val lock: Lock?, val take: Lock?, val node: String?, val confirm: List<Consequence>
) {
    val tip = Tip(title, lines.indices.map { lines[it] to colors[it] })
    val tipKey = "loan-offer-tip:${offer.id}"
    val revealKey = "loan-offer-reveal:${offer.id}"
    val lockKey = "loan-offer-lock:${offer.id}"
    val buttonKey = "loan-take:${offer.id}"
}

private class ActiveCard(
    val loan: ActiveLoanView, val title: String, val paidLabel: String, val leftLabel: String, val overdueText: String?, val h: Int,
    val repay: Lock?, val confirm: List<Consequence>
) {
    val revealKey = "loan-active-reveal:${loan.id}"
    val barKey = "loan-bar:${loan.id}"
    val buttonKey = "loan-repay:${loan.id}"
}

class LoansPage(app: ClaimsApp) : ClaimsPage(app) {
    override val title get() = tr("kami_claims.nav.loans")

    private var scrollHeight = 0
    private val built = Memo()
    private var offers = emptyList<OfferCard>()
    private var active = emptyList<ActiveCard>()
    private var cols = 1
    private var slotsLabel = ""
    private var slotNext: Lock? = null
    private var defaultText = ""
    private var defaultH = 0
    private var explainText = ""
    private var explainH = 0
    private var emptyText = ""

    override fun draw(ui: Ui, r: Rect) {
        val loans = ClientResearch.loans
        val lock = ClientLocks.loansTeaser()
        if (lock != null) {
            ui.lockedPanel(r.top(TEASER_H.coerceAtMost(r.h)), tr("kami_claims.nav.loans"), tr("kami_claims.loans.teaser"), lock, Icons.LOCK, "loans-locked")
            return
        }
        ui.scroll("loans-page", r, scrollHeight) { area ->
            rebuild(loans, area.w)
            val stack = Stack(area.x, area.y, area.w, CARD_GAP)
            ui.capacityRow(stack, slotsLabel, loans.active.size, loans.slots, slotNext, "loans-slots")
            if (loans.inDefault) {
                val box = stack.take(defaultH + 2 * PAD)
                Draw.box(ui.g, box, Palette.alpha(Palette.danger, 0x30), Palette.alpha(Palette.danger, 0x90))
                val lead = Draw.leadIcon(ui.g, Icons.WARNING, box.x + PAD, box.y + PAD + 4, Palette.danger)
                Draw.paragraph(ui.g, defaultText, box.x + PAD + lead + 2, box.y + PAD, box.w - 2 * PAD - lead - 2, Palette.danger)
            }
            ui.section(stack, tr("kami_claims.loans.active.title"), active.size.toString())
            if (active.isEmpty()) Draw.text(ui.g, emptyText, stack.x, stack.take(Draw.LINE).y, Palette.textMuted)
            active.forEachIndexed { i, card -> drawActive(ui, stack.take(card.h), card, i) }
            val offersHead = stack.take(14)
            ui.section(offersHead, tr("kami_libs.common.offers"), offers.size.toString())
            ui.tooltip("loans-explain", offersHead, explainText)
            stack.cardGrid(offers.size, cols, { offers[it].h }) { rect, i -> drawOffer(ui, rect, offers[i], i) }
            scrollHeight = stack.bottom - area.y
        }
    }

    private fun rebuild(loans: LoansView, width: Int) = built.of(loans, ClientResearch.defs, ClientResearch.state, width, Format.locale) {
        val state = ClientResearch.state
        val slots = loans.slots
        val free = loans.active.size < slots
        val cap = state.max(Capacity.TREASURY)
        cols = cardColumns(width, MIN_CARD_W, 2)
        val cardW = cardWidth(width, cols)
        val twoCol = cardW >= WIDE_CARD_W
        val nodes = ClientResearch.defs.trees.flatMap { it.nodes }
        val rights = if (state.canManage) null else Lock(tr("kami_claims.loans.reason.rights"))
        slotsLabel = tr("kami_claims.loans.slots")
        slotNext = nodes.asSequence().filter { n -> n.key !in state.done && n.unlocks.any { it.kind == "loan_slots" } }.minByOrNull { it.level }
            ?.let { n -> Lock.raise(n.level, slots + n.unlocks.filter { it.kind == "loan_slots" }.sumOf { it.count }) }
        defaultText = tr("kami_claims.loans.default")
        defaultH = Draw.paragraphHeight(defaultText, width - 2 * PAD - Draw.ICON - 2)
        explainText = tr("kami_claims.loans.explain")
        explainH = Draw.paragraphHeight(explainText, width)
        emptyText = tr("kami_claims.loans.active.empty")
        active = loans.active.map { loan ->
            val remaining = loan.total - loan.paid
            val overdue = loan.overdue
            val repay = rights ?: if (state.treasury < remaining) Lock(tr("kami_claims.loans.reason.funds", Format.money(remaining))) else null
            ActiveCard(
                loan, tr("kami_claims.research.unlock.loan", Format.money(loan.principal)), tr("kami_libs.common.paid"),
                tr("kami_libs.common.left", trn("kami_claims.unit.day", loan.daysLeft.toLong())),
                if (overdue > 0) tr("kami_claims.loans.overdue", Format.money(overdue)) else null,
                2 * PAD + TITLE_H + PROGRESS_LABELLED_H + if (overdue > 0) ROW_H + 2 else 0, repay,
                listOf(
                    Consequence(tr("kami_claims.loans.confirm.repay.pay", Format.money(remaining))),
                    Consequence(tr("kami_claims.loans.confirm.repay.after", Format.money(state.treasury - remaining)), if (state.treasury - remaining < 0) Severity.DANGER else Severity.NEUTRAL)
                )
            )
        }
        offers = loans.offers.map { offer ->
            val amount = offer.amount
            val total = offer.total
            val perDay = offer.perDay
            val cooldown = offer.cooldownDays
            val days = trn("kami_claims.unit.day", offer.termDays.toLong())
            val extra = total - amount
            val lines = ArrayList<String>(5)
            val colors = ArrayList<Int>(5)
            val dim = !offer.unlocked
            lines += tr("kami_claims.loans.received", Format.money(amount)); colors += if (dim) Palette.textSecondary else Palette.money
            lines += tr("kami_claims.loans.payback", Format.money(total)); colors += if (dim) Palette.textMuted else Palette.text
            lines += tr("kami_claims.loans.extra", Format.money(extra), offer.interestPct); colors += Palette.warning
            lines += tr("kami_claims.loans.installment", Format.money(perDay), days); colors += if (dim) Palette.textMuted else Palette.textSecondary
            if (cooldown > 0) { lines += tr("kami_claims.loans.cooldown", trn("kami_claims.unit.day", cooldown.toLong())); colors += Palette.warning }
            val node = nodes.firstOrNull { n -> n.unlocks.any { it.kind == "loan" && it.id == offer.id } }
            val lock = if (offer.unlocked) null else Lock.level(offer.level, node?.label()?.resolve())
            val take = rights ?: when {
                loans.inDefault -> Lock(tr("kami_claims.loans.reason.default"))
                !free -> Lock(tr("kami_claims.loans.reason.slots"))
                cooldown > 0 -> Lock(tr("kami_claims.loans.reason.cooldown", cooldown))
                cap > 0 && state.treasury + amount > cap -> Lock(tr("kami_claims.loans.reason.cap", Format.money(cap.toLong())))
                else -> null
            }
            val rows = 1
            OfferCard(
                offer, tr("kami_claims.research.unlock.loan", Format.money(amount)), lines.toTypedArray(), colors.toIntArray(), twoCol, 2 * PAD + TITLE_H + rows * ROW_H + 2, lock, take, node?.key,
                listOf(
                    Consequence(tr("kami_claims.loans.received", Format.money(amount))),
                    Consequence(tr("kami_claims.loans.payback", Format.money(total))),
                    Consequence(tr("kami_claims.loans.extra", Format.money(extra), offer.interestPct), Severity.WARNING),
                    Consequence(tr("kami_claims.loans.installment", Format.money(perDay), days)),
                    Consequence(tr("kami_claims.loans.confirm.blocked"), Severity.WARNING)
                )
            )
        }
    }

    private fun drawOffer(ui: Ui, r: Rect, card: OfferCard, index: Int) = ui.staggered(r, card.revealKey, index) { box ->
        if (card.lock == null) body(ui, box, card, false)
        else if (ui.locked(box, card.lock, card.lockKey) { body(ui, it, card, true) } && card.node != null) app.navigate(Route("research", focus = card.node))
    }

    private fun body(ui: Ui, box: Rect, card: OfferCard, dim: Boolean) {
        Draw.box(ui.g, box, Palette.raised, Palette.border)
        val button = Rect(box.right - PAD - BUTTON_W, box.y + PAD, BUTTON_W, SMALL_H)
        Draw.text(ui.g, Draw.fit(card.title, box.w - 3 * PAD - if (dim) 0 else BUTTON_W), box.x + PAD, box.y + PAD + 3, if (dim) Palette.textSecondary else Palette.text)
        if (!dim) {
            val busy = pending("loan_take")
            if (ui.lockedButton(button, tr("kami_claims.loans.take"), card.take, Icons.COIN, ButtonStyle.PRIMARY, !busy, pending = busy, key = card.buttonKey)) take(card)
        }
        Draw.text(ui.g, Draw.fit(card.lines[1], box.w - 2 * PAD), box.x + PAD, box.y + PAD + TITLE_H, if (dim) Palette.textMuted else Palette.textSecondary)
        ui.tooltip(card.tipKey, Rect(box.x, box.y, box.w - if (dim) 0 else BUTTON_W + 2 * PAD, box.h), card.tip)
    }

    private fun drawActive(ui: Ui, r: Rect, card: ActiveCard, index: Int) = ui.staggered(r, card.revealKey, index) { box ->
        val late = card.overdueText != null
        Draw.box(ui.g, box, if (late) Palette.alpha(Palette.warning, 0x20) else Palette.raised, if (late) Palette.warning else Palette.border)
        Draw.text(ui.g, Draw.fit(card.title, box.w - 3 * PAD - BUTTON_W), box.x + PAD, box.y + PAD + 3, Palette.text)
        val button = Rect(box.right - PAD - BUTTON_W, box.y + PAD, BUTTON_W, SMALL_H)
        val busy = pending("loan_repay")
        if (ui.lockedButton(button, tr("kami_claims.loans.repay"), card.repay, Icons.CHECK, ButtonStyle.SECONDARY, !busy, pending = busy, key = card.buttonKey)) repay(card)
        val bar = Rect(box.x + PAD, box.y + PAD + TITLE_H, box.w - 2 * PAD, PROGRESS_LABELLED_H)
        ui.progressBar(bar, card.loan.paid, card.loan.total, card.paidLabel, card.leftLabel, if (late) Palette.warning else Palette.brass, key = card.barKey)
        card.overdueText?.let { text ->
            val y = bar.bottom + 2
            val lead = Draw.leadIcon(ui.g, Icons.WARNING, bar.x, y + 4, Palette.warning)
            Draw.text(ui.g, Draw.fit(text, bar.w - lead - 2), bar.x + lead + 2, y, Palette.warning)
        }
    }

    private fun take(card: OfferCard) = Dialogs.confirm(
        app, tr("kami_claims.loans.confirm.take.title", card.title), tr("kami_claims.loans.confirm.take.subtitle"), Icons.COIN,
        card.confirm, tr("kami_claims.loans.take"), "loan_take", arrayOf(card.offer.id)
    )

    private fun repay(card: ActiveCard) = Dialogs.confirm(
        app, tr("kami_claims.loans.confirm.repay.title", card.title), tr("kami_claims.loans.confirm.repay.subtitle"), Icons.COIN,
        card.confirm, tr("kami_claims.loans.repay"), "loan_repay", arrayOf(card.loan.id)
    )
}
