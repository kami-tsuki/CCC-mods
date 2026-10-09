package kami.claims.client.app.personal

import kami.claims.client.app.ClientLocks
import kami.claims.client.app.Dialogs
import kami.claims.client.store.ClaimsStore
import kami.claims.client.store.ClientResearch
import kami.claims.net.HomeLine
import kami.claims.net.Info
import kami.libs.ui.app.Consequence
import kami.libs.ui.app.KamiApp
import kami.libs.ui.app.NavBadge
import kami.libs.ui.app.Page
import kami.libs.ui.app.Personal
import kami.libs.ui.core.Rect
import kami.libs.ui.core.Row
import kami.libs.ui.core.Stack
import kami.libs.ui.core.Ui
import kami.libs.ui.style.Draw
import kami.libs.ui.style.Format
import kami.libs.ui.style.Icons
import kami.libs.ui.style.Palette
import kami.libs.ui.style.Severity
import kami.libs.ui.text.tr
import kami.libs.ui.text.trn
import kami.libs.ui.widget.*

private fun homeKey(h: HomeLine) = "${h.dim}|${h.x}|${h.z}"

private fun severity(h: HomeLine, funds: Long) = when {
    h.debt > 0 -> Severity.DANGER
    h.credit < h.rent && funds < h.rent - h.credit -> Severity.WARNING
    else -> Severity.SUCCESS
}

private fun color(h: HomeLine, funds: Long): Int {
    val sev = severity(h, funds)
    return if (sev == Severity.SUCCESS && h.credit < 3L * h.rent) Palette.paid else sev.color
}

class DebtsPage(private val host: KamiApp) : Page() {
    override val title get() = tr("kami_claims.personal.debts")

    private val table = TableState<HomeLine>()
    private var scrollHeight = 0

    override fun draw(ui: Ui, r: Rect) {
        val view = ClaimsPersonal.view() ?: return skeleton(ui, r)
        ui.scroll("debts-page", r, scrollHeight) { area ->
            val stack = Stack(area.x, area.y, area.w, 6)
            rent(ui, stack, view.homes, view.funds)
            val info = ClaimsStore.info
            if (info != null && !Personal.viewing && ClientLocks.cap("withdraw") == null) country(ui, stack, info)
            scrollHeight = stack.bottom - area.y
        }
    }

    private fun skeleton(ui: Ui, r: Rect) {
        val stack = Stack(r.x, r.y, r.w, 6)
        ui.section(stack, tr("kami_claims.personal.debts.rent"))
        ui.skeleton(stack.take(CONTROL_H).withWidth(r.w / 2))
        ui.skeletonTable(stack.take(TABLE_HEADER_H + SKELETON_ROWS * TABLE_ROW_H), 4, SKELETON_ROWS)
    }

    private fun rent(ui: Ui, stack: Stack, homes: List<HomeLine>, funds: Long) {
        ui.section(stack, tr("kami_claims.personal.debts.rent"), homes.size.toString())
        val bar = Row(stack.take(CONTROL_H), 4)
        val picked = homes.firstOrNull { homeKey(it) in table.selected }
        val blocked = ClaimsPersonal.readOnly() ?: if (funds <= 0) tr("kami_claims.money.disabled.no_coins") else null
        if (ui.edgeButton(bar, tr("kami_claims.personal.debts.pay_all"), Icons.COIN, ButtonStyle.PRIMARY, homes.any { it.debt > 0 } && blocked == null, blocked ?: tr("kami_claims.personal.debts.none"), pending = ClaimsStore.isPending("rent_pay_all"), key = "debts-pay-all")) ClaimsStore.send("rent_pay_all")
        if (ui.edgeButton(bar, tr("kami_claims.personal.debts.pay_amount"), Icons.COIN, enabled = picked != null && blocked == null, disabledReason = blocked ?: tr("kami_claims.plots.select_first"), pending = picked?.let { ClaimsStore.isPending(Dialogs.rentPayKey(it)) } == true, key = "debts-pay")) picked?.let { Dialogs.rentPay(host, it) }
        val debt = homes.sumOf { it.debt }
        val credit = homes.sumOf { it.credit }
        ui.fitText(bar.rest.x, bar.rest.y + 5, bar.rest.w, tr("kami_claims.personal.debts.summary", Format.money(debt), Format.money(credit)), if (debt > 0) Palette.danger else Palette.textSecondary)
        if (homes.isEmpty()) {
            Draw.text(ui.g, tr("kami_claims.home.empty"), stack.x, stack.take(Draw.LINE).y, Palette.textMuted)
            return
        }
        ui.table(stack.take(TABLE_HEADER_H + minOf(homes.size, 8) * TABLE_ROW_H + 2), listOf(
            Column.text<HomeLine>(tr("kami_claims.help.term.plot"), -1, color = { color(it, funds) }) { tr("kami_claims.home.where", it.country, it.x, it.z) },
            Column.number<HomeLine>(tr("kami_claims.plots.col.rent"), 70, format = { Format.perDay(Format.money(it)) }) { it.rent.toLong() },
            Column<HomeLine>(tr("kami_libs.common.balance"), 80, Align.RIGHT, compareBy { it.credit - it.debt }) { _, c, h ->
                val text = if (h.debt > 0) "-" + Format.money(h.debt) else Format.money(h.credit)
                Draw.textRight(g, text, c.right, c.y + (c.h - 8) / 2, color(h, funds))
            },
            Column.text<HomeLine>(tr("kami_claims.personal.debts.next_bill"), 92, Align.RIGHT, sortable = false, color = { Palette.textSecondary }) { if (it.nextBill > 0) Format.until(ClaimsStore.receivedAt + it.nextBill) else "" }
        ), homes, table, ::homeKey, severity = { severity(it, funds) }, key = "debts-table")
    }

