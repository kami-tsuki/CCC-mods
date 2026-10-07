package kami.claims.client.app.pages

import kami.claims.client.app.ClaimsApp
import kami.claims.client.app.ClaimsPage
import kami.claims.client.app.ClientLocks
import kami.claims.client.app.Dialogs
import kami.claims.LendState
import kami.claims.client.store.ClientResearch
import kami.claims.net.ActiveLoanView
import kami.claims.net.LoanOfferView
import kami.claims.net.LoanTerms
import kami.claims.net.LoansView
import kami.claims.net.MarketLoanView
import kami.claims.net.OwnLoanView
import kami.claims.research.Capacity
import kami.claims.research.Limits.MAX_LINKS
import kami.claims.service.Diplomacy
import kami.libs.ui.app.Consequence
import kami.libs.ui.app.Route
import kami.libs.ui.core.Rect
import kami.libs.ui.core.Memo
import kami.libs.ui.core.Row
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
private const val ROW_H = 11
private const val TITLE_H = SMALL_H + 3
private const val FIELD_H = 10 + CONTROL_H

private class Texts {
    val nav = tr("kami_claims.nav.loans")
    val teaser = tr("kami_claims.loans.teaser")
    val tabBorrow = tr("kami_claims.loans.tab.borrow")
    val tabLend = tr("kami_claims.loans.tab.lend")
    val tabActive = tr("kami_claims.loans.tab.active")
    val marketTitle = tr("kami_claims.loans.market.title")
    val offersTitle = tr("kami_libs.common.offers")
    val activeTitle = tr("kami_claims.loans.active.title")
    val lentTitle = tr("kami_claims.loans.lent.title")
    val lendTitle = tr("kami_claims.loans.lend.title")
    val formTitle = tr("kami_claims.loans.form.title")
    val amount = tr("kami_claims.loans.form.amount")
    val term = tr("kami_claims.loans.form.term")
    val days = tr("kami_claims.loans.form.days")
    val interest = tr("kami_claims.loans.form.interest")
    val tariffHead = tr("kami_claims.trade.tariff")
    val tariffToggle = tr("kami_claims.loans.form.tariff")
    val tariffTip = tr("kami_claims.loans.form.tariff.tip")
    val min = tr("kami_claims.loans.form.min")
    val max = tr("kami_claims.loans.form.max")
    val none = tr("kami_claims.loans.form.none")
    val limit = tr("kami_claims.loans.form.limit")
    val reserve = tr("kami_claims.loans.form.reserve")
    val relist = tr("kami_claims.loans.form.relist")
    val relistTip = tr("kami_claims.loans.form.relist.tip")
    val create = tr("kami_claims.loans.form.create")
    val range = tr("kami_claims.loans.form.range")
    val take = tr("kami_claims.loans.take")
    val repay = tr("kami_claims.loans.repay")
    val enable = tr("kami_claims.loans.enable")
    val disable = tr("kami_claims.loans.disable")
    val delete = tr("kami_claims.loans.delete")
    val forgive = tr("kami_claims.loans.forgive")
}

private class OfferCard(
    val offer: LoanOfferView, val title: String, val lines: Array<String>, val colors: IntArray, val h: Int,
    val lock: Lock?, val take: Lock?, val node: String?, val confirm: List<Consequence>
) {
    val tip = Tip(title, lines.indices.map { lines[it] to colors[it] })
    val tipKey = "loan-offer-tip:${offer.id}"
    val revealKey = "loan-offer-reveal:${offer.id}"
    val lockKey = "loan-offer-lock:${offer.id}"
    val buttonKey = "loan-take:${offer.id}"
}

private class ActiveCard(
    val loan: ActiveLoanView, val title: String, val paidLabel: String, val leftLabel: String, val notes: List<Pair<String, Int>>, val h: Int,
    val repay: Lock?, val confirm: List<Consequence>, val readOnly: Boolean
) {
    val revealKey = "loan-active-reveal:${loan.id}"
    val barKey = "loan-bar:${loan.id}"
    val buttonKey = "loan-repay:${loan.id}"
}

private class MarketCard(val offer: MarketLoanView, val title: String, val lines: List<Pair<String, Int>>, val rights: Lock?, takeLock: Lock?, val confirm: List<Consequence>) {
    val h = 2 * PAD + TITLE_H + lines.size * ROW_H + 2
    val reason = takeLock?.reason ?: offer.blocked.takeIf { it.isNotEmpty() }?.let { tr(it) }
    val revealKey = "loan-market-reveal:${offer.id}"
    val buttonKey = "loan-borrow:${offer.id}"
}

