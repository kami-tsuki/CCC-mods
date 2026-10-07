package kami.claims.client.app.pages

import kami.claims.client.app.ClaimsApp
import kami.claims.client.app.ClaimsPage
import kami.claims.client.app.Dialogs
import kami.claims.client.store.ClaimsStore
import kami.claims.net.Info
import kami.claims.net.OfferTerms
import kami.claims.net.StakeLine
import kami.claims.net.WarLine
import kami.libs.ui.app.Consequence
import kami.libs.ui.app.Dialog
import kami.libs.ui.app.DialogKind
import kami.libs.ui.app.consequences
import kami.libs.ui.app.dialogButtons
import kami.libs.ui.core.Flow
import kami.libs.ui.core.Rect
import kami.libs.ui.core.Row
import kami.libs.ui.core.Ui
import kami.libs.ui.style.Draw
import kami.libs.ui.style.Format
import kami.libs.ui.style.Icon
import kami.libs.ui.style.Icons
import kami.libs.ui.style.Palette
import kami.libs.ui.style.Severity
import kami.libs.ui.style.TextStyle
import kami.libs.ui.text.tr
import kami.libs.ui.text.trn
import kami.libs.ui.widget.*

class WarPage(app: ClaimsApp) : ClaimsPage(app) {
    override val title get() = tr("kami_claims.nav.war")
    override val sections = listOf("world")
    private val table = TableState<WarLine>()
    private var tab = 0

    override fun draw(ui: Ui, r: Rect) {
        val info = info ?: return
        val tabs = r.top(CONTROL_H)
        ui.subTabs(tabs.dropRight(130), listOf(
            TabItem(tr("kami_claims.warpage.tab.wars"), Icons.SHIELD, info.wars.size, if (info.wars.any { it.offer != null && it.offerFrom != info.name }) Severity.WARNING else Severity.NEUTRAL),
            TabItem(tr("kami_claims.warpage.tab.standing"), Icons.STAR, info.warDebts.size, Severity.NEUTRAL)
        ), tab, "war-tabs")?.let { tab = it }
        val chief = chief(info)
        val cooling = info.cooldownUntil > System.currentTimeMillis()
        val why = chief ?: if (cooling) tr("kami_claims.warpage.cooldown", Format.until(info.cooldownUntil)) else null
        if (ui.button(tabs.right(126), tr("kami_claims.warpage.declare"), Icons.FLAG, ButtonStyle.DANGER, why == null, why, pending = pending("war_declare"), key = "war-declare")) declare(info)
        val body = r.dropTop(CONTROL_H + 6)
        if (tab == 0) wars(ui, body, info) else standing(ui, body, info)
    }

    private fun chief(info: Info) = when {
        ClaimsStore.delegated -> tr("kami_claims.warpage.own_only")
        info.rank.equals("president", true) -> null
        else -> tr("kami_claims.warpage.president_only")
    }

    private fun wars(ui: Ui, r: Rect, info: Info) {
        if (info.wars.isEmpty()) {
            ui.emptyState(r, tr("kami_claims.warpage.empty.title"), tr("kami_claims.warpage.empty.desc"))
            return
        }
        val detailW = if (app.compact) 0 else (r.w * 0.5).toInt().coerceIn(180, 260)
        ui.table(r.dropRight(detailW, if (detailW > 0) 8 else 0), listOf(
            Column.text<WarLine>(tr("kami_claims.warpage.col.war"), -1) { tr("kami_claims.warpage.versus", it.attacker, it.defender) },
            Column.text<WarLine>(tr("kami_claims.warpage.col.phase"), 70, color = { phaseColor(it.phase) }) { phase(it.phase) },
            Column.text<WarLine>(tr("kami_claims.warpage.col.left"), 64) { left(it.ends) }
        ), info.wars, table, { it.id }, severity = { if (it.offer != null && it.offerFrom != info.name) Severity.WARNING else null })
        if (detailW > 0) (info.wars.firstOrNull { it.id in table.selected } ?: info.wars.first()).let { detail(ui, r.right(detailW), it, info) }
    }

