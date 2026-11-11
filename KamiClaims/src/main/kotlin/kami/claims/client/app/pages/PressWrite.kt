package kami.claims.client.app.pages

import kami.claims.client.ClientClaims
import kami.claims.client.app.ClaimsApp
import kami.claims.client.store.ClientNews
import kami.claims.net.DraftDto
import kami.claims.net.InfoDto
import kami.claims.net.ResultDto
import kami.claims.client.app.pages.PressReqs.strip
import kami.claims.press.PressRules
import kami.libs.press.Cost
import net.minecraft.client.Minecraft
import kami.libs.ui.app.Consequence
import kami.libs.ui.app.Dialog
import kami.libs.ui.app.DialogKind
import kami.libs.ui.app.confirmDialog
import kami.libs.ui.app.consequences
import kami.libs.ui.app.dialogButtons
import kami.libs.ui.app.leaveDialog
import kami.libs.ui.core.Flow
import kami.libs.ui.core.Rect
import kami.libs.ui.core.Row
import kami.libs.ui.core.Ui
import kami.libs.ui.press.Article
import kami.libs.ui.press.EditorState
import kami.libs.ui.press.Inserter
import kami.libs.ui.press.Newsprint
import kami.libs.ui.press.markupEditor
import kami.libs.ui.style.Draw
import kami.libs.ui.style.Format
import kami.libs.ui.style.Icon
import kami.libs.ui.style.Icons
import kami.libs.ui.style.Palette
import kami.libs.ui.style.Severity
import kami.libs.ui.style.TextStyle
import kami.libs.ui.text.tr
import kami.libs.ui.text.trJson
import kami.libs.ui.widget.ButtonStyle
import kami.libs.ui.widget.CONTROL_H
import kami.libs.ui.widget.SCROLL_GUTTER
import kami.libs.ui.widget.TextState
import kami.libs.ui.widget.button
import kami.libs.ui.widget.buttonWidth
import kami.libs.ui.widget.card
import kami.libs.ui.widget.chip
import kami.libs.ui.widget.emptyState
import kami.libs.ui.widget.fieldLabel
import kami.libs.ui.widget.fitText
import kami.libs.ui.widget.iconButton
import kami.libs.ui.widget.noticeBar
import kami.libs.ui.widget.scroll
import kami.libs.ui.widget.skeleton
import kami.libs.ui.widget.statusPill
import kami.libs.ui.widget.textField
import kami.libs.ui.widget.toggle

class PressWrite(private val app: ClaimsApp, private val published: () -> Unit) {
    private data class Content(val title: String = "", val subtitle: String = "", val body: String = "")
    private class Pill(val label: String, val color: Int)

    private var openId = 0L
    private var stale: DraftDto? = null
    private var loaded = false
    private var title = TextState()
    private var subtitle = TextState()
    private var editor = EditorState()
    private var saved = Content()
    private var seen = Content()
    private var sent: Content? = null
    private var sentId = 0L
    private var conflict: Content? = null
    private var publishing = false
    private var bodyEditor: EditorState? = null
    private var bodyVersion = -1
    private var bodyText = ""
    private var costKey: Content? = null
    private var costInfo: InfoDto? = null
    private var costValue: Cost? = null
    private var sentAuto = false
    private var blocked = false
    private var changedAt = 0L
    private var lastSave = 0L
    private var savedAt = 0L
    private var beat = 0L
    private var creating = false

    val editing get() = openId != 0L
    private val draft get() = ClientNews.draft?.takeIf { it.id == openId && it !== stale }
    private val readOnly get() = draft?.lock == OTHER
    private val frozen get() = readOnly || conflict != null || !ClientNews.info.canWrite
    val dirty get() = loaded && !readOnly && current() != saved

    private fun body(): String {
        val v = editor.area.buffer.version
        if (bodyEditor !== editor || bodyVersion != v) { bodyEditor = editor; bodyVersion = v; bodyText = editor.text }
        return bodyText
    }

    private fun current() = Content(title.text, subtitle.text, body())

    private fun cost(): Cost {
        val c = current()
        val info = ClientNews.info
        if (c != costKey || info !== costInfo) {
            costKey = c
            costInfo = info
            costValue = ClientNews.cost(c.title, c.subtitle, c.body)
        }
        return costValue!!
    }

    private fun open(id: Long) {
        openId = id
        loaded = false
        blocked = false
        conflict = null
        publishing = false
        stale = ClientNews.draft
        editor = EditorState()
        title = TextState()
        subtitle = TextState()
        ClientNews.requestDraft(id)
    }

