package kami.claims.client.app.pages

import kami.claims.client.app.ClaimsApp
import kami.claims.client.app.ClaimsPage
import kami.claims.client.app.GovernmentLevers
import kami.claims.client.app.wireEnum
import kami.claims.client.app.Dialogs
import kami.claims.client.store.ClaimsStore
import kami.claims.gov.PRESIDENT
import kami.claims.gov.RESET_RANKS
import kami.claims.gov.PresetId
import kami.claims.gov.Presets
import kami.claims.net.GovLine
import kami.claims.net.Info
import kami.libs.ui.app.Callout
import kami.libs.ui.app.Route
import kami.libs.ui.core.Rect
import kami.libs.ui.core.Ui
import kami.libs.ui.style.Draw
import kami.libs.ui.style.Format
import kami.libs.ui.style.Icons
import kami.libs.ui.style.Palette
import kami.libs.ui.style.Sprites
import kami.libs.ui.style.TextStyle
import kami.libs.ui.text.tr
import kami.libs.ui.widget.CONTROL_H
import kami.libs.ui.widget.DiffLine
import kami.libs.ui.widget.SCROLL_GUTTER
import kami.libs.ui.widget.checkbox
import kami.libs.ui.widget.clickable
import kami.libs.ui.widget.fieldLabel
import kami.libs.ui.widget.fitText
import kami.libs.ui.widget.property
import kami.libs.ui.widget.scroll
import kami.libs.ui.widget.section
import kami.libs.ui.widget.segmented

class GovernmentPage(app: ClaimsApp) : ClaimsPage(app) {
    override val title get() = tr("kami_claims.nav.government")
    override val subtitle get() = tr("kami_claims.gov.subtitle")
    override val help get() = listOf(
        Callout("gov:presets", tr("kami_claims.gov.help.presets"), tr("kami_claims.gov.help.presets.desc")),
        Callout("gov:levers", tr("kami_claims.gov.help.levers"), tr("kami_claims.gov.help.levers.desc"))
    )
    private var preset: PresetId? = null
    private var values: List<String>? = null
    private var resetRanks = false
    private var droppedEdits = false

    init { onSuccess("gov_apply", ::discard) }

    private fun changes(gov: GovLine) = (if (chosenPreset(gov) != current(gov)) 1 else 0) + GovernmentLevers.values(gov).indices.count { chosen(gov)[it] != GovernmentLevers.values(gov)[it] } + (if (resetRanks) 1 else 0)

    private fun current(gov: GovLine) = wireEnum(gov.preset, PresetId.CUSTOM)

    private fun chosenPreset(gov: GovLine) = preset ?: current(gov)

    private fun chosen(gov: GovLine) = values ?: GovernmentLevers.values(gov)

    private fun discard() {
        preset = null
        values = null
        resetRanks = false
        droppedEdits = false
    }

    private fun isProvince(info: Info) = info.parent.isNotEmpty()

    private fun hasLeverEdits(gov: GovLine) = chosen(gov) != (preset?.let { GovernmentLevers.values(Presets.levers(it)) } ?: GovernmentLevers.values(gov))

    private fun pick(next: PresetId, gov: GovLine, info: Info) {
        droppedEdits = hasLeverEdits(gov)
        val kept = chosen(gov)
        val levers = GovernmentLevers.values(Presets.levers(next))
        preset = next
        values = if (isProvince(info)) GovernmentLevers.keeping(levers, kept, GovernmentLevers.PROVINCES) else levers
    }

    override fun leaving(next: Route): Boolean {
        val gov = info?.government ?: return super.leaving(next)
        return guardLeave(next, changes(gov) > 0, if (applyLock(gov) == null) { { send(gov) } } else null, discard = ::discard) && super.leaving(next)
    }

    private fun isPresident(info: Info) = ClaimsStore.myRank?.id == PRESIDENT && !info.delegated

    private fun applyLock(gov: GovLine): String? {
        val info = info ?: return null
        return when {
            !isPresident(info) -> tr("kami_claims.gov.lock.president")
            gov.changedAt > 0 && gov.cooldownUntil > System.currentTimeMillis() -> tr("kami_claims.gov.lock.cooldown", Format.until(gov.cooldownUntil))
            !gov.hasToken && info.treasury < gov.cost -> tr("kami_claims.gov.lock.funds", Format.money(gov.cost))
            else -> null
        }
    }