private class OwnCard(
    val offer: OwnLoanView, val title: String, val stateLabel: String, val stateColor: Int, val lines: List<Pair<String, Int>>, val toggleLabel: String,
    val toggleReason: String?, val deleteLabel: String, val deleteTitle: String, val deleteSubtitle: String, val rights: Lock?, val confirm: List<Consequence>
) {
    val h = 2 * PAD + TITLE_H + lines.size * ROW_H + 2
    val revealKey = "loan-own-reveal:${offer.id}"
    val toggleKey = "loan_offer_toggle:${offer.id}"
    val deleteKey = "loan_offer_delete:${offer.id}"
}

class LoansPage(app: ClaimsApp) : ClaimsPage(app) {
    override val title get() = texts().nav

    private var scrollHeight = 0
    private val built = Memo()
    private val textMemo = Memo()
    private val calcMemo = Memo()
    private val reasonMemo = Memo()
    private var takeLock: Lock? = null
    private var lendLock: String? = null
    private var offers = emptyList<OfferCard>()
    private var active = emptyList<ActiveCard>()
    private var lent = emptyList<ActiveCard>()
    private var market = emptyList<MarketCard>()
    private var own = emptyList<OwnCard>()
    private var cols = 1
    private var slotsLabel = ""
    private var slotNext: Lock? = null
    private var defaultText = ""
    private var defaultH = 0
    private var blockText = ""
    private var blockH = 0
    private var explainText = ""
    private var emptyText = ""
    private var lentEmpty = ""
    private var ownEmpty = ""
    private var marketEmpty = ""
    private var rights: Lock? = null
    private var tab = 0
    private val amount = NumberState(1000)
    private val term = NumberState(7)
    private val interest = NumberState(10)
    private val minRep = NumberState(0)
    private val maxRep = NumberState(0)
    private var tariff = false
    private var relist = false
    private var minOn = false
    private var maxOn = false

    private fun texts() = textMemo.of(Format.locale) { Texts() }

    override fun draw(ui: Ui, r: Rect) {
        val loans = ClientResearch.loans
        val t = texts()
        val lock = ClientLocks.loansTeaser()
        if (lock != null) {
            ui.lockedPanel(r.top(TEASER_H.coerceAtMost(r.h)), t.nav, t.teaser, lock, Icons.LOCK, "loans-locked")
            return
        }
        ui.subTabs(r.top(CONTROL_H), listOf(
            TabItem(t.tabBorrow, Icons.COIN, loans.offers.size + loans.market.size),
            TabItem(t.tabLend, Icons.HANDSHAKE, loans.own.size),
            TabItem(t.tabActive, Icons.CHECK, loans.active.size + loans.lent.size, if (loans.active.any { it.overdue > 0 }) Severity.WARNING else Severity.NEUTRAL)
        ), tab, "loans-tabs")?.let { tab = it }
        ui.scroll("loans-page-$tab", r.dropTop(CONTROL_H + 6), scrollHeight) { area ->
            rebuild(loans, area.w)
            val stack = Stack(area.x, area.y, area.w, CARD_GAP)
            ui.capacityRow(stack, slotsLabel, loans.used, loans.slots, slotNext, "loans-slots")
            if (loans.inDefault) notice(ui, stack, defaultText, defaultH, Palette.danger)
            if (loans.blockDays > 0) notice(ui, stack, blockText, blockH, Palette.warning)
            when (tab) {
                0 -> borrow(ui, stack)
                1 -> lend(ui, stack, loans)
                else -> activeTab(ui, stack)
            }
            scrollHeight = stack.bottom - area.y
        }
    }

    private fun notice(ui: Ui, stack: Stack, text: String, h: Int, color: Int) {
        val box = stack.take(h + 2 * PAD)
        Draw.box(ui.g, box, Palette.alpha(color, 0x30), Palette.alpha(color, 0x90))
        val lead = Draw.leadIcon(ui.g, Icons.WARNING, box.x + PAD, box.y + PAD + 4, color)
        Draw.paragraph(ui.g, text, box.x + PAD + lead + 2, box.y + PAD, box.w - 2 * PAD - lead - 2, color)
    }

