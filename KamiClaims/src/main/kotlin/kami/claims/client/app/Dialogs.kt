package kami.claims.client.app

import kami.libs.ui.text.tr
import kami.claims.NameRules
import kami.claims.rentSplit
import kami.claims.client.store.ClaimsStore
import kami.claims.client.store.Outcome
import kami.claims.gov.WarnTier
import kami.claims.net.HomeLine
import kami.claims.net.Snap
import kami.libs.ui.app.KamiApp
import kami.libs.ui.app.Dialog
import kami.libs.ui.app.DialogKind
import kami.libs.ui.app.dialogButtons
import kami.libs.ui.app.Consequence
import kami.libs.ui.app.confirmDialog
import kami.libs.ui.app.consequences
import kami.claims.research.Tokens
import kami.libs.ui.widget.CONTROL_H
import kami.libs.ui.widget.TextState
import kami.libs.ui.widget.fieldHelp
import kami.libs.ui.widget.fieldLabel
import kami.libs.ui.widget.textField
import kami.libs.ui.app.formDialog
import kami.libs.ui.app.AmountPlan
import kami.libs.ui.app.amountDialog
import kami.libs.ui.core.Rect
import kami.libs.ui.style.Format
import kami.libs.ui.style.Icon
import kami.libs.ui.style.Icons
import kami.libs.ui.style.Palette
import kami.libs.ui.style.Severity
import kami.libs.ui.widget.playerField
import kami.libs.ui.widget.property
import kami.libs.ui.widget.ButtonStyle
import kami.libs.ui.widget.DIFF_ROW_H
import kami.libs.ui.widget.DiffLine
import kami.libs.ui.widget.checkbox
import kami.libs.ui.widget.diffList
import kami.libs.ui.widget.diffListHeight

object Dialogs {
    fun gift(app: ClaimsApp, id: String, name: String) {
        app.open(amountDialog(tr("kami_claims.gift.title", name), tr("kami_claims.gift.subtitle"), Icons.WITHDRAW, 1,
            header = { r ->
                property(Rect(r.x, r.y, r.w, 11), tr("kami_claims.kpi.funds"), Format.money(ClaimsStore.snap?.funds ?: 0L), Palette.money)
                16
            },
            plan = { amount ->
                val max = ClaimsStore.snap?.funds ?: 0L
                AmountPlan(max, tr("kami_claims.gift.help"), tr("kami_claims.gift.send", Format.money(amount)), moneyReason(max, tr("kami_claims.money.disabled.no_coins")))
            },
            send = { ClaimsStore.send("gift_send", id, it.toString(), key = "gift:$id") }
        ))
    }

    private fun moneyReason(max: Long, empty: String) = if (max <= 0) empty else tr("kami_claims.money.disabled.range", Format.number(max))

    fun confirm(
        app: KamiApp, title: String, subtitle: String?, icon: Icon, items: List<Consequence>, primary: String, name: String, args: Array<String>,
        danger: Boolean = false, hold: Boolean = false, typed: String? = null, stale: () -> String? = { null }, key: String = name, after: () -> Unit = {}
    ) = confirmDialog(app::open, title, subtitle, icon, items, primary, danger, hold, typed, stale = stale) {
        ClaimsStore.send(name, *args, key = key)
        after()
    }

    fun release(app: KamiApp, x: Int, z: Int, subtitle: String?, moving: Boolean) = confirm(app, tr("kami_claims.plots.release.confirm.title", x, z), subtitle, Icons.HOUSE, listOfNotNull(
        Consequence(tr("kami_claims.plots.release.access"), Severity.WARNING),
        Consequence(tr("kami_claims.plots.release.open"), Severity.WARNING),
        if (!moving) Consequence(tr("kami_claims.home.release.rent")) else null,
        if (moving) Consequence(tr("kami_claims.home.release.moving"), Severity.SUCCESS) else null
    ), tr("kami_claims.plots.release.action"), "plot_release", arrayOf(x.toString(), z.toString()), danger = true, hold = true)

    fun rent(app: ClaimsApp, x: Int, z: Int, price: Int, subtitle: String) {
        val cost = Format.perDay(Format.money(price.toLong()))
        val info = ClaimsStore.info
        confirm(app, tr("kami_claims.plots.rent.confirm.title", x, z), subtitle, Icons.HOUSE, listOf(
            Consequence(tr("kami_claims.plots.rent.terms", cost, Format.money(info?.rentDebtLimit ?: 0), Format.days((info?.moveOutDays ?: 0).toLong())))
        ), tr("kami_claims.plots.rent.action", cost), "plot_claim", arrayOf(x.toString(), z.toString()))
    }

    fun removeTenant(app: ClaimsApp, x: Int, z: Int, owner: String) = confirm(app, tr("kami_claims.plots.untrust.title", owner), tr("kami_claims.plot.at", x, z), Icons.BAN, listOf(
        Consequence(tr("kami_claims.confirm.plot_remove.body", Format.days((ClaimsStore.info?.moveOutDays ?: 0).toLong())), Severity.DANGER),
        Consequence(tr("kami_claims.plots.remove.builds"), Severity.WARNING)
    ), tr("kami_libs.common.remove"), "plot_remove", arrayOf(x.toString(), z.toString(), "confirm"), danger = true, hold = true)