    private fun clear() {
        openId = 0
        loaded = false
        conflict = null
        publishing = false
    }

    fun leave() {
        if (!editing) return
        if (loaded && !readOnly) ClientNews.release(openId)
        clear()
    }

    private fun apply(c: Content) {
        title.set(c.title)
        subtitle.set(c.subtitle)
        editor.set(c.body)
    }

    fun save(auto: Boolean = false) {
        val c = current()
        sent = c
        sentId = openId
        sentAuto = auto
        lastSave = System.currentTimeMillis()
        ClientNews.save(openId, c.title, c.subtitle, c.body, auto)
    }

    fun result(r: ResultDto) {
        if (r.name == "news_draft_new") creating = false
        if (r.name == "news_draft_save") {
            if (sentId == openId && editing) {
                if (r.ok) { saved = sent ?: saved; savedAt = System.currentTimeMillis() } else if (sentAuto) blocked = true
            }
            sent = null
        }
        if (r.name == "news_publish" || !r.ok) publishing = false
        if (!r.ok) {
            if (app.isOpen) app.toast(Severity.DANGER, tr("kami_claims.press.failed"), trJson(r.message))
            return
        }
        when (r.name) {
            "news_draft_new" -> if (app.isOpen && r.id != 0L) open(r.id)
            "news_publish" -> {
                clear()
                if (app.isOpen) app.toast(Severity.SUCCESS, trJson(r.message))
                published()
            }
            "news_draft_delete" -> if (app.isOpen) app.toast(Severity.SUCCESS, trJson(r.message))
        }
    }

    fun draw(ui: Ui, r: Rect) {
        ClientNews.poll(openId)
        if (!editing) return list(ui, r)
        val d = draft
        if (d == null || !loaded && !ClientNews.infoLoaded) return waiting(ui, r)
        if (!loaded) {
            saved = Content(d.title, d.subtitle, d.body)
            seen = saved
            editor = EditorState("", ClientNews.info.bodyMax)
            apply(saved)
            loaded = true
            beat = System.currentTimeMillis()
            if (d.lock != OTHER) ClientNews.lock(openId)
        }
        tick(d)
        edit(ui, r, d)
    }

    private fun waiting(ui: Ui, r: Rect) {
        val f = Flow(r, 4)
        val back = tr("kami_libs.common.back")
        if (ui.button(Rect(r.x, f.take(CONTROL_H).y, buttonWidth(back, Icons.BACK), CONTROL_H), back, Icons.BACK, key = "press-back")) leave()
        val area = f.remaining()
        if (loaded) ui.emptyState(area, tr("kami_claims.press.draft.gone"), tr("kami_claims.press.draft.gone.desc")) else ui.skeleton(area.top(120))
    }

    private fun clip(c: Content) {
        Minecraft.getInstance()?.keyboardHandler?.clipboard = listOf(c.title, c.subtitle, c.body).filter { it.isNotBlank() }.joinToString("\n\n")
    }

    private fun tick(d: DraftDto) {
        val now = System.currentTimeMillis()
        val info = ClientNews.info
        if (d.lock == OTHER) {
            val server = Content(d.title, d.subtitle, d.body)
            val cur = current()
            when {
                server == cur -> { saved = server; seen = server; conflict = null }
                cur == saved -> { saved = server; seen = server; apply(server); conflict = null }
                else -> conflict = server
            }
            return
        }
        conflict = null
        if (now - beat > info.lockMinutes * LOCK_BEAT_MS) { beat = now; ClientNews.lock(openId) }
        val cur = current()
        if (cur != seen) { seen = cur; changedAt = now; blocked = false }
        if (ClientClaims.prefs.pressAutosave && cur != saved && !blocked && sent == null && now - changedAt >= DEBOUNCE_MS && now - lastSave >= info.autosaveMinSeconds * 1000L) save(true)
    }

    private fun pills(ui: Ui, r: Rect, pills: List<Pill>, draw: Boolean): Int {
        var x = r.x
        var y = r.y
        pills.forEachIndexed { i, p ->
            val w = Draw.width(p.label) + 10
            if (x + w > r.right && x > r.x) { x = r.x; y += PILL_STEP }
            if (draw) ui.chip(x, y, p.label, p.color, key = "press-pill:$i", maxWidth = r.w)
            x += w + 3
        }
        return y + PILL_H - r.y
    }