    private fun borrow(ui: Ui, stack: Stack) {
        ui.section(stack, texts().marketTitle, market.size.toString())
        if (market.isEmpty()) Draw.text(ui.g, marketEmpty, stack.x, stack.take(Draw.LINE).y, Palette.textMuted)
        market.forEachIndexed { i, card -> drawMarket(ui, stack.take(card.h), card, i) }
        val head = stack.take(14)
        ui.section(head, texts().offersTitle, offers.size.toString())
        ui.tooltip("loans-explain", head, explainText)
        stack.cardGrid(offers.size, cols, { offers[it].h }) { rect, i -> drawOffer(ui, rect, offers[i], i) }
    }

    private fun activeTab(ui: Ui, stack: Stack) {
        ui.section(stack, texts().activeTitle, active.size.toString())
        if (active.isEmpty()) Draw.text(ui.g, emptyText, stack.x, stack.take(Draw.LINE).y, Palette.textMuted)
        active.forEachIndexed { i, card -> drawActive(ui, stack.take(card.h), card, i) }
        ui.section(stack, texts().lentTitle, lent.size.toString())
        if (lent.isEmpty()) Draw.text(ui.g, lentEmpty, stack.x, stack.take(Draw.LINE).y, Palette.textMuted)
        lent.forEachIndexed { i, card -> drawActive(ui, stack.take(card.h), card, i) }
    }

    private fun lend(ui: Ui, stack: Stack, loans: LoansView) {
        form(ui, stack, loans)
        ui.section(stack, texts().lendTitle, own.size.toString())
        if (own.isEmpty()) Draw.text(ui.g, ownEmpty, stack.x, stack.take(Draw.LINE).y, Palette.textMuted)
        own.forEachIndexed { i, card -> drawOwn(ui, stack.take(card.h), card, i) }
    }

    private fun labelled(ui: Ui, cell: Rect, label: String, trailing: String? = null): Rect {
        ui.fieldLabel(cell.top(9), label, trailing)
        return Rect(cell.x, cell.y + 10, cell.w, CONTROL_H)
    }

    private fun form(ui: Ui, stack: Stack, loans: LoansView) {
        val t = texts()
        val state = ClientResearch.state
        val edit = state.canManage
        ui.section(stack, t.formTitle)
        val first = stack.take(FIELD_H).columns(2, 8)
        ui.numberField(labelled(ui, first[0], t.amount), amount, LoanTerms.MIN_AMOUNT, state.treasury.coerceAtLeast(LoanTerms.MIN_AMOUNT), 100, Format.COIN, edit, "loan-form:amount")
        ui.numberField(labelled(ui, first[1], t.term), term, 1, LoanTerms.MAX_TERM.toLong(), 1, t.days, edit, "loan-form:term")
        val second = stack.take(FIELD_H).columns(2, 8)
        ui.numberField(labelled(ui, second[0], t.interest), interest, 0, LoanTerms.MAX_INTEREST.toLong(), 1, "%", edit, "loan-form:interest")
        ui.fieldLabel(second[1].top(9), t.tariffHead)
        ui.toggle(Rect(second[1].x, second[1].y + 10, second[1].w, CONTROL_H), tariff, t.tariffToggle, edit, tip = t.tariffTip, key = "loan-form:tariff")?.let { tariff = it }
        val third = stack.take(FIELD_H).columns(2, 8)
        repField(ui, third[0], t.min, minRep, minOn, edit, "loan-form:min", loans.repMax)?.let { minOn = it }
        repField(ui, third[1], t.max, maxRep, maxOn, edit, "loan-form:max", loans.repMax)?.let { maxOn = it }
        val calc = calcMemo.of(amount.value, term.value, interest.value, tariff, Format.locale) {
            val total = LoanTerms.total(amount.value, interest.value.toInt())
            val main = tr("kami_claims.loans.form.calc", Format.money(total), Format.money(LoanTerms.perDay(total, term.value.toInt())))
            val worst = if (tariff) {
                val effective = (interest.value * (100 + Diplomacy.MAX_TARIFF) / 100).toInt()
                val worstTotal = LoanTerms.total(amount.value, effective)
                tr("kami_claims.loans.form.calc.tariff", Diplomacy.MAX_TARIFF, effective, Format.money(worstTotal), Format.money(LoanTerms.perDay(worstTotal, term.value.toInt())))
            } else null
            main to worst
        }
        Draw.text(ui.g, Draw.fit(calc.first, stack.w), stack.x, stack.take(ROW_H).y, Palette.textSecondary)
        calc.second?.let { Draw.text(ui.g, Draw.fit(it, stack.w), stack.x, stack.take(ROW_H).y, Palette.warning) }
        Draw.text(ui.g, Draw.fit(t.reserve, stack.w), stack.x, stack.take(ROW_H).y, Palette.textMuted)
        val last = stack.take(CONTROL_H).columns(2, 8)
        ui.toggle(last[0], relist, t.relist, edit, tip = t.relistTip, key = "loan-form:relist")?.let { relist = it }
        val reason = createReason(loans, state.treasury)
        val busy = pending("loan_offer_create")
        if (ui.lockedButton(last[1], t.create, rights, Icons.COIN, ButtonStyle.PRIMARY, reason == null && !busy, reason, pending = busy, key = "loan-form:create")) {
            act(
                "loan_offer_create", amount.value.toString(), term.value.toString(), interest.value.toString(), if (tariff) "on" else "off",
                if (minOn) minRep.value.toString() else "-1", if (maxOn) maxRep.value.toString() else "-1", if (relist) "on" else "off"
            )
        }
    }

