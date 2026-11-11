package kami.claims.client.admin

import kami.claims.admin.AdminNews
import kami.claims.press.PressSettings
import kami.claims.admin.NewsFull
import kami.libs.ui.core.Flow
import kami.libs.ui.core.Rect
import kami.libs.ui.core.Ui
import kami.libs.ui.press.Article
import kami.libs.ui.press.EditorState
import kami.libs.ui.press.markupEditor
import kami.libs.ui.style.Draw
import kami.libs.ui.style.Palette
import kami.libs.ui.text.tr
import kami.libs.ui.widget.CONTROL_H
import kami.libs.ui.widget.TextState
import kami.libs.ui.widget.textField

internal object PressForm {
    const val BODY_LIMIT = 8000
    const val TITLE_CAP = 200
    const val SUBTITLE_CAP = 300

    fun problem(title: String, subtitle: String, body: String, cfg: PressSettings): String? = when {
        title.isBlank() || body.isBlank() -> "kami_claims.news.error.empty"
        title.trim().length > cfg.titleMax || subtitle.trim().length > cfg.subtitleMax || body.trim().length > minOf(cfg.bodyMax, BODY_LIMIT) -> "kami_claims.admin.press.too_long"
        else -> null
    }
}

internal class PressEditor(private val withAuthor: Boolean) {
    val title = TextState()
    val subtitle = TextState()
    val author = TextState()
    val body = EditorState("", PressForm.BODY_LIMIT)

    private val cfg get() = PressAdminSection.settings.run { data ?: latest } ?: PressSettings()

    fun load(a: NewsFull) {
        title.set(a.title)
        subtitle.set(a.subtitle)
        body.set(a.body)
    }

    fun clear() {
        title.set("")
        subtitle.set("")
        author.set("")
        body.set("")
    }

    fun problem(): String? = PressForm.problem(title.text, subtitle.text, body.text, cfg)?.let { tr(it) }

    fun draw(ui: Ui, r: Rect, key: String) {
        PressAdminSection.settings.poll()
        val f = Flow(r, 4)
        ui.textField(f.take(CONTROL_H), title, tr("kami_claims.admin.press.title_hint"), maxLength = PressForm.TITLE_CAP, key = "$key:title")
        ui.textField(f.take(CONTROL_H), subtitle, tr("kami_claims.admin.press.subtitle_hint"), maxLength = PressForm.SUBTITLE_CAP, key = "$key:subtitle")
        if (withAuthor) ui.textField(f.take(CONTROL_H), author, tr("kami_claims.admin.press.author_hint", AdminNews.DEFAULT_AUTHOR), maxLength = AdminNews.AUTHOR_MAX, key = "$key:author")
        val limits = cfg
        ui.markupEditor(f.remaining(), body, "$key:body", article = { Article(title.text.trim(), subtitle.text.trim(), it) }, statusHeight = 10) { s ->
            val over = body.text.trim().length > minOf(limits.bodyMax, PressForm.BODY_LIMIT)
            Draw.text(g, tr("kami_claims.admin.press.chars", body.text.trim().length, minOf(limits.bodyMax, PressForm.BODY_LIMIT)), s.x, s.y, if (over) Palette.danger else Palette.textMuted)
        }
    }
}