    override fun draw(ui: Ui, r: Rect) {
        val info = info ?: return
        val gov = info.government ?: return
        val editable = isPresident(info)
        val picked = chosenPreset(gov)
        val staged = chosen(gov)
        val content = r.dropBottom(BAR_H + 6, 4)
        val cols = if (content.w >= WIDE_W) 2 else 1
        val detailW = content.w - SCROLL_GUTTER
        val detailH = PresetDetail.height(detailW, picked, true)
        val total = CURRENT_H + SECTION_H + cardRows(cols) * (CARD_H + GAP) + SECTION_H + detailH + SECTION_H + GovernmentLevers.all.size * LEVER_H + (if (droppedEdits) NOTE_H else 0) + CHECK_H + GAP
        ui.scroll("government", content, total) { area ->
            var y = area.y
            y += currentCard(ui, Rect(area.x, y, area.w, CURRENT_H), gov)
            y += cards(ui, Rect(area.x, y, area.w, 0), cols, picked, current(gov), editable, gov, info)
            y += details(ui, Rect(area.x, y, area.w, 0), picked, detailW, detailH)
            y += levers(ui, Rect(area.x, y, area.w, 0), staged, editable, isProvince(info))
            ui.checkbox(Rect(area.x, y, area.w, CHECK_H), tr("kami_claims.gov.reset_ranks"), resetRanks, editable, tr("kami_claims.gov.lock.president"), tr("kami_claims.gov.reset_ranks.tip"), "gov-reset")?.let { resetRanks = it }
        }
        val summary = tr("kami_claims.gov.summary", PresetDetail.name(picked), costText(gov))
        ui.applyBar(r.bottom(BAR_H), changes(gov), summary, applyLock(gov) == null, applyLock(gov), pending("gov_apply"), "gov", { review(gov) }, ::discard)
    }

    private fun costText(gov: GovLine) = if (gov.hasToken) tr("kami_claims.gov.cost.free") else tr("kami_claims.gov.cost.fee", Format.money(gov.cost))

    private fun currentCard(ui: Ui, r: Rect, gov: GovLine): Int {
        val body = ui.section(Rect(r.x, r.y, r.w, SECTION_H), tr("kami_claims.gov.current"), PresetDetail.name(current(gov)))
        ui.property(Rect(body.x, body.y, body.w, PROPERTY_H), tr("kami_claims.gov.cost"), costText(gov))
        val cooling = gov.changedAt > 0 && gov.cooldownUntil > System.currentTimeMillis()
        ui.property(Rect(body.x, body.y + PROPERTY_H, body.w, PROPERTY_H), tr("kami_claims.gov.cooldown"), if (cooling) Format.until(gov.cooldownUntil) else tr("kami_claims.gov.cooldown.none"), if (cooling) Palette.warning else Palette.success)
        return CURRENT_H
    }

    private fun cardRows(cols: Int) = (PresetId.entries.size + cols - 1) / cols

    private fun cards(ui: Ui, at: Rect, cols: Int, picked: PresetId, current: PresetId, editable: Boolean, gov: GovLine, info: Info): Int {
        val body = ui.section(Rect(at.x, at.y, at.w, SECTION_H), tr("kami_claims.gov.presets"))
        ui.anchor("gov:presets", Rect(body.x, body.y, body.w, cardRows(cols) * (CARD_H + GAP)))
        val width = (body.w - (cols - 1) * GAP) / cols
        PresetId.entries.forEachIndexed { i, p ->
            val card = Rect(body.x + (i % cols) * (width + GAP), body.y + (i / cols) * (CARD_H + GAP), width, CARD_H)
            Draw.sprite(ui.g, Sprites.CARD, card)
            val key = "gov-card:${p.key}"
            if (editable && ui.hovering(card)) Draw.fill(ui.g, card, Palette.hover)
            if (p == picked) Draw.outline(ui.g, card, Palette.brass)
            val title = if (!editable) Palette.textDisabled else if (p == picked) Palette.brass else Palette.text
            ui.fitText(card.x + 8, card.y + 5, card.w - 16, PresetDetail.name(p) + if (p == current) " · " + tr("kami_claims.gov.current.tag") else "", title, style = TextStyle.HEADING)
            ui.fitText(card.x + 8, card.y + 17, card.w - 16, PresetDetail.tagline(p), if (editable) Palette.textMuted else Palette.textDisabled)
            if (ui.clickable(key, card, editable)) pick(p, gov, info)
            ui.focusRing(key, card)
            ui.tooltip("gov-card-tip:${p.key}", card, if (editable) PresetDetail.tagline(p) else tr("kami_claims.gov.lock.president"))
        }
        return SECTION_H + cardRows(cols) * (CARD_H + GAP)
    }