    private fun repField(ui: Ui, cell: Rect, label: String, field: NumberState, on: Boolean, edit: Boolean, key: String, repMax: Int): Boolean? {
        val t = texts()
        val control = Row(labelled(ui, cell, label, if (on) null else t.none), 4)
        val result = ui.toggle(control.take(58), on, t.limit, edit, key = "$key:on")
        ui.numberField(control.rest, field, 0, repMax.toLong(), 10, enabled = edit && on, key = key)
        return result
    }

    private fun createReason(loans: LoansView, treasury: Long): String? =
        reasonMemo.of(loans, treasury, amount.value, minOn, maxOn, minRep.value, maxRep.value, Format.locale) {
            when {
                lendLock != null -> lendLock
                loans.own.size >= MAX_LINKS -> tr("kami_claims.loans.error.offer_limit")
                treasury < amount.value -> tr("kami_claims.loans.reason.funds", Format.money(amount.value))
                minOn && maxOn && minRep.value > maxRep.value -> texts().range
                else -> null
            }
        }

    private fun repLine(min: Int, max: Int): Pair<String, Int>? = when {
        min >= 0 && max >= 0 -> tr("kami_claims.loans.rep.range", min, max)
        min >= 0 -> tr("kami_claims.loans.rep.min", min)
        max >= 0 -> tr("kami_claims.loans.rep.max", max)
        else -> null
    }?.let { it to Palette.textSecondary }