    private fun detail(ui: Ui, r: Rect, w: WarLine, info: Info) {
        val f = ui.sidePanel(r, 3, 8)
        val head = f.take(22)
        Draw.text(ui.g, Draw.fit(tr("kami_claims.warpage.versus", w.attacker, w.defender), head.w), head.x, head.y, TextStyle.HEADING)
        Draw.text(ui.g, Draw.fit("${phase(w.phase)} · ${left(w.ends)}", head.w), head.x, head.y + 12, phaseColor(w.phase))
        if (w.kind == "rebellion") ui.property(f.take(11), tr("kami_claims.warpage.kind"), tr("kami_claims.warpage.kind.rebellion"), Palette.warning)
        if (w.side.isNotEmpty()) ui.property(f.take(11), tr("kami_claims.warpage.side"), tr("kami_claims.warpage.side.${w.side}"))
        if (w.joinedA.isNotEmpty()) ui.property(f.take(11), tr("kami_claims.warpage.joined", w.attacker), w.joinedA.joinToString(", "))
        if (w.joinedD.isNotEmpty()) ui.property(f.take(11), tr("kami_claims.warpage.joined", w.defender), w.joinedD.joinToString(", "))
        ui.property(f.take(11), tr("kami_claims.warpage.stakes"), stakes(w.stakes))
        if (w.fee > 0 || w.escrow > 0) ui.property(f.take(11), tr("kami_claims.warpage.fee"), "${Format.money(w.fee)} · ${Format.money(w.escrow)}", Palette.money)
        if (w.billA > 0 || w.billD > 0) ui.property(f.take(11), tr("kami_claims.warpage.bills"), "${Format.money(w.billA)} · ${Format.money(w.billD)}", Palette.money)
        if (w.ceasefires > 0) ui.property(f.take(11), tr("kami_claims.warpage.ceasefires"), Format.number(w.ceasefires))
        exhaustion(ui, f, w.attacker, w.exhaustionA)
        exhaustion(ui, f, w.defender, w.exhaustionD)
        w.offer?.let { o ->
            ui.section(f.take(14), tr("kami_claims.warpage.offer", w.offerFrom))
            f.take(Draw.paragraph(ui.g, terms(o), f.rest.x, f.rest.y, f.rest.w, Palette.textSecondary))
        }
        if (w.result.isNotEmpty()) ui.property(f.take(11), tr("kami_claims.warpage.result"), tr("kami_claims.warpage.result.${w.result}"))
        actions(ui, f.rest, w, info)
    }

    private fun exhaustion(ui: Ui, f: Flow, name: String, value: Double) {
        val row = f.take(9)
        Draw.text(ui.g, Draw.fit(tr("kami_claims.warpage.exhaustion", name), row.w - 30), row.x, row.y, Palette.textMuted)
        Draw.textRight(ui.g, Format.percent(value / 100.0), row.right, row.y, Palette.textSecondary)
        ui.progress(f.take(5), value / 100.0, if (value >= 75) Palette.danger else Palette.warning)
    }