    private fun country(ui: Ui, stack: Stack, info: Info) {
        val loans = ClientResearch.loans.active
        ui.section(stack, tr("kami_claims.nav.loans"), loans.size.toString())
        if (loans.isEmpty()) Draw.text(ui.g, tr("kami_claims.loans.active.empty"), stack.x, stack.take(Draw.LINE).y, Palette.textMuted)
        loans.forEach { loan ->
            val row = Row(stack.take(CONTROL_H), 4)
            val remaining = loan.total - loan.paid
            val title = if (loan.party.isEmpty()) tr("kami_claims.research.unlock.loan", Format.money(loan.principal)) else tr("kami_claims.loans.card.from", loan.party, Format.money(loan.principal))
            val reason = if (info.treasury < remaining) tr("kami_claims.loans.reason.funds", Format.money(remaining)) else null
            val label = tr("kami_claims.loans.repay")
            if (ui.edgeButton(row, label, Icons.CHECK, enabled = reason == null, disabledReason = reason, pending = ClaimsStore.isPending("loan_repay:${loan.id}"), key = "debts-loan:${loan.id}")) {
                Dialogs.confirm(host, tr("kami_claims.loans.confirm.repay.title", title), tr("kami_claims.loans.confirm.repay.subtitle"), Icons.COIN, listOf(
                    Consequence(tr("kami_claims.loans.confirm.repay.pay", Format.money(remaining))),
                    Consequence(tr("kami_claims.loans.confirm.repay.after", Format.money(info.treasury - remaining)))
                ), label, "loan_repay", arrayOf(loan.id), key = "loan_repay:${loan.id}")
            }
            val text = tr("kami_claims.personal.debts.loan", title, Format.money(remaining), trn("kami_claims.unit.day", loan.daysLeft.toLong()))
            ui.fitText(row.rest.x, row.rest.y + 5, row.rest.w, text, if (loan.overdue > 0) Palette.warning else Palette.text)
        }
        ui.section(stack, tr("kami_claims.warpage.debts"), info.warDebts.size.toString())
        if (info.warDebts.isEmpty()) Draw.text(ui.g, tr("kami_claims.warpage.debts.none"), stack.x, stack.take(Draw.LINE).y, Palette.textMuted)
        val chief = when {
            ClaimsStore.delegated -> tr("kami_claims.warpage.own_only")
            info.rank.equals("president", true) -> null
            else -> tr("kami_claims.warpage.president_only")
        }
        info.warDebts.forEach { (to, amount) ->
            val row = Row(stack.take(CONTROL_H), 4)
            val pay = minOf(amount, info.treasury)
            val label = tr("kami_claims.warpage.repay")
            val reason = chief ?: if (pay <= 0) tr("kami_claims.warpage.repay.empty") else null
            if (ui.edgeButton(row, label, Icons.COIN, enabled = reason == null, disabledReason = reason, pending = ClaimsStore.isPending("war_repay:$to"), key = "debts-war:$to")) {
                Dialogs.confirm(host, tr("kami_claims.warpage.repay.title", to), null, Icons.COIN, listOf(Consequence(tr("kami_claims.warpage.repay.amount", Format.money(pay), Format.money(amount)))), label, "war_repay", arrayOf(to, pay.toString()), key = "war_repay:$to")
            }
            ui.fitText(row.rest.x, row.rest.y + 5, row.rest.w, "$to · ${Format.money(amount)}", Palette.text)
        }
    }

    companion object {
        private const val SKELETON_ROWS = 3

        fun badge(): NavBadge? {
            val view = ClaimsPersonal.view() ?: return null
            val levels = view.homes.map { severity(it, view.funds) }.filter { it != Severity.SUCCESS }
            if (levels.isEmpty()) return null
            return NavBadge(levels.size, if (Severity.DANGER in levels) Severity.DANGER else Severity.WARNING)
        }
    }
}