    private fun rebuild(loans: LoansView, width: Int) = built.of(loans, ClientResearch.defs, ClientResearch.state, width, Format.locale) {
        val state = ClientResearch.state
        val slots = loans.slots
        val t = texts()
        val cap = state.max(Capacity.TREASURY)
        cols = cardColumns(width, MIN_CARD_W, 2)
        val nodes = ClientResearch.defs.trees.flatMap { it.nodes }
        val rights = if (state.canManage) null else Lock(tr("kami_claims.loans.reason.rights"))
        this.rights = rights
        takeLock = loans.takeLock.takeIf { it.isNotEmpty() }?.let { Lock(tr(it)) }
        lendLock = loans.lendLock.takeIf { it.isNotEmpty() }?.let { tr(it) }
        slotsLabel = tr("kami_claims.loans.slots")
        slotNext = nodes.asSequence().filter { n -> n.key !in state.done && n.unlocks.any { it.kind == "loan_slots" } }.minByOrNull { it.level }
            ?.let { n -> Lock.raise(n.level, slots + n.unlocks.filter { it.kind == "loan_slots" }.sumOf { it.count }) }
        defaultText = tr("kami_claims.loans.default")
        defaultH = Draw.paragraphHeight(defaultText, width - 2 * PAD - Draw.ICON - 2)
        blockText = tr("kami_claims.loans.block", trn("kami_claims.unit.day", loans.blockDays.toLong()))
        blockH = Draw.paragraphHeight(blockText, width - 2 * PAD - Draw.ICON - 2)
        explainText = tr("kami_claims.loans.explain")
        emptyText = tr("kami_claims.loans.active.empty")
        lentEmpty = tr("kami_claims.loans.lent.empty")
        ownEmpty = tr("kami_claims.loans.lend.empty")
        marketEmpty = tr("kami_claims.loans.market.empty")
        active = loans.active.map { activeCard(it, state.treasury, rights, false) }
        lent = loans.lent.map { activeCard(it, state.treasury, rights, true) }
        market = loans.market.map { offer ->
            val days = trn("kami_claims.unit.day", offer.termDays.toLong())
            val extra = offer.total - offer.amount
            val lines = ArrayList<Pair<String, Int>>(3)
            lines += tr("kami_claims.loans.market.pay", Format.money(offer.total), Format.money(offer.perDay), days) to Palette.text
            lines += if (offer.effectivePct != offer.interestPct) tr("kami_claims.loans.market.interest_tariff", offer.interestPct, offer.effectivePct) to Palette.warning
            else tr("kami_claims.loans.market.interest", offer.interestPct) to Palette.textSecondary
            repLine(offer.minRep, offer.maxRep)?.let { lines += it }
            MarketCard(
                offer, tr("kami_claims.loans.market.card", offer.lender, Format.money(offer.amount)), lines, rights, takeLock,
                listOf(
                    Consequence(tr("kami_claims.loans.market.lender", offer.lender)),
                    Consequence(tr("kami_claims.loans.received", Format.money(offer.amount))),
                    Consequence(tr("kami_claims.loans.payback", Format.money(offer.total))),
                    Consequence(tr("kami_claims.loans.extra", Format.money(extra), offer.effectivePct), Severity.WARNING),
                    Consequence(tr("kami_claims.loans.installment", Format.money(offer.perDay), days))
                )
            )
        }
        own = loans.own.map { offer ->
            val lentOut = offer.state == LendState.LENT.ordinal
            val on = offer.state == LendState.ACTIVE.ordinal
            val name = LendState.entries[offer.state].name.lowercase()
            val days = trn("kami_claims.unit.day", offer.termDays.toLong())
            val total = LoanTerms.total(offer.amount, offer.interestPct)
            val lines = ArrayList<Pair<String, Int>>(5)
            lines += tr("kami_claims.loans.market.pay", Format.money(total), Format.money(LoanTerms.perDay(total, offer.termDays)), days) to Palette.text
            lines += tr("kami_claims.loans.market.interest", offer.interestPct) to Palette.textSecondary
            lines += tr("kami_claims.loans.own.flags", yesNo(offer.tariff), yesNo(offer.relist)) to Palette.textMuted
            repLine(offer.minRep, offer.maxRep)?.let { lines += it }
            if (lentOut) lines += tr("kami_claims.loans.own.borrower", offer.borrower) to Palette.brass
            val ownTitle = tr("kami_claims.loans.own.card", Format.money(offer.amount))
            val remaining = loans.lent.firstOrNull { it.id == offer.id }?.let { it.total - it.paid } ?: 0L
            OwnCard(
                offer, ownTitle, tr("kami_claims.loans.state.$name"),
                when { on -> Severity.SUCCESS.color; lentOut -> Palette.brass; else -> Palette.textMuted },
                lines, if (on) t.disable else t.enable,
                when {
                    lentOut -> tr("kami_claims.loans.reason.lent")
                    !on && lendLock != null -> lendLock
                    !on && state.treasury < offer.amount -> tr("kami_claims.loans.reason.funds", Format.money(offer.amount))
                    else -> null
                },
                if (lentOut) t.forgive else t.delete,
                tr(if (lentOut) "kami_claims.loans.confirm.forgive.title" else "kami_claims.loans.confirm.delete.title", ownTitle),
                tr(if (lentOut) "kami_claims.loans.confirm.forgive.subtitle" else "kami_claims.loans.confirm.delete.subtitle"),
                rights,
                buildList {
                    if (lentOut) {
                        add(Consequence(tr("kami_claims.loans.confirm.forgive.lost", Format.money(remaining)), Severity.DANGER))
                    } else {
                        add(Consequence(tr("kami_claims.loans.confirm.delete.offer", Format.money(offer.amount))))
                        if (on) add(Consequence(tr("kami_claims.loans.confirm.delete.refund", Format.money(offer.amount))))
                    }
                }
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
            val take = rights ?: takeLock ?: when {
                cooldown > 0 -> Lock(tr("kami_claims.loans.reason.cooldown", cooldown))
                cap > 0 && state.treasury + amount > cap -> Lock(tr("kami_claims.loans.reason.cap", Format.money(cap.toLong())))
                else -> null
            }
            OfferCard(
                offer, tr("kami_claims.research.unlock.loan", Format.money(amount)), lines.toTypedArray(), colors.toIntArray(), 2 * PAD + TITLE_H + ROW_H + 2, lock, take, node?.key,
                listOf(
                    Consequence(tr("kami_claims.loans.received", Format.money(amount))),
                    Consequence(tr("kami_claims.loans.payback", Format.money(total))),
                    Consequence(tr("kami_claims.loans.extra", Format.money(extra), offer.interestPct), Severity.WARNING),
                    Consequence(tr("kami_claims.loans.installment", Format.money(perDay), days))
                )
            )
        }
    }

    private fun yesNo(on: Boolean) = tr(if (on) "kami_claims.loans.on" else "kami_claims.loans.off")

    private fun activeCard(loan: ActiveLoanView, treasury: Long, rights: Lock?, readOnly: Boolean): ActiveCard {
        val remaining = loan.total - loan.paid
        val notes = ArrayList<Pair<String, Int>>(2)
        if (loan.overdue > 0) notes += tr("kami_claims.loans.overdue", Format.money(loan.overdue)) to Palette.warning
        if (loan.missed > 0) notes += tr("kami_claims.loans.missed", loan.missed) to Palette.warning
        val money = Format.money(loan.principal)
        val title = when {
            loan.party.isEmpty() -> tr("kami_claims.research.unlock.loan", money)
            readOnly -> tr("kami_claims.loans.card.to", loan.party, money)
            else -> tr("kami_claims.loans.card.from", loan.party, money)
        }
        val repay = if (readOnly) null else rights ?: if (treasury < remaining) Lock(tr("kami_claims.loans.reason.funds", Format.money(remaining))) else null
        val confirm = if (readOnly) emptyList() else listOf(
            Consequence(tr("kami_claims.loans.confirm.repay.pay", Format.money(remaining))),
            Consequence(tr("kami_claims.loans.confirm.repay.after", Format.money(treasury - remaining)), if (treasury - remaining < 0) Severity.DANGER else Severity.NEUTRAL)
        )
        return ActiveCard(
            loan, title, tr("kami_libs.common.paid"), tr("kami_libs.common.left", trn("kami_claims.unit.day", loan.daysLeft.toLong())), notes,
            2 * PAD + TITLE_H + PROGRESS_LABELLED_H + notes.size * (ROW_H + 2), repay, confirm, readOnly
        )
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
            if (ui.lockedButton(button, texts().take, card.take, Icons.COIN, ButtonStyle.PRIMARY, !busy, pending = busy, key = card.buttonKey)) take(card)
        }
        Draw.text(ui.g, Draw.fit(card.lines[1], box.w - 2 * PAD), box.x + PAD, box.y + PAD + TITLE_H, if (dim) Palette.textMuted else Palette.textSecondary)
        ui.tooltip(card.tipKey, Rect(box.x, box.y, box.w - if (dim) 0 else BUTTON_W + 2 * PAD, box.h), card.tip)
    }