    private fun details(ui: Ui, at: Rect, picked: PresetId, width: Int, height: Int): Int {
        val body = ui.section(Rect(at.x, at.y, at.w, SECTION_H), tr("kami_claims.gov.about", PresetDetail.name(picked)))
        PresetDetail.draw(ui, body.x, body.y, width, picked, true)
        return SECTION_H + height
    }

    private fun levers(ui: Ui, at: Rect, values: List<String>, editable: Boolean, province: Boolean): Int {
        val body = ui.section(Rect(at.x, at.y, at.w, SECTION_H), tr("kami_claims.gov.levers"))
        ui.anchor("gov:levers", Rect(body.x, body.y, body.w, GovernmentLevers.all.size * LEVER_H))
        GovernmentLevers.all.forEachIndexed { i, (id, _) ->
            val y = body.y + i * LEVER_H
            val label = Rect(body.x, y, body.w, 9)
            ui.fieldLabel(label, GovernmentLevers.label(id))
            ui.tooltip("lever-tip:$id", label, tr("kami_claims.lever.$id.desc"))
            val control = Rect(body.x, y + 10, body.w, CONTROL_H)
            val locked = province && id == GovernmentLevers.PROVINCES
            ui.segmented(control, GovernmentLevers.options(id), values[i], editable && !locked, "lever:$id")
                ?.let { next -> this.values = GovernmentLevers.with(values, i, next); droppedEdits = false }
            if (locked) ui.tooltip("lever-lock:$id", control, tr("kami_claims.gov.lock.province"))
        }
        if (droppedEdits) ui.fitText(body.x, body.y + GovernmentLevers.all.size * LEVER_H + 2, body.w, tr("kami_claims.gov.dropped_edits"), Palette.warning)
        return SECTION_H + GovernmentLevers.all.size * LEVER_H + if (droppedEdits) NOTE_H else 0
    }

    private fun review(gov: GovLine) =
        Dialogs.review(app, tr("kami_claims.gov.review.title"), costText(gov), Icons.CROWN, lines(gov), tr("kami_libs.common.apply")) { send(gov) }

    private fun lines(gov: GovLine): List<DiffLine> {
        val before = GovernmentLevers.values(gov)
        val after = chosen(gov)
        val presetLines = if (chosenPreset(gov) != current(gov)) DiffLine.change(tr("kami_claims.gov.preset"), PresetDetail.name(current(gov)), PresetDetail.name(chosenPreset(gov))) else emptyList()
        val leverLines = GovernmentLevers.all.indices.filter { before[it] != after[it] }.flatMap { val id = GovernmentLevers.all[it].first; DiffLine.change(GovernmentLevers.label(id), GovernmentLevers.valueLabel(id, before[it]), GovernmentLevers.valueLabel(id, after[it])) }
        return presetLines + leverLines + listOfNotNull(DiffLine(tr("kami_claims.gov.reset_ranks.line"), true).takeIf { resetRanks })
    }

    private fun send(gov: GovLine) {
        val args = listOf(chosenPreset(gov).key) + chosen(gov) + listOfNotNull(RESET_RANKS.takeIf { resetRanks })
        act("gov_apply", *args.toTypedArray(), key = "gov_apply")
    }
}

private const val BAR_H = 28
private const val WIDE_W = 420
private const val SECTION_H = 13
private const val CURRENT_H = SECTION_H + 22 + 6
private const val PROPERTY_H = 11
private const val CARD_H = 30
private const val GAP = 6
private val LEVER_H get() = 10 + CONTROL_H + 4
private const val CHECK_H = 14
private const val NOTE_H = 12
