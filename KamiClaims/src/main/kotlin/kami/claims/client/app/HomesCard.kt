package kami.claims.client.app

import kami.claims.net.HomeLine
import kami.libs.ui.core.Memo
import kami.libs.ui.core.Rect
import kami.libs.ui.core.Ui
import kami.libs.ui.style.Draw
import kami.libs.ui.style.Format
import kami.libs.ui.style.Icons
import kami.libs.ui.style.Palette
import kami.libs.ui.style.Severity
import kami.libs.ui.style.TextStyle
import kami.libs.ui.text.tr
import kami.libs.ui.widget.card
import kami.libs.ui.widget.iconButton
import kami.libs.ui.widget.scroll
import kami.libs.ui.widget.statusPill

class HomesCard(private val app: ClaimsApp) {
    private val memo = Memo()

    private class Line(
        val home: HomeLine, val title: String, val pill: String?, val severity: Severity, val detail: String, val detailColor: Int,
        val pillKey: String, val mapKey: String, val releaseKey: String
    )

    private class Built(val lines: List<Line>, val hint: String?, val count: String, val cardKey: String, val scrollKey: String)

    private fun built(homes: List<HomeLine>, key: String): Built = memo.of(homes, Format.locale, System.currentTimeMillis() / 60_000) {
        val lines = homes.sortedBy { if (it.state == Tenure.MOVING) 0 else 1 }.mapIndexed { i, h ->
            val moving = h.state == Tenure.MOVING
            val pill = when {
                h.state == "active" && h.debt > 0 -> tr("kami_claims.home.debt", Format.money(h.debt))
                h.state == "active" -> null
                else -> Tenure.label(h.state)
            }
            Line(
                h, tr("kami_claims.home.where", h.country, h.x, h.z), pill, Tenure.severity(h.state, h.debt),
                Tenure.note(h, h.state, h.until, 0).ifEmpty { Format.perDay(Format.money(h.rent.toLong())) },
                if (moving) Palette.danger else Palette.textSecondary,
                "$key:pill:$i", "$key:map:$i", "$key:release:$i"
            )
        }
        val hint = homes.firstOrNull { it.state == Tenure.MOVING }?.let { Tenure.hint(it.state) } ?: homes.firstOrNull { it.state == "removed" }?.let { Tenure.hint(it.state) }
        Built(lines, hint, Format.number(homes.size), "$key:card", "$key:scroll")
    }

    fun height(homes: List<HomeLine>): Int {
        if (homes.isEmpty()) return EMPTY_H
        val hint = if (homes.any { it.state == Tenure.MOVING || it.state == "removed" }) HINT_H else 0
        return HEAD_H + hint + minOf(homes.size, MAX_ROWS) * ROW_H + 4
    }

    fun draw(ui: Ui, r: Rect, homes: List<HomeLine>, key: String) {
        val v = built(homes, key)
        val danger = homes.any { it.state == Tenure.MOVING }
        val body = ui.card(r, tr("kami_claims.home.title"), Icons.HOUSE, if (danger) Severity.DANGER else null, trailing = if (homes.isEmpty()) null else v.count, key = v.cardKey)
        if (homes.isEmpty()) {
            Draw.text(ui.g, Draw.fit(tr("kami_claims.home.empty"), body.w), body.x, body.y + 2, Palette.textMuted)
            return
        }
        var list = body
        v.hint?.let {
            Draw.text(ui.g, Draw.fit(it, body.w), body.x, body.y + 1, if (danger) Palette.danger else Palette.warning)
            list = body.dropTop(HINT_H)
        }
        ui.scroll(v.scrollKey, list, v.lines.size * ROW_H) { area ->
            v.lines.forEachIndexed { i, l -> row(ui, Rect(area.x, area.y + i * ROW_H, area.w, ROW_H - 2), l) }
        }
    }

    private fun row(ui: Ui, row: Rect, l: Line) {
        val release = Rect(row.right - ICON_BTN, row.y + (row.h - ICON_BTN) / 2, ICON_BTN, ICON_BTN)
        val map = Rect(release.x - ICON_BTN - 2, release.y, ICON_BTN, ICON_BTN)
        if (ui.iconButton(release, Icons.REMOVE, tr("kami_claims.home.release"), key = l.releaseKey)) release(l.home)
        if (ui.iconButton(map, Icons.MAP, tr("kami_claims.nav.map"), key = l.mapKey)) app.openMapAt(l.home.x, l.home.z)
        val textW = map.x - row.x - 8
        Draw.text(ui.g, Draw.fit(l.title, textW), row.x + 4, row.y + 2, TextStyle.HEADING)
        var x = row.x + 4
        l.pill?.let { x += ui.statusPill(x, row.y + 12, it, l.severity, key = l.pillKey) + 4 }
        Draw.text(ui.g, Draw.fit(l.detail, row.x + textW - x), x, row.y + 15, l.detailColor)
    }

    private fun release(h: HomeLine) = Dialogs.release(app, h.x, h.z, h.country, h.state == Tenure.MOVING)
}

private const val HEAD_H = 22
private const val ROW_H = 28
private const val HINT_H = 12
private const val EMPTY_H = 40
private const val MAX_ROWS = 3
private const val ICON_BTN = 16