    private fun statusColor(ok: Boolean) = if (ok) Palette.success else Palette.danger

    private fun list(ui: Ui, r: Rect) {
        val info = ClientNews.info
        val f = Flow(r, 4)
        val summary = listOf(
            Pill(tr("kami_claims.press.slots", info.drafts, info.slots), statusColor(info.drafts < info.slots)),
            Pill(tr("kami_claims.press.published", info.published, info.cap), statusColor(info.published < info.cap)),
            Pill(tr("kami_claims.press.fee", Format.money(info.fee)), Palette.textSecondary)
        )
        pills(ui, f.take(pills(ui, f.rest, summary, false)), summary, true)
        val label = tr("kami_claims.press.new")
        if (ui.button(Rect(r.x, f.take(CONTROL_H).y, buttonWidth(label, Icons.ADD), CONTROL_H), label, Icons.ADD, ButtonStyle.PRIMARY, info.drafts < info.slots, tr("kami_claims.press.new.full", info.slots), pending = creating, key = "press-new")) {
            creating = true
            ClientNews.newDraft()
        }
        val area = f.remaining()
        val drafts = ClientNews.drafts
        if (drafts.isEmpty()) {
            if (info.drafts > 0) repeat(2) { ui.skeleton(Rect(area.x, area.y + it * (ROW_H + 4), area.w - SCROLL_GUTTER, ROW_H)) }
            else ui.emptyState(area, tr("kami_claims.press.drafts.empty"), tr("kami_claims.press.drafts.empty.desc"))
            return
        }
        ui.scroll("press-drafts", area, drafts.size * (ROW_H + 4)) { c ->
            drafts.forEachIndexed { i, d -> row(ui, Rect(c.x, c.y + i * (ROW_H + 4), area.w - SCROLL_GUTTER, ROW_H), d) }
        }
    }

    private fun row(ui: Ui, box: Rect, d: DraftDto) {
        val info = ClientNews.info
        val inner = ui.card(box, key = "press-draft:${d.id}")
        val line = Row(inner, 4)
        val delete = line.takeFromRight(CONTROL_H)
        if (ui.iconButton(Rect(delete.x, inner.centerY - CONTROL_H / 2, CONTROL_H, CONTROL_H), Icons.REMOVE, tr("kami_claims.press.delete"), info.canPublish, disabledReason = tr("kami_claims.press.delete.perm"), key = "press-del:${d.id}")) {
            confirmDialog(app::open, tr("kami_claims.press.delete.title"), d.title.ifBlank { tr("kami_claims.press.untitled") }, Icons.REMOVE, listOf(Consequence(tr("kami_claims.press.delete.body"), Severity.DANGER)), tr("kami_claims.press.delete"), danger = true) { ClientNews.delete(d.id) }
        }
        val openLabel = tr(if (d.lock == OTHER) "kami_libs.common.view" else "kami_libs.common.open")
        val button = line.takeFromRight(buttonWidth(openLabel, Icons.EDIT))
        if (ui.button(Rect(button.x, inner.centerY - CONTROL_H / 2, button.w, CONTROL_H), openLabel, Icons.EDIT, key = "press-open:${d.id}")) open(d.id)
        val text = line.rest
        val name = d.title.ifBlank { tr("kami_claims.press.untitled") }
        val used = Draw.width(name, TextStyle.HEADING).coerceAtMost(text.w)
        ui.fitText(text.x, text.y, text.w, name, if (d.title.isBlank()) Palette.textMuted else Palette.text, style = TextStyle.HEADING, key = "press-name:${d.id}")
        when (d.lock) {
            MINE -> ui.statusPill(text.x + used + 6, text.y - 1, tr("kami_claims.press.lock.mine"), Severity.SUCCESS, key = "press-lock:${d.id}")
            OTHER -> ui.statusPill(text.x + used + 6, text.y - 1, tr("kami_claims.press.lock.other", d.holder?.name.orEmpty(), Format.duration(d.leftMs)), Severity.WARNING, key = "press-lock:${d.id}")
        }
        val who = d.contributors.joinToString(", ") { it.name }.ifEmpty { "-" }
        ui.fitText(text.x, text.y + 12, text.w, tr("kami_claims.press.draft.meta", who, Format.ago(d.updated), d.chars), Palette.textMuted, key = "press-meta:${d.id}")
    }

    private fun reqCells(need: Map<String, Int>): List<PressReqs.Cell> {
        val info = ClientNews.info
        return PressReqs.cells(need, info.have, info.fee, info.treasury, info.published, info.cap)
    }

