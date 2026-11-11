package kami.claims.client.app.pages

import kami.claims.client.app.ClaimsApp
import kami.claims.client.app.Dialogs
import kami.claims.client.app.Vocabulary
import kami.claims.client.app.rankLine
import kami.claims.client.app.permsOf
import kami.claims.client.app.wireEnum
import kami.claims.client.store.ClaimsStore
import kami.claims.gov.FLAT_RANK_CAP
import kami.claims.gov.PRESIDENT
import kami.claims.gov.Perm
import kami.claims.gov.PresetId
import kami.claims.gov.Presets
import kami.claims.gov.RankSeed
import kami.claims.gov.RoleTemplate
import kami.claims.net.Info
import kami.claims.net.Mem
import kami.claims.net.RankLine
import kami.libs.ui.app.Consequence
import kami.libs.ui.app.Dialog
import kami.libs.ui.app.DialogKind
import kami.libs.ui.app.consequences
import kami.libs.ui.app.dialogButtons
import kami.libs.ui.app.formDialog
import kami.libs.ui.core.Rect
import kami.libs.ui.core.Stack
import kami.libs.ui.core.Ui
import kami.libs.ui.style.Draw
import kami.libs.ui.style.Icons
import kami.libs.ui.style.Palette
import kami.libs.ui.style.Severity
import kami.libs.ui.text.tr
import kami.libs.ui.widget.ButtonStyle
import kami.libs.ui.widget.COLOR_PICKER_H
import kami.libs.ui.widget.ChipSpec
import kami.libs.ui.widget.CONTROL_H
import kami.libs.ui.widget.DEFAULT_COLORS
import kami.libs.ui.widget.Option
import kami.libs.ui.widget.TextState
import kami.libs.ui.widget.chipFlow
import kami.libs.ui.widget.colorPicker
import kami.libs.ui.widget.colorProblem
import kami.libs.ui.widget.fieldLabel
import kami.libs.ui.widget.select
import kami.libs.ui.widget.textField

object RankDialogs {
    private const val BLANK = "blank"
    private const val MAX_LINES = 8
    private const val DELETE_W = 320
    private const val LABEL_H = 11

    fun create(app: ClaimsApp, onSent: (String) -> Unit) {
        val name = TextState()
        var color = DEFAULT_COLORS.first()
        var template = BLANK
        val limits = ClaimsStore.snap?.limits
        val nameMax = NameCheck.max(limits)
        fun problem() = NameCheck.problem(name.text, limits) ?: colorProblem(color)
        formDialog({ app.open(it) }, tr("kami_claims.ranks.new.title"), tr("kami_claims.ranks.new.subtitle"), Icons.ADD, tr("kami_claims.ranks.new.action"),
            { problem() == null }, { problem().orEmpty() },
            {
                ClaimsStore.send("rank_create", name.text.trim(), color.toString(), template, key = "ranks:create")
                onSent(name.text.trim())
            }
        ) { r ->
            var y = r.y
            y += suggestions(r, y) { seed ->
                name.set(seed.name)
                color = seed.color
                template = seed.template.key
            }
            fieldLabel(Rect(r.x, y, r.w, 9), tr("kami_claims.ranks.field.name"), "${name.text.length}/$nameMax"); y += LABEL_H
            textField(Rect(r.x, y, r.w, CONTROL_H), name, tr("kami_claims.ranks.new.placeholder"), Icons.STAR, maxLength = nameMax, allow = NameCheck::allows, key = "new-rank-name", autoFocus = true); y += CONTROL_H + 4
            fieldLabel(Rect(r.x, y, r.w, 9), tr("kami_claims.ranks.field.color")); y += LABEL_H
            colorPicker(Rect(r.x, y, r.w, COLOR_PICKER_H), color, "new-rank-color")?.let { color = it }; y += COLOR_PICKER_H + 4
            fieldLabel(Rect(r.x, y, r.w, 9), tr("kami_claims.ranks.field.template")); y += LABEL_H
            select(Rect(r.x, y, r.w, CONTROL_H), templates(), template, key = "new-rank-template")?.let { template = it }
            y + CONTROL_H - r.y
        }
    }