    fun nameError(text: String, snap: Snap): String? = when {
        text.length < (snap.limits?.nameMin ?: 3) -> tr("kami_claims.wizard.name.error.short", snap.limits?.nameMin ?: 3)
        !NameRules.valid(text) -> tr("kami_claims.wizard.name.error.chars")
        ClaimsStore.countries.any { NameRules.key(it.name) == NameRules.key(text) && !it.name.equals(snap.info?.name, true) } -> tr("kami_claims.error.name_taken")
        else -> null
    }

    fun invite(app: ClaimsApp, name: TextState) = formDialog({ app.open(it) }, tr("kami_libs.common.invite"), null, Icons.INVITE, tr("kami_libs.common.invite"),
        { name.text.length >= 3 }, { tr("kami_claims.field.player.disabled") }, { ClaimsStore.send("invite", name.text, key = "invite"); name.set("") }
    ) { r ->
        playerField(Rect(r.x, r.y, r.w, CONTROL_H), name, offline = { ClaimsStore.snap?.names.orEmpty() }, query = { ClaimsStore.quiet("names", it) }, key = "invite-dialog")
        CONTROL_H
    }

    fun rename(app: ClaimsApp) {
        val name = TextState()
        formDialog({ app.open(it) }, tr("kami_claims.common.rename_country"), tr("kami_claims.rename.subtitle"), Icons.EDIT, tr("kami_claims.rename.action"),
            { name.error == null && name.text.isNotEmpty() && tokenOffer(Tokens.RENAME).affordable }, { name.error ?: tr("kami_claims.token.unaffordable") }, { ClaimsStore.send("rename", name.text, key = "rename") }
        ) { r ->
            val snap = ClaimsStore.snap ?: return@formDialog 0
            val max = snap.limits?.nameMax ?: 24
            name.error = nameError(name.text, snap)
            val offer = tokenOffer(Tokens.RENAME)
            var y = r.y
            fieldLabel(Rect(r.x, y, r.w, 9), tr("kami_claims.wizard.name"), "${name.text.length}/$max"); y += 11
            textField(Rect(r.x, y, r.w, CONTROL_H), name, snap.info?.name.orEmpty(), Icons.FLAG, maxLength = max, allow = NameRules::allows, key = "rename-field", autoFocus = true)
            name.touched = name.text.isNotEmpty()
            y += CONTROL_H + 2
            fieldHelp(Rect(r.x, y, r.w, 9), name, tr("kami_claims.rename.desc")); y += 16
            y += consequences(r.x, y, r.w, listOf(Consequence(offer.text, offer.severity), Consequence(tr("kami_claims.rename.keeps"))))
            y - r.y
        }
    }

    fun rentPayKey(h: HomeLine) = "rent_pay:${h.dim}|${h.x}|${h.z}"

    fun rentPay(app: KamiApp, h: HomeLine) {
        app.open(amountDialog(tr("kami_claims.personal.debts.pay_amount"), tr("kami_claims.home.where", h.country, h.x, h.z), Icons.COIN, maxOf(h.debt, h.rent.toLong()),
            plan = { amount ->
                val funds = ClaimsStore.snap?.funds ?: 0L
                val charged = rentSplit(h.debt, h.credit, h.rent.toLong(), h.room, amount).let { it.first + it.second }
                val reason = when {
                    amount !in 1..funds -> tr("kami_claims.money.disabled.range", Format.number(funds))
                    h.debt > 0 && h.room <= 0 -> tr("kami_claims.error.rent_treasury_full")
                    else -> tr("kami_claims.error.rent_nothing")
                }
                AmountPlan(funds, tr("kami_claims.personal.debts.pay.help", Format.money(h.debt), Format.money(h.credit)), tr("kami_claims.personal.debts.pay.action", Format.money(charged)), reason, charged > 0)
            },
            send = { ClaimsStore.send("rent_pay", h.dim, h.x.toString(), h.z.toString(), it.toString(), key = rentPayKey(h)) }
        ))
    }