    private fun lines(ui: Ui, box: Rect, lines: List<Pair<String, Int>>) = lines.forEachIndexed { i, (text, color) ->
        Draw.text(ui.g, Draw.fit(text, box.w - 2 * PAD), box.x + PAD, box.y + PAD + TITLE_H + i * ROW_H, color)
    }

    private fun drawMarket(ui: Ui, r: Rect, card: MarketCard, index: Int) = ui.staggered(r, card.revealKey, index) { box ->
        Draw.box(ui.g, box, Palette.raised, Palette.border)
        Draw.text(ui.g, Draw.fit(card.title, box.w - 3 * PAD - BUTTON_W), box.x + PAD, box.y + PAD + 3, Palette.text)
        val busy = pending("loan_borrow")
        val button = Rect(box.right - PAD - BUTTON_W, box.y + PAD, BUTTON_W, SMALL_H)
        if (ui.lockedButton(button, texts().take, card.rights, Icons.COIN, ButtonStyle.PRIMARY, card.reason == null && !busy, card.reason, pending = busy, key = card.buttonKey)) borrow(card)
        lines(ui, box, card.lines)
    }

    private fun drawOwn(ui: Ui, r: Rect, card: OwnCard, index: Int) = ui.staggered(r, card.revealKey, index) { box ->
        Draw.box(ui.g, box, Palette.raised, Palette.border)
        val row = Row(Rect(box.x + PAD, box.y + PAD, box.w - 2 * PAD, SMALL_H), 4)
        val del = row.takeFromRight(BUTTON_W)
        val toggle = row.takeFromRight(BUTTON_W)
        val on = card.offer.state == LendState.ACTIVE.ordinal
        val busyToggle = pending(card.toggleKey)
        if (ui.lockedButton(toggle, card.toggleLabel, card.rights, if (on) Icons.BAN else Icons.CHECK, ButtonStyle.SECONDARY, card.toggleReason == null && !busyToggle, card.toggleReason, pending = busyToggle, key = card.toggleKey)) {
            act("loan_offer_toggle", card.offer.id, if (on) "off" else "on", key = card.toggleKey)
        }
        val busyDelete = pending(card.deleteKey)
        if (ui.lockedButton(del, card.deleteLabel, card.rights, Icons.REMOVE, ButtonStyle.DANGER, !busyDelete, pending = busyDelete, key = card.deleteKey)) delete(card)
        val stateW = Draw.width(card.stateLabel) + 6
        Draw.text(ui.g, card.stateLabel, row.rest.right - stateW + 6, box.y + PAD + 3, card.stateColor)
        Draw.text(ui.g, Draw.fit(card.title, row.rest.w - stateW), row.rest.x, box.y + PAD + 3, Palette.text)
        lines(ui, box, card.lines)
    }

