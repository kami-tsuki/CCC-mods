package kami.claims.client.app.pages

import kami.claims.client.app.ClaimsPage
import kami.claims.client.app.ClientLocks
import kami.claims.gov.PRESIDENT
import kami.claims.gov.Perm
import kami.claims.net.Info
import kami.libs.ui.core.Flow
import kami.libs.ui.core.Rect
import kami.libs.ui.core.Row
import kami.libs.ui.core.Ui
import kami.libs.ui.style.Format
import kami.libs.ui.style.Icons
import kami.libs.ui.style.Palette
import kami.libs.ui.text.tr
import kami.libs.ui.widget.ButtonStyle
import kami.libs.ui.widget.CONTROL_H
import kami.libs.ui.widget.button
import kami.libs.ui.widget.buttonWidth
import kami.libs.ui.widget.card
import kami.libs.ui.widget.fitText

internal fun withdrawalsHeight(info: Info) = if (info.withdrawals.isEmpty()) 0 else CARD_HEAD_H + info.withdrawals.size * (CONTROL_H + 1)

private fun soleWithdrawer(info: Info) =
    info.rank == PRESIDENT && info.ranks.filter { it.id == PRESIDENT || Perm.WITHDRAW.name in it.perms }.sumOf { it.members } == 1

internal fun ClaimsPage.withdrawalsCard(ui: Ui, r: Rect, info: Info) {
    val flow = Flow(ui.card(r, tr("kami_claims.budget.pending"), Icons.WITHDRAW, help = tr("kami_claims.budget.pending.help")), 1)
    val myName = info.members.firstOrNull { it.id == snap.me }?.name.takeUnless { soleWithdrawer(info) }
    info.withdrawals.forEach { w ->
        val row = Row(flow.take(CONTROL_H), 4)
        val reason = lock(Perm.WITHDRAW) ?: ClientLocks.perm(Perm.VIEW_PEOPLE).takeIf { info.members.isEmpty() } ?: tr("kami_claims.budget.pending.own").takeIf { w.requester == myName }
        val approve = tr("kami_claims.budget.pending.approve")
        if (ui.button(row.takeFromRight(buttonWidth(approve, Icons.CHECK)), approve, Icons.CHECK, ButtonStyle.PRIMARY, reason == null, reason, pending = pending("withdraw_approve:${w.id}"), key = "wd-approve:${w.id}")) act("withdraw_approve", w.id, key = "withdraw_approve:${w.id}")
        ui.fitText(row.rest.x, row.rest.y + 3, row.rest.w, tr("kami_claims.budget.pending.line", w.requester, Format.money(w.amount), Format.ago(System.currentTimeMillis() - w.ageMinutes * 60_000L)), Palette.textSecondary)
    }
}
