package kami.claims.client.admin

import kami.claims.admin.NewsRequests
import kami.claims.press.PressSettings
import kami.claims.press.ReportSchedule
import kami.libs.config.Configs
import kami.libs.ui.app.KamiApp
import kami.libs.ui.app.Page
import kami.libs.ui.app.adminAct
import kami.libs.ui.app.adminManager
import kami.libs.ui.app.adminPending
import kami.libs.ui.app.lockUnless
import kami.libs.ui.app.reasonDialog
import kami.libs.ui.app.remoteFailed
import kami.libs.ui.core.Flow
import kami.libs.ui.core.Rect
import kami.libs.ui.core.Row
import kami.libs.ui.core.Ui
import kami.libs.ui.style.Format
import kami.libs.ui.style.Icon
import kami.libs.ui.style.Icons
import kami.libs.ui.style.Palette
import kami.libs.ui.text.tr
import kami.libs.ui.widget.ButtonStyle
import kami.libs.ui.widget.CARD_HEADER_H
import kami.libs.ui.widget.CONTROL_H
import kami.libs.ui.widget.NumberState
import kami.libs.ui.widget.Option
import kami.libs.ui.widget.TextState
import kami.libs.ui.widget.card
import kami.libs.ui.widget.edgeButton
import kami.libs.ui.widget.fieldHelp
import kami.libs.ui.widget.fitText
import kami.libs.ui.widget.numberField
import kami.libs.ui.widget.scroll
import kami.libs.ui.widget.select
import kami.libs.ui.widget.textField
import kami.libs.ui.widget.toggle

private class Num(val id: String, val min: Long, val max: Long, val get: (PressSettings) -> Long, val set: (PressSettings, Long) -> PressSettings)

private val GENERAL = listOf(
    Num("dailyCap", 0, PressSettings.MAX_CAP.toLong(), { it.dailyCap.toLong() }) { s, v -> s.copy(dailyCap = v.toInt()) },
    Num("draftSlots", 1, 50, { it.draftSlots.toLong() }) { s, v -> s.copy(draftSlots = v.toInt()) },
    Num("lockMinutes", 1, 120, { it.lockMinutes.toLong() }) { s, v -> s.copy(lockMinutes = v.toInt()) },
    Num("autosaveMinSeconds", 1, 300, { it.autosaveMinSeconds.toLong() }) { s, v -> s.copy(autosaveMinSeconds = v.toInt()) },
    Num("feeSpur", 0, PressSettings.MAX_FEE, { it.feeSpur }) { s, v -> s.copy(feeSpur = v) },
    Num("retentionDays", 7, 3650, { it.retentionDays.toLong() }) { s, v -> s.copy(retentionDays = v.toInt()) },
    Num("titleMax", 10, 200, { it.titleMax.toLong() }) { s, v -> s.copy(titleMax = v.toInt()) },
    Num("subtitleMax", 0, 300, { it.subtitleMax.toLong() }) { s, v -> s.copy(subtitleMax = v.toInt()) },
    Num("bodyMax", 100, 20_000, { it.bodyMax.toLong() }) { s, v -> s.copy(bodyMax = v.toInt()) }
)

private val COST = listOf(
    Num("cost.basePaper", 0, 100, { it.cost.basePaper.toLong() }) { s, v -> s.copy(cost = s.cost.copy(basePaper = v.toInt())) },
    Num("cost.baseInk", 0, 100, { it.cost.baseInk.toLong() }) { s, v -> s.copy(cost = s.cost.copy(baseInk = v.toInt())) },
    Num("cost.includedChars", 0, 20_000, { it.cost.includedChars.toLong() }) { s, v -> s.copy(cost = s.cost.copy(includedChars = v.toInt())) },
    Num("cost.subtitlePaper", 0, 100, { it.cost.subtitlePaper.toLong() }) { s, v -> s.copy(cost = s.cost.copy(subtitlePaper = v.toInt())) },
    Num("cost.subtitleInk", 0, 100, { it.cost.subtitleInk.toLong() }) { s, v -> s.copy(cost = s.cost.copy(subtitleInk = v.toInt())) },
    Num("cost.extraChars", 1, 20_000, { it.cost.extraChars.toLong() }) { s, v -> s.copy(cost = s.cost.copy(extraChars = v.toInt())) },
    Num("cost.extraPaper", 0, 100, { it.cost.extraPaper.toLong() }) { s, v -> s.copy(cost = s.cost.copy(extraPaper = v.toInt())) },
    Num("cost.extraInk", 0, 100, { it.cost.extraInk.toLong() }) { s, v -> s.copy(cost = s.cost.copy(extraInk = v.toInt())) },
    Num("cost.inkPerElement", 0, 100, { it.cost.inkPerElement.toLong() }) { s, v -> s.copy(cost = s.cost.copy(inkPerElement = v.toInt())) },
    Num("cost.inkPerStyle", 0, 100, { it.cost.inkPerStyle.toLong() }) { s, v -> s.copy(cost = s.cost.copy(inkPerStyle = v.toInt())) },
    Num("cost.dyeChars", 1, 20_000, { it.cost.dyeChars.toLong() }) { s, v -> s.copy(cost = s.cost.copy(dyeChars = v.toInt())) },
    Num("cost.dyePerRef", 0, 100, { it.cost.dyePerRef.toLong() }) { s, v -> s.copy(cost = s.cost.copy(dyePerRef = v.toInt())) }
)