    private fun actions(ui: Ui, r: Rect, w: WarLine, info: Info) {
        val chief = chief(info)
        val main = info.name == w.attacker || info.name == w.defender
        val enemy = if (info.name == w.attacker) w.defender else w.attacker
        var y = r.bottom - CONTROL_H
        fun button(label: String, icon: Icon, style: ButtonStyle = ButtonStyle.SECONDARY, key: String, run: () -> Unit) {
            if (y < r.y) return
            if (ui.button(Rect(r.x, y, r.w, CONTROL_H), label, icon, style, chief == null, chief, pending = pending(key), key = "war-$key-${w.id}")) run()
            y -= CONTROL_H + 3
        }
        fun confirm(action: String, label: String, icon: Icon, vararg lines: Consequence, danger: Boolean = true) =
            Dialogs.confirm(app, label, tr("kami_claims.warpage.versus", w.attacker, w.defender), icon, lines.toList(), label, action, arrayOf(enemy, "confirm"), danger = danger, hold = danger)
        w.joinSides.forEach { side ->
            val ally = if (side == "attacker") w.attacker else w.defender
            if (info.wars.count { it.canJoin && (it.attacker == ally || it.defender == ally) } > 1) return@forEach
            val label = tr("kami_claims.warpage.join", ally)
            button(label, Icons.HANDSHAKE, ButtonStyle.PRIMARY, "war_join") {
                Dialogs.confirm(app, tr("kami_claims.confirm.war_join.title", ally, if (side == "attacker") w.defender else w.attacker), null, Icons.HANDSHAKE,
                    listOf(Consequence(tr("kami_claims.warpage.join.locks"), Severity.WARNING)), label, "war_join", arrayOf(ally, "confirm"), danger = true, hold = true)
            }
        }
        if (!main) return
        val open = w.offer != null && w.offerFrom != info.name
        if (open) {
            button(tr("kami_libs.common.decline"), Icons.CROSS, key = "war_decline") { act("war_decline", enemy) }
            button(tr("kami_libs.common.accept"), Icons.CHECK, ButtonStyle.PRIMARY, "war_accept") {
                confirm("war_accept", tr("kami_libs.common.accept"), Icons.CHECK, Consequence(tr("kami_claims.confirm.war_accept", enemy, w.offer?.let(::terms).orEmpty())), danger = false)
            }
        }
        if (w.phase != "ended") button(tr("kami_claims.warpage.offer.make"), Icons.SCROLL, key = "war_offer") { offer(w, enemy) }
        val defending = info.name == w.defender
        when (w.phase) {
            "declaration" -> if (defending) {
                button(tr("kami_claims.warpage.agree"), Icons.FLAG, ButtonStyle.DANGER, "war_agree") {
                    confirm("war_agree", tr("kami_claims.warpage.agree"), Icons.FLAG, Consequence(tr("kami_claims.confirm.war_agree", enemy), Severity.DANGER), Consequence(tr("kami_claims.warpage.stakes.line", stakes(w.stakes)), Severity.WARNING))
                }
                button(tr("kami_claims.warpage.fight"), Icons.SHIELD, ButtonStyle.PRIMARY, "war_fight") { act("war_fight", enemy) }
            } else button(tr("kami_claims.warpage.withdraw"), Icons.REMOVE, ButtonStyle.DANGER, "war_withdraw") {
                confirm("war_withdraw", tr("kami_claims.warpage.withdraw"), Icons.REMOVE, Consequence(tr("kami_claims.confirm.war_withdraw", enemy), Severity.DANGER), Consequence(tr("kami_claims.warpage.withdraw.reputation"), Severity.WARNING))
            }
            "war", "ceasefire" -> button(tr("kami_claims.warpage.capitulate"), Icons.FLAG, ButtonStyle.DANGER, "war_capitulate") {
                confirm("war_capitulate", tr("kami_claims.warpage.capitulate"), Icons.FLAG, Consequence(tr("kami_claims.confirm.war_capitulate", enemy), Severity.DANGER))
            }
        }
    }

    private fun standing(ui: Ui, r: Rect, info: Info) {
        val half = (r.w - 8) / 2
        val left = Flow(ui.card(Rect(r.x, r.y, half, r.h), tr("kami_claims.warpage.standing"), Icons.STAR, key = "war-standing"), 3)
        info.power?.let { ui.property(left.take(11), tr("kami_claims.kpi.power"), power(it)) }
        ui.property(left.take(11), tr("kami_claims.warpage.reputation"), Format.number(info.reputation), reputationColor(info.reputation))
        ui.property(left.take(11), tr("kami_claims.warpage.fame"), info.fame?.let { stars(it) } ?: tr("kami_claims.warpage.fame.hidden"), Palette.brass)
        ui.property(left.take(11), tr("kami_claims.warpage.fame.votes"), Format.number(info.fameCount))
        if (info.cooldownUntil > System.currentTimeMillis()) ui.property(left.take(11), tr("kami_claims.warpage.cooldown.label"), Format.until(info.cooldownUntil), Palette.warning)
        if (info.warOverlord.isNotEmpty()) ui.property(left.take(11), tr("kami_claims.warpage.war_province"), info.warOverlord, Palette.danger)
        ui.section(left.take(14), tr("kami_claims.warpage.debts"))
        if (info.warDebts.isEmpty()) left.take(11).let { Draw.text(ui.g, tr("kami_claims.warpage.debts.none"), it.x, it.y, Palette.textMuted) }
        val chief = chief(info)
        info.warDebts.forEach { (to, amount) ->
            if (left.rest.h < CONTROL_H) return@forEach
            val row = left.take(CONTROL_H)
            val pay = minOf(amount, info.treasury)
            val label = tr("kami_claims.warpage.repay")
            val bw = buttonWidth(label, Icons.COIN)
            Draw.text(ui.g, Draw.fit("$to · ${Format.money(amount)}", row.w - bw - 4), row.x, row.y + 5, Palette.text)
            val reason = chief ?: if (pay <= 0) tr("kami_claims.warpage.repay.empty") else null
            if (ui.button(row.right(bw), label, Icons.COIN, enabled = reason == null, disabledReason = reason, pending = pending("war_repay"), key = "war-repay-$to")) {
                Dialogs.confirm(app, tr("kami_claims.warpage.repay.title", to), null, Icons.COIN, listOf(Consequence(tr("kami_claims.warpage.repay.amount", Format.money(pay), Format.money(amount)))), label, "war_repay", arrayOf(to, pay.toString()))
            }
        }
        val right = Flow(ui.card(Rect(r.x + half + 8, r.y, r.w - half - 8, r.h), tr("kami_claims.warpage.safe"), Icons.SHIELD, trailing = "${info.safeChunks.size}/${info.safeLimit}", key = "war-safe"), 3)
        right.take(Draw.paragraph(ui.g, tr("kami_claims.warpage.safe.desc"), right.rest.x, right.rest.y, right.rest.w, Palette.textMuted))
        val row = Row(right.take(CONTROL_H), 4)
        val add = tr("kami_claims.warpage.safe.add")
        val full = chief ?: if (info.safeChunks.size >= info.safeLimit) tr("kami_claims.warpage.safe.full") else null
        if (ui.button(row.take(row.rest.w / 2 - 2), add, Icons.ADD, enabled = full == null, disabledReason = full, pending = pending("war_safe"), key = "war-safe-add")) act("war_safe", "add")
        if (ui.button(row.rest, tr("kami_claims.warpage.safe.remove"), Icons.REMOVE, enabled = chief == null, disabledReason = chief, pending = pending("war_safe"), key = "war-safe-remove")) act("war_safe", "remove")
        info.safeChunks.forEach { s ->
            if (right.rest.h < 11) return
            right.take(11).let { Draw.text(ui.g, Draw.fit("${s.x}, ${s.z} · ${s.dim.substringAfter(':')}", it.w), it.x, it.y, Palette.textSecondary) }
        }
    }