    private fun drawActive(ui: Ui, r: Rect, card: ActiveCard, index: Int) = ui.staggered(r, card.revealKey, index) { box ->
        val late = card.notes.isNotEmpty()
        Draw.box(ui.g, box, if (late) Palette.alpha(Palette.warning, 0x20) else Palette.raised, if (late) Palette.warning else Palette.border)
        Draw.text(ui.g, Draw.fit(card.title, box.w - 3 * PAD - if (card.readOnly) 0 else BUTTON_W), box.x + PAD, box.y + PAD + 3, Palette.text)
        if (!card.readOnly) {
            val button = Rect(box.right - PAD - BUTTON_W, box.y + PAD, BUTTON_W, SMALL_H)
            val busy = pending("loan_repay")
            if (ui.lockedButton(button, texts().repay, card.repay, Icons.CHECK, ButtonStyle.SECONDARY, !busy, pending = busy, key = card.buttonKey)) repay(card)
        }
        val bar = Rect(box.x + PAD, box.y + PAD + TITLE_H, box.w - 2 * PAD, PROGRESS_LABELLED_H)
        ui.progressBar(bar, card.loan.paid, card.loan.total, card.paidLabel, card.leftLabel, if (late) Palette.warning else Palette.brass, key = card.barKey)
        card.notes.forEachIndexed { i, (text, color) ->
            val y = bar.bottom + 2 + i * (ROW_H + 2)
            val lead = Draw.leadIcon(ui.g, Icons.WARNING, bar.x, y + 4, color)
            Draw.text(ui.g, Draw.fit(text, bar.w - lead - 2), bar.x + lead + 2, y, color)
        }
    }

    private fun take(card: OfferCard) = Dialogs.confirm(
        app, tr("kami_claims.loans.confirm.take.title", card.title), tr("kami_claims.loans.confirm.take.subtitle"), Icons.COIN,
        card.confirm, texts().take, "loan_take", arrayOf(card.offer.id)
    )

    private fun borrow(card: MarketCard) = Dialogs.confirm(
        app, tr("kami_claims.loans.confirm.borrow.title", card.offer.lender), tr("kami_claims.loans.confirm.borrow.subtitle"), Icons.COIN,
        card.confirm, texts().take, "loan_borrow", arrayOf(card.offer.id, card.offer.total.toString())
    )

    private fun delete(card: OwnCard) = Dialogs.confirm(
        app, card.deleteTitle, card.deleteSubtitle, Icons.REMOVE,
        card.confirm, card.deleteLabel, "loan_offer_delete", arrayOf(card.offer.id), danger = true, key = card.deleteKey
    )

    private fun repay(card: ActiveCard) = Dialogs.confirm(
        app, tr("kami_claims.loans.confirm.repay.title", card.title), tr("kami_claims.loans.confirm.repay.subtitle"), Icons.COIN,
        card.confirm, texts().repay, "loan_repay", arrayOf(card.loan.id)
    )
}