private val SYSTEM = listOf(
    Num("systemPerHour", 1, 100, { it.systemPerHour.toLong() }) { s, v -> s.copy(systemPerHour = v.toInt()) },
    Num("warUpdatesPerWar", 0, PressSettings.MAX_CAP.toLong(), { it.warUpdatesPerWar.toLong() }) { s, v -> s.copy(warUpdatesPerWar = v.toInt()) },
    Num("warUpdatesPerDay", 0, PressSettings.MAX_CAP.toLong(), { it.warUpdatesPerDay.toLong() }) { s, v -> s.copy(warUpdatesPerDay = v.toInt()) }
)

private const val GAP = 6
private const val LINE_GAP = 3
private const val FIELD_W = 120
private const val HELP_H = 12
private const val CAP_BUTTON = 70

internal object PressTimes {
    fun parse(text: String): List<String> = text.split(',').map { it.trim() }.filter { it.isNotEmpty() }

    fun problem(text: String): String? = PressSettings.badTime(parse(text))
}

internal class PressSettingsPage(private val app: KamiApp) : Page() {
    private val remote = PressAdminSection.settings
    private var loaded: PressSettings? = null
    private var draft: PressSettings? = null
    private val numbers = HashMap<String, NumberState>()
    private val market = TextState()
    private val ranking = TextState()
    private var capCountry: String? = null
    private val capValue = NumberState(1)
    private var contentH = 0

    override val title get() = tr("kami_claims.admin.press.settings")

    private fun reset(s: PressSettings) {
        draft = s
        market.set(s.marketReport.times.joinToString(", "))
        ranking.set(s.rankingReport.times.joinToString(", "))
    }

    override fun draw(ui: Ui, r: Rect) {
        remote.poll()
        PressAdminSection.names.poll()
        remote.data?.takeIf { it != loaded }?.let { server ->
            val clean = draft == null || draft == loaded || draft?.copy(rev = server.rev) == server
            loaded = server
            if (clean) reset(server)
        }
        val d = draft
        if (d == null) {
            if (remote.error != null && !remote.loading) ui.remoteFailed(r, remote, "press-settings")
            return
        }
        val dirty = d != loaded
        val stale = d.rev != loaded?.rev
        val timesError = PressTimes.problem(market.text) ?: PressTimes.problem(ranking.text)
        val bar = Row(r.top(CONTROL_H), GAP)
        val why = lockUnless(adminManager) ?: tr("kami_claims.admin.press.stale").takeIf { stale } ?: tr("kami_claims.admin.press.no_changes").takeIf { !dirty } ?: timesError?.let { tr("kami_claims.admin.press.bad_time_field", it) }
        val json = Configs.json().encodeToString(PressSettings.serializer(), d)
        if (ui.edgeButton(bar, tr("kami_libs.common.save"), Icons.EDIT, enabled = why == null, disabledReason = why, pending = adminPending(NewsRequests.SETTINGS_SAVE, listOf(json)), key = "press-set-save"))
            reasonDialog({ app.open(it) }, tr("kami_claims.admin.press.settings.title")) { reason, _, _ -> app.adminAct(NewsRequests.SETTINGS_SAVE, listOf(json), reason) }
        if (ui.edgeButton(bar, tr("kami_claims.admin.press.discard"), Icons.CLOSE, enabled = dirty, disabledReason = tr("kami_claims.admin.press.no_changes"), key = "press-set-discard")) loaded?.let(::reset)
        if (stale) ui.fitText(bar.rest.x, bar.rest.y + (CONTROL_H - 8) / 2, bar.rest.w, tr("kami_claims.admin.press.stale"), Palette.danger, key = "press-stale")
        else if (dirty) ui.fitText(bar.rest.x, bar.rest.y + (CONTROL_H - 8) / 2, bar.rest.w, tr("kami_claims.admin.press.unsaved"), Palette.warning, key = "press-unsaved")
        val area = r.dropTop(CONTROL_H + GAP)
        ui.scroll("press-settings", area, contentH) { c ->
            val f = Flow(Rect(c.x, c.y, c.w, Int.MAX_VALUE / 2), GAP)
            body(ui, f, d, adminManager)
            contentH = f.rest.y - c.y
        }
    }