    fun money(app: ClaimsApp, deposit: Boolean, suggested: Long = 10) {
        app.open(amountDialog(tr(if (deposit) "kami_libs.common.deposit" else "kami_libs.common.withdraw"), tr("kami_libs.flow.treasury"), if (deposit) Icons.DEPOSIT else Icons.WITHDRAW, suggested,
            header = { r ->
                val snap = ClaimsStore.snap
                val treasury = snap?.info?.treasury ?: 0L
                property(Rect(r.x, r.y, r.w, 11), tr("kami_libs.flow.treasury"), Format.money(treasury), Palette.money)
                property(Rect(r.x, r.y + 12, r.w, 11), tr("kami_claims.kpi.funds"), Format.money(snap?.funds ?: 0L), Palette.money)
                28
            },
            plan = { amount ->
                val snap = ClaimsStore.snap ?: return@amountDialog null
                val info = snap.info ?: return@amountDialog null
                val max = if (deposit) snap.funds else info.treasury
                val spend = -Finance.of(info).net
                val help = when {
                    !deposit && info.government?.treasury == "council" -> tr("kami_claims.money.withdraw.council")
                    !deposit -> tr("kami_claims.money.withdraw.desc")
                    spend > 0 -> tr("kami_claims.money.deposit.desc_runway", Format.days((info.treasury + amount) / spend))
                    else -> tr("kami_claims.money.deposit.desc")
                }
                AmountPlan(max, help, tr(if (deposit) "kami_claims.common.deposit_x" else "kami_claims.money.withdraw.action", Format.money(amount)), moneyReason(max, tr(if (deposit) "kami_claims.money.disabled.no_coins" else "kami_claims.money.disabled.empty")))
            },
            send = { ClaimsStore.send(if (deposit) "deposit" else "withdraw", it.toString()) }
        ))
    }

    fun review(app: KamiApp, title: String, subtitle: String?, icon: Icon, lines: List<DiffLine>, primary: String, onConfirm: () -> Unit) =
        app.open(Dialog(title, subtitle, icon, width = REVIEW_W) { s ->
            val height = diffListHeight(lines.size).coerceIn(DIFF_ROW_H * 2, REVIEW_MAX_H)
            diffList(Rect(s.body.x, s.body.y, s.body.w, height), lines, "review")
            s.used = height + 4
            dialogButtons(s, primary) {
                onConfirm()
                s.close()
            }
        })

    fun reset() {
        batch.clear()
        warningDialog?.close()
        warningDialog = null
    }

    fun warnings(app: ClaimsApp, o: Outcome) {
        ClaimsStore.hold(o.key) { warningDialog?.open == true }
        if (warningDialog?.open != true) {
            batch.clear()
            warningDialog = buildWarningDialog().also(app::open)
        }
        batch += o
    }

    private fun buildWarningDialog(): Dialog {
        val typed = TextState()
        var understood = false
        return Dialog(tr("kami_claims.warn.title"), tr("kami_claims.warn.subtitle"), Icons.WARNING, DialogKind.DESTRUCTIVE, WARNING_W) { s ->
            val grants = grants()
            val critical = grants.any { it.tier == WarnTier.CRITICAL }
            val word = tr("kami_claims.warn.confirm_word")
            var y = s.body.y + consequences(s.body.x, s.body.y, s.body.w, grants.map(::consequence)) + 4
            if (critical) {
                fieldLabel(Rect(s.body.x, y, s.body.w, 9), tr("kami_libs.dialog.type_to_confirm", word))
                y += 11
                textField(Rect(s.body.x, y, s.body.w, CONTROL_H), typed, word, key = "warn-typed", autoFocus = true)
                y += CONTROL_H + 4
            } else {
                checkbox(Rect(s.body.x, y, s.body.w, CHECK_H), tr("kami_claims.warn.understood"), understood)?.let { understood = it }
                y += CHECK_H + 4
            }
            s.used = y - s.body.y
            val ready = if (critical) typed.text.equals(word, true) else understood
            val reason = if (critical) tr("kami_libs.dialog.type_required", word) else tr("kami_claims.warn.tick_required")
            dialogButtons(s, tr("kami_claims.warn.confirm"), ready, reason, primaryStyle = ButtonStyle.DANGER) {
                batch.forEach { ClaimsStore.sendAs(it.viewingAs, it.name, (it.args + CONFIRM_ARG).toTypedArray(), it.key) }
                s.close()
            }
        }
    }

    private fun grants() = batch
        .flatMap { o -> o.confirm.map { Grant(o, it.substringBeforeLast(':'), WarnTier.entries.firstOrNull { tier -> tier.name == it.substringAfterLast(':') } ?: WarnTier.DANGER) } }
        .distinctBy { it.key }
        .sortedByDescending { it.tier }

    private fun consequence(g: Grant): Consequence {
        val holders = if (g.outcome.name in RANK_TARGET_ACTIONS) ClaimsStore.info?.ranks?.firstOrNull { it.id == g.outcome.args.firstOrNull() }?.members ?: 0 else 0
        return Consequence(tr("kami_claims.warn.line", tr("kami_claims.warn.tier.${g.tier.name.lowercase()}"), tr("kami_claims.warn.${g.key}", holders)), g.tier.look)
    }
}

private class Grant(val outcome: Outcome, val key: String, val tier: WarnTier)

private val WarnTier.look get() = when (this) {
    WarnTier.INFO -> Severity.INFO
    WarnTier.CAUTION -> Severity.WARNING
    WarnTier.DANGER, WarnTier.CRITICAL -> Severity.DANGER
}

private val batch = ArrayList<Outcome>()
private var warningDialog: Dialog? = null

private val RANK_TARGET_ACTIONS = setOf("rank_edit", "rank_perms", "rank_delete")
private const val CONFIRM_ARG = "confirm"
private const val REVIEW_W = 340
private const val REVIEW_MAX_H = 150
private const val WARNING_W = 320
private const val CHECK_H = 14