    private fun Ui.suggestions(r: Rect, y: Int, pick: (RankSeed) -> Unit): Int {
        val government = ClaimsStore.info?.government ?: return 0
        if (government.ranks != "ladder") return Draw.paragraph(g, tr("kami_claims.ranks.new.flat", FLAT_RANK_CAP), r.x, y, r.w) + 4
        val seeds = Presets.of(wireEnum(government.preset, PresetId.CUSTOM)).ladder
        fieldLabel(Rect(r.x, y, r.w, 9), tr("kami_claims.ranks.new.suggestions"))
        val stack = Stack(r.x, y + LABEL_H, r.w, 3)
        chipFlow(stack, seeds.map { ChipSpec(it.name, Palette.opaque(it.color)) }, "new-rank-seed")?.let { pick(seeds[it]) }
        return stack.bottom + 1 - y
    }

    private fun templates() = listOf(Option(BLANK, tr("kami_claims.template.blank"), null, tr("kami_claims.template.blank.desc"))) +
        RoleTemplate.entries.map { Option(it.key, tr("kami_claims.template.${it.key}"), null, tr("kami_claims.template.${it.key}.desc")) }

    fun delete(app: ClaimsApp, info: Info, line: RankLine) {
        val mine = ClaimsStore.myRank?.position ?: return
        val choices = info.ranks.filter { it.id != line.id && it.position > mine }
        var target = info.ranks.getOrNull(line.position + 1)?.id ?: choices.firstOrNull()?.id ?: return
        val holders = info.members.filter { it.rank == line.id }
        val items = listOf(
            Consequence(tr("kami_claims.ranks.delete.members", holders.size), Severity.WARNING),
            Consequence(tr("kami_claims.ranks.delete.preview", holders.take(MAX_LINES).joinToString { it.name }.ifEmpty { "-" }))
        )
        app.open(Dialog(tr("kami_claims.ranks.delete.title", line.name), null, Icons.REMOVE, DialogKind.DESTRUCTIVE, DELETE_W) { s ->
            var y = s.body.y + consequences(s.body.x, s.body.y, s.body.w, items) + 4
            fieldLabel(Rect(s.body.x, y, s.body.w, 9), tr("kami_claims.ranks.delete.target")); y += LABEL_H
            select(Rect(s.body.x, y, s.body.w, CONTROL_H), choices.map { Option(it.id, it.name) }, target, key = "delete-target")?.let { target = it }
            s.used = y + CONTROL_H + 4 - s.body.y
            dialogButtons(s, tr("kami_claims.ranks.delete.action"), primaryStyle = ButtonStyle.DANGER) {
                ClaimsStore.send("rank_delete", line.id, target, key = "ranks:delete")
                s.close()
            }
        })
    }

    fun assign(app: ClaimsApp, info: Info, member: Mem, to: RankLine) {
        val from = info.rankLine(member.rank) ?: return
        val before = held(from)
        val after = held(to)
        val up = to.position < from.position
        val changes = (after - before).map { Consequence(tr("kami_claims.citizens.rank.gains", Vocabulary.perm(it).label), Severity.WARNING) } + (before - after).map { Consequence(tr("kami_claims.citizens.rank.loses", Vocabulary.perm(it).label)) }
        val items = changes.take(MAX_LINES) + listOfNotNull(changes.size.takeIf { it > MAX_LINES }?.let { Consequence(tr("kami_claims.ranks.more", it - MAX_LINES)) }) +
            listOfNotNull(Consequence(tr("kami_claims.ranks.bracket_delay")).takeIf { info.government?.taxes == "brackets" })
        Dialogs.confirm(app, tr(if (up) "kami_claims.citizens.promote.confirm.title" else "kami_claims.citizens.demote.confirm.title", member.name, to.name), null, Icons.STAR,
            items, tr(if (up) "kami_claims.citizens.promote" else "kami_claims.citizens.demote"), "rank_assign", arrayOf(member.id, to.id))
    }

    private fun held(line: RankLine): Set<Perm> = if (line.id == PRESIDENT) Perm.entries.toSet() else permsOf(line.perms)
}