    private fun group(ui: Ui, f: Flow, title: String, icon: Icon, lines: Int, extra: Int = 0, draw: (Flow) -> Unit) {
        val box = ui.card(f.take(CARD_HEADER_H + 8 + lines * (CONTROL_H + LINE_GAP) + extra), title, icon, key = "press-group:$title")
        draw(Flow(box, LINE_GAP))
    }

    private fun body(ui: Ui, f: Flow, d: PressSettings, enabled: Boolean) {
        group(ui, f, tr("kami_claims.admin.press.group.limits"), Icons.GEAR, GENERAL.size) { g -> GENERAL.forEach { ui.number(g, it, d, enabled) } }
        group(ui, f, tr("kami_claims.admin.press.group.cost"), Icons.COIN, COST.size) { g -> COST.forEach { ui.number(g, it, d, enabled) } }
        val events = PressSettings.EVENTS.keys.toList()
        group(ui, f, tr("kami_claims.admin.press.group.system"), Icons.SCROLL, SYSTEM.size + (events.size + 1) / 2 + 1) { g ->
            SYSTEM.forEach { ui.number(g, it, d, enabled) }
            ui.flag(g.take(CONTROL_H), tr("kami_claims.admin.press.f.digest"), d.digest, enabled, "digest")?.let { draft = d.copy(digest = it) }
            events.chunked(2).forEach { pair ->
                val cells = g.take(CONTROL_H).columns(2, GAP)
                pair.forEachIndexed { i, key ->
                    ui.flag(cells[i], tr("kami_claims.admin.press.event.$key"), d.events[key] ?: true, enabled, "event:$key")?.let { draft = d.copy(events = d.events + (key to it)) }
                }
            }
        }
        group(ui, f, tr("kami_claims.admin.press.group.reports"), Icons.STATS, 2 * REPORT_LINES, extra = 2 * (HELP_H + LINE_GAP)) { g ->
            report(ui, g, "market", d.marketReport, market, enabled) { s -> d.copy(marketReport = s) }
            report(ui, g, "ranking", d.rankingReport, ranking, enabled) { s -> d.copy(rankingReport = s) }
        }
        group(ui, f, tr("kami_claims.admin.press.group.rooms"), Icons.CHAIN, 2) { g ->
            ui.flag(g.take(CONTROL_H), tr("kami_claims.admin.press.f.newsRoom"), d.newsRoom, enabled, "newsRoom")?.let { draft = d.copy(newsRoom = it) }
            ui.flag(g.take(CONTROL_H), tr("kami_claims.admin.press.f.countryRoom"), d.countryRoom, enabled, "countryRoom")?.let { draft = d.copy(countryRoom = it) }
        }
        caps(ui, f, d, enabled)
    }

    private fun Ui.number(g: Flow, n: Num, d: PressSettings, enabled: Boolean) {
        val row = Row(g.take(CONTROL_H), GAP)
        val field = row.takeFromRight(FIELD_W)
        val label = tr("kami_claims.admin.press.f.${n.id}")
        fitText(row.rest.x, row.rest.y + (CONTROL_H - 8) / 2, row.rest.w, label, Palette.textSecondary, key = "press-label:${n.id}")
        val state = numbers.getOrPut(n.id) { NumberState(n.get(d)) }.also { it.sync(n.get(d)) }
        numberField(field, state, n.min, n.max, enabled = enabled, key = "press-num:${n.id}")?.let { draft = n.set(d, it) }
    }