    private fun declare(info: Info) {
        var target: String? = null
        val money = NumberState(0)
        var vassal = false
        val provinces = TextState()
        app.open(Dialog(tr("kami_claims.warpage.declare"), tr("kami_claims.warpage.declare.subtitle"), Icons.FLAG, DialogKind.DESTRUCTIVE, 320) { s ->
            val countries = ClaimsStore.countries.filter { it.name != info.name }
            var y = s.body.y
            fieldLabel(Rect(s.body.x, y, s.body.w, 9), tr("kami_claims.warpage.declare.target")); y += 11
            select(Rect(s.body.x, y, s.body.w, CONTROL_H), countries.map { Option(it.name, it.name, null, tr("kami_claims.warpage.reputation.short", it.reputation), it.color) }, target, key = "war-target")?.let { target = it }
            y += CONTROL_H + 6
            fieldLabel(Rect(s.body.x, y, s.body.w, 9), tr("kami_claims.warpage.stake.money")); y += 11
            numberField(Rect(s.body.x, y, s.body.w, CONTROL_H), money, 0, info.treasury.coerceAtLeast(0), 100, Format.COIN, key = "war-money"); y += CONTROL_H + 4
            toggle(Rect(s.body.x, y, s.body.w, 14), vassal, tr("kami_claims.warpage.stake.vassal.toggle"), key = "war-vassal")?.let { vassal = it }; y += 18
            fieldLabel(Rect(s.body.x, y, s.body.w, 9), tr("kami_claims.warpage.stake.provinces.field")); y += 11
            textField(Rect(s.body.x, y, s.body.w, CONTROL_H), provinces, tr("kami_claims.warpage.stake.provinces.hint"), key = "war-provinces", maxLength = 200); y += CONTROL_H + 6
            y += consequences(s.body.x, y, s.body.w, listOf(Consequence(tr("kami_claims.warpage.declare.fee"), Severity.WARNING), Consequence(tr("kami_claims.warpage.declare.locks")))) + 4
            s.used = y - s.body.y
            dialogButtons(s, tr("kami_claims.warpage.declare"), target != null, tr("kami_claims.warpage.declare.pick"), hold = true, primaryStyle = ButtonStyle.DANGER) {
                val names = provinces.text.split(',').map { it.trim() }.filter { it.isNotEmpty() }
                val args = listOf(target ?: return@dialogButtons) + (if (money.value > 0) listOf("money", money.value.toString()) else emptyList()) +
                    (if (vassal) listOf("vassal") else emptyList()) + names.flatMap { listOf("province", it) } + "confirm"
                ClaimsStore.send("war_declare", *args.toTypedArray(), key = "war_declare")
                s.close()
            }
        })
    }