    private fun publishReason(need: Map<String, Int>): String? {
        val info = ClientNews.info
        if (!info.canPublish) return tr("kami_claims.press.publish.perm")
        val reasons = listOfNotNull(
            tr("kami_claims.press.publish.empty").takeIf { title.text.isBlank() || editor.text.isBlank() },
            tr("kami_claims.press.publish.cap").takeIf { info.published >= info.cap },
            tr("kami_claims.press.publish.fee").takeIf { info.treasury < info.fee },
            PressRules.missing(need, info.have).takeIf { it.isNotEmpty() }?.let { m -> tr("kami_claims.press.publish.materials", m.entries.joinToString(", ") { "${it.value} x ${PressModel.itemName(it.key)}" }) }
        )
        return reasons.takeIf { it.isNotEmpty() }?.joinToString("; ")
    }

    private fun confirmPublish(need: Map<String, Int>) {
        val cells = reqCells(need).filter { it.kind != PressReqs.Kind.CAP }
        val notice = listOf(Consequence(tr("kami_claims.press.confirm.public"), Severity.WARNING))
        app.open(Dialog(tr("kami_claims.press.confirm.title"), title.text, Icons.SCROLL, DialogKind.CONFIRM, 320) { s ->
            var y = s.body.y
            if (cells.isNotEmpty()) {
                fieldLabel(Rect(s.body.x, y, s.body.w, 9), tr("kami_claims.press.confirm.uses"))
                y += 12 + strip(s.body.x, y + 12, s.body.w, cells, "press-confirm") + 6
            }
            y += consequences(s.body.x, y, s.body.w, notice) + 4
            s.used = y - s.body.y
            dialogButtons(s, tr("kami_claims.press.publish")) {
                if (!publishing) {
                    publishing = true
                    if (dirty) save()
                    ClientNews.publish(openId)
                }
                s.close()
            }
        })
    }

    private fun back() {
        if (!dirty) return leave()
        app.open(leaveDialog({ save(); leave() }, null, ::leave))
    }