    private fun Ui.flag(r: Rect, label: String, on: Boolean, enabled: Boolean, key: String): Boolean? =
        toggle(r, on, label, enabled, tr("kami_libs.admin.need_manage").takeIf { !enabled }, key = "press-flag:$key")

    private fun report(ui: Ui, g: Flow, name: String, schedule: ReportSchedule, text: TextState, enabled: Boolean, update: (ReportSchedule) -> PressSettings) {
        val head = Row(g.take(CONTROL_H), GAP)
        val run = tr("kami_claims.admin.press.run")
        if (ui.edgeButton(head, run, Icons.SCROLL, enabled = enabled, disabledReason = tr("kami_libs.admin.need_manage"), pending = adminPending(NewsRequests.REPORT, listOf(name)), key = "press-run:$name"))
            reasonDialog({ app.open(it) }, tr("kami_claims.admin.press.run.title", tr("kami_claims.admin.press.report.$name"))) { reason, _, _ -> app.adminAct(NewsRequests.REPORT, listOf(name), reason) }
        ui.flag(head.remaining(), tr("kami_claims.admin.press.report.$name"), schedule.enabled, enabled, "report:$name")?.let { draft = update(schedule.copy(enabled = it)) }
        val error = PressTimes.problem(text.text)
        text.error = error?.let { tr("kami_claims.admin.press.bad_time_field", it) }
        text.touched = error != null
        val res = ui.textField(g.take(CONTROL_H), text, tr("kami_claims.admin.press.times_hint"), enabled = enabled, maxLength = TIMES_MAX, key = "press-times:$name")
        if (res.changed && error == null) draft = update(schedule.copy(times = PressTimes.parse(text.text)))
        ui.fieldHelp(g.take(HELP_H), text, tr("kami_claims.admin.press.times_help"))
    }

    private fun caps(ui: Ui, f: Flow, d: PressSettings, enabled: Boolean) {
        val names = PressAdminSection.names.run { data ?: latest }.orEmpty()
        group(ui, f, tr("kami_claims.admin.press.group.caps"), Icons.FLAG, d.perCountryCap.size + 1) { g ->
            d.perCountryCap.entries.sortedBy { names[it.key] ?: it.key }.forEach { (id, cap) ->
                val row = Row(g.take(CONTROL_H), GAP)
                if (ui.edgeButton(row, tr("kami_claims.admin.press.cap.remove"), Icons.REMOVE, ButtonStyle.DANGER, enabled, tr("kami_libs.admin.need_manage"), key = "press-cap-remove:$id"))
                    draft = d.copy(perCountryCap = d.perCountryCap - id)
                ui.fitText(row.rest.x, row.rest.y + (CONTROL_H - 8) / 2, row.rest.w, "${names[id] ?: id}: ${Format.number(cap.toLong())}", Palette.text, key = "press-cap:$id")
            }
            val row = Row(g.take(CONTROL_H), GAP)
            val add = row.takeFromRight(CAP_BUTTON)
            val value = row.takeFromRight(FIELD_W)
            val options = names.entries.filter { it.key !in d.perCountryCap }.sortedBy { it.value.lowercase() }.map { Option(it.key, it.value) }
            ui.select(row.rest, options, capCountry, tr("kami_claims.admin.press.cap.pick"), enabled, key = "press-cap-pick")?.let { capCountry = it }
            ui.numberField(value, capValue, 0, PressSettings.MAX_CAP.toLong(), enabled = enabled, key = "press-cap-value")?.let { capValue.commit(it) }
            val pick = capCountry?.takeIf { it in names }
            if (ui.edgeButton(add, tr("kami_claims.admin.press.cap.set"), Icons.ADD, enabled = enabled && pick != null, disabledReason = if (enabled) tr("kami_claims.admin.press.cap.pick") else tr("kami_libs.admin.need_manage"), key = "press-cap-set")) {
                draft = d.copy(perCountryCap = d.perCountryCap + (pick!! to capValue.value.toInt()))
                capCountry = null
            }
        }
    }

    private companion object {
        const val REPORT_LINES = 2
        const val TIMES_MAX = 120
    }
}