    private fun offer(w: WarLine, enemy: String) {
        val ceasefire = NumberState(0)
        val extend = NumberState(0)
        val bill = NumberState(100)
        val money = NumberState(0)
        var peace = false
        app.open(Dialog(tr("kami_claims.warpage.offer.make"), tr("kami_claims.warpage.versus", w.attacker, w.defender), Icons.SCROLL, DialogKind.CONFIRM, 300) { s ->
            var y = s.body.y
            fun number(label: String, state: NumberState, max: Long, unit: String?, key: String) {
                fieldLabel(Rect(s.body.x, y, s.body.w, 9), label); y += 11
                numberField(Rect(s.body.x, y, s.body.w, CONTROL_H), state, 0, max, 1, unit, key = key); y += CONTROL_H + 4
            }
            number(tr("kami_claims.warpage.offer.ceasefire"), ceasefire, 7, null, "offer-ceasefire")
            number(tr("kami_claims.warpage.offer.extend"), extend, 3, null, "offer-extend")
            number(tr("kami_claims.warpage.offer.bill"), bill, 100, "%", "offer-bill")
            number(tr("kami_claims.warpage.stake.money"), money, Long.MAX_VALUE, Format.COIN, "offer-money")
            toggle(Rect(s.body.x, y, s.body.w, 14), peace, tr("kami_claims.warpage.offer.peace"), key = "offer-peace")?.let { peace = it }; y += 18
            if (money.value > 0) y += consequences(s.body.x, y, s.body.w, listOf(Consequence(tr("kami_claims.warpage.offer.replaces", Format.money(money.value)), Severity.WARNING))) + 4
            s.used = y - s.body.y
            val tokens = (if (ceasefire.value > 0) listOf("ceasefire", ceasefire.value.toString()) else emptyList()) +
                (if (extend.value > 0) listOf("extend", extend.value.toString()) else emptyList()) +
                (if (peace) listOf("peace") else emptyList()) + (if (bill.value != 100L) listOf("bill", bill.value.toString()) else emptyList()) +
                (if (money.value > 0) listOf("money", money.value.toString()) else emptyList())
            dialogButtons(s, tr("kami_claims.warpage.offer.send"), tokens.isNotEmpty(), tr("kami_claims.warpage.offer.empty")) {
                ClaimsStore.send("war_offer", enemy, *tokens.toTypedArray(), "confirm", key = "war_offer")
                s.close()
            }
        })
    }

    private fun phase(p: String) = tr("kami_claims.warpage.phase.$p")

    private fun phaseColor(p: String) = when (p) {
        "declaration" -> Palette.warning
        "preparation" -> Palette.info
        "war" -> Palette.danger
        "ceasefire" -> Palette.success
        else -> Palette.textSecondary
    }

    private fun left(ends: Long) = if (ends > 0) Format.until(ends) else tr("kami_claims.warpage.open_ended")

    private fun stakes(s: StakeLine) = listOfNotNull(
        trn("kami_claims.unit.chunk", s.chunks).takeIf { s.chunks > 0 },
        Format.money(s.money).takeIf { s.money > 0 },
        tr("kami_claims.warpage.stake.vassal").takeIf { s.vassal },
        tr("kami_claims.warpage.stake.provinces", s.provinces.joinToString(", ")).takeIf { s.provinces.isNotEmpty() }
    ).joinToString(", ").ifEmpty { tr("kami_claims.warpage.stake.none") }

    private fun terms(o: OfferTerms) = listOfNotNull(
        o.stakes?.let { tr("kami_claims.warpage.stakes.line", stakes(it)) },
        tr("kami_claims.war.term.ceasefire", Format.days(o.ceasefireDays.toLong())).takeIf { o.ceasefireDays > 0 },
        tr("kami_claims.war.term.extend", Format.days(o.extendDays.toLong())).takeIf { o.extendDays > 0 },
        tr("kami_claims.war.term.peace").takeIf { o.peace },
        o.billPct?.let { tr("kami_claims.war.term.bill", it) }
    ).joinToString(", ")

    companion object {
        fun stars(fame: Double) = "${Format.decimal(fame, 1)} ★"
        fun power(p: Double) = Format.decimal(p, 1)
        fun reputationColor(r: Int) = when {
            r >= 1200 -> Palette.success
            r < 800 -> Palette.danger
            else -> Palette.text
        }
    }
}