    private fun edit(ui: Ui, r: Rect, d: DraftDto) {
        val info = ClientNews.info
        val locked = d.lock == OTHER
        val need = PressRules.need(cost())
        val f = Flow(r, 4)
        val head = Row(f.take(CONTROL_H), 4)
        val backLabel = tr("kami_libs.common.back")
        if (ui.button(head.take(buttonWidth(backLabel, Icons.BACK)), backLabel, Icons.BACK, key = "press-back")) back()
        if (locked) {
            val label = tr("kami_claims.press.takeover")
            if (ui.button(head.takeFromRight(buttonWidth(label, Icons.HAND)), label, Icons.HAND, ButtonStyle.PRIMARY, d.takeover, tr("kami_claims.press.takeover.wait"), key = "press-takeover")) {
                ClientNews.takeover(openId, d.takeover)
                ClientNews.poll(openId, force = true)
            }
        } else {
            val reason = publishReason(need)
            val publish = tr("kami_claims.press.publish")
            if (ui.button(head.takeFromRight(buttonWidth(publish, Icons.SCROLL)), publish, Icons.SCROLL, ButtonStyle.PRIMARY, reason == null, reason, pending = publishing, key = "press-publish")) confirmPublish(need)
            val saveLabel = tr("kami_claims.press.save")
            if (ui.button(head.takeFromRight(buttonWidth(saveLabel, Icons.SAVE)), saveLabel, Icons.SAVE, enabled = dirty && sent == null, disabledReason = tr("kami_claims.press.save.clean"), key = "press-save")) save()
        }
        val who = d.contributors.joinToString(", ") { it.name }.ifEmpty { "-" }
        ui.fitText(head.rest.x, head.rest.y + 5, head.rest.w, tr("kami_claims.press.authors", who), Palette.textMuted, key = "press-authors")
        if (locked) ui.noticeBar(f.take(18), Icons.LOCK, tr("kami_claims.press.locked", d.holder?.name.orEmpty(), Format.duration(d.leftMs)), Severity.WARNING, key = "press-locked")
        conflict?.let { server ->
            ui.noticeBar(f.take(18), Icons.WARNING, tr("kami_claims.press.conflict"), Severity.DANGER, key = "press-conflict")
            val row = Row(f.take(CONTROL_H), 4)
            val copy = tr("kami_claims.press.conflict.copy")
            if (ui.button(row.take(buttonWidth(copy, Icons.COPY)), copy, Icons.COPY, key = "press-conflict-copy")) clip(current())
            val load = tr("kami_claims.press.conflict.load")
            if (ui.button(row.take(buttonWidth(load, Icons.CHECK)), load, Icons.CHECK, key = "press-conflict-load")) {
                saved = server
                seen = server
                conflict = null
                apply(server)
            }
        }
        ui.fieldLabel(f.take(9), tr("kami_claims.press.field.title"), "${title.text.length}/${info.titleMax}")
        ui.textField(f.take(CONTROL_H), title, tr("kami_claims.press.field.title"), enabled = !locked, maxLength = info.titleMax, key = "press-title")
        ui.fieldLabel(f.take(9), tr("kami_claims.press.field.subtitle"), "${subtitle.text.length}/${info.subtitleMax}")
        ui.textField(f.take(CONTROL_H), subtitle, tr("kami_claims.press.field.subtitle"), enabled = !locked, maxLength = info.subtitleMax, key = "press-subtitle")
        val cells = reqCells(need)
        val stripH = PressReqs.measure(cells, r.w)
        val area = f.remaining()
        fun insert(label: String, icon: Icon, open: ((String) -> Unit) -> Unit) = Inserter(label, icon) { put -> open { token -> if (!frozen) put(token) } }
        val inserts = listOf(
            insert(tr("kami_claims.press.insert.price"), Icons.COIN) { put -> PressPickers.item(app, tr("kami_claims.press.insert.price")) { put("{price:$it}") } },
            insert(tr("kami_claims.press.insert.item"), Icons.CHEST) { put -> PressPickers.item(app, tr("kami_claims.press.insert.item")) { put("{item:$it}") } },
            insert(tr("kami_claims.press.insert.flag"), Icons.FLAG) { put -> PressPickers.countries(app, tr("kami_claims.press.insert.flag"), false, emptySet()) { ids -> ids.firstOrNull()?.let { put("{flag:$it}") } } },
            insert(tr("kami_claims.press.insert.head"), Icons.PERSON) { put -> PressPickers.player(app) { put("{head:$it}") } },
            insert(tr("kami_claims.press.insert.icon"), Icons.STAR) { put -> PressPickers.icon(app) { put("{icon:$it}") } }
        )
        val news = PressModel.data(d.refs)
        ui.markupEditor(
            area.dropBottom(stripH + 4), editor, "press-editor", readOnly = locked, inserts = inserts,
            colorHint = { if (it.free) null else tr("kami_claims.press.color.hint", info.cost.dyeChars) },
            article = { doc -> Article(title.text, subtitle.text, doc, Newsprint.signature(Format.exact(System.currentTimeMillis()), info.country.name, d.contributors.map { it.name }), news) },
            statusHeight = STATUS_H, data = news
        ) { s -> status(s, locked) }
        ui.strip(r.x, area.bottom - stripH, r.w, cells, "press-req")
    }

    private fun Ui.status(s: Rect, locked: Boolean) {
        val info = ClientNews.info
        val count = "${editor.text.length}/${editor.limit}"
        Draw.text(g, count, s.x, s.y + 4, Palette.textMuted)
        val box = Rect(s.right - TOGGLE_W, s.y, TOGGLE_W, 14)
        toggle(box, ClientClaims.prefs.pressAutosave, tr("kami_claims.press.autosave"), enabled = !locked, tip = tr("kami_claims.press.autosave.tooltip", info.autosaveMinSeconds), key = "press-autosave")?.let {
            ClientClaims.prefs.pressAutosave = it
            ClientClaims.savePrefs()
        }
        val text = when {
            locked -> ""
            sent != null -> tr("kami_claims.press.saving")
            dirty -> tr("kami_claims.press.unsaved")
            savedAt > 0 -> tr("kami_claims.press.saved", Format.ago(savedAt))
            else -> ""
        }
        fitText(s.x + Draw.width(count) + 12, s.y + 4, (box.x - s.x - Draw.width(count) - 18).coerceAtLeast(0), text, if (dirty && sent == null) Palette.warning else Palette.textMuted, key = "press-saved")
    }

    private companion object {
        const val MINE = "mine"
        const val OTHER = "other"
        const val ROW_H = 34
        const val PILL_H = 13
        const val PILL_STEP = 15
        const val STATUS_H = 16
        const val TOGGLE_W = 100
        const val DEBOUNCE_MS = 3000L
        const val LOCK_BEAT_MS = 20_000L
    }
}
