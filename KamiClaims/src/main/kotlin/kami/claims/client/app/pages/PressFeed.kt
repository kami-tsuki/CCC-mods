package kami.claims.client.app.pages

import kami.claims.client.app.ClaimsApp
import kami.claims.client.store.ClientNews
import kami.claims.client.store.FeedFilter
import kami.claims.net.ArticleDto
import kami.claims.net.FeedItem
import kami.claims.press.ArticleKind
import kami.claims.press.Corrections
import kami.libs.ui.app.TimeRange
import kami.libs.ui.core.Cursor
import kami.libs.ui.core.Rect
import kami.libs.ui.core.Row
import kami.libs.ui.core.Stack
import kami.libs.ui.core.Ui
import kami.libs.ui.press.Article
import kami.libs.ui.press.Newsprint
import kami.libs.ui.press.newsprintView
import kami.libs.ui.style.Draw
import kami.libs.ui.style.Icons
import kami.libs.ui.style.Palette
import kami.libs.ui.style.Severity
import kami.libs.ui.text.tr
import kami.libs.ui.widget.CONTROL_H
import kami.libs.ui.widget.ChipSpec
import kami.libs.ui.widget.SCROLL_GUTTER
import kami.libs.ui.widget.button
import kami.libs.ui.widget.buttonWidth
import kami.libs.ui.widget.chipFlow
import kami.libs.ui.widget.emptyState
import kami.libs.ui.widget.noticeBar
import kami.libs.ui.widget.scroll
import kami.libs.ui.widget.skeleton

class PressFeed(private val app: ClaimsApp) {
    private val range = TimeRange("press-range")
    private val kinds = LinkedHashSet<ArticleKind>()
    private val countries = LinkedHashSet<String>()
    private var applied = FeedFilter()
    private var shown = 0L
    private var built: Pair<ArticleDto, Article>? = null
    private val cards = HashMap<Long, Article>()
    private val heights = HashMap<Long, Int>()
    private var width = 0

    fun reload() {
        cards.clear()
        heights.clear()
        ClientNews.requestFeed(applied)
    }

    fun open(id: Long) {
        shown = id
        ClientNews.requestArticle(id)
    }

    fun draw(ui: Ui, r: Rect) {
        if (shown != 0L) return article(ui, r)
        val head = Newsprint.mastheadHeight(r.w, tr("kami_claims.press.masthead"), tr("kami_claims.press.tagline"))
        Newsprint.masthead(ui.g, r.top(head), tr("kami_claims.press.masthead"), tr("kami_claims.press.tagline"))
        val bar = Rect(r.x, r.y + head + 6, r.w, CONTROL_H)
        val row = Row(bar, 6)
        range.draw(ui, row.take(range.width(r.w < 420)))
        val label = if (countries.isEmpty()) tr("kami_claims.press.filter.all_countries") else tr("kami_claims.press.filter.countries", countries.size)
        if (ui.button(row.take(buttonWidth(label, Icons.FLAG)), label, Icons.FLAG, key = "press-countries")) {
            PressPickers.countries(app, tr("kami_claims.press.filter.pick"), true, countries.toSet()) { countries.clear(); countries += it }
        }
        val chips = ui.chipFlow(Stack(r.x, bar.bottom + 4, r.w), ArticleKind.entries.map {
            ChipSpec(tr("kami_claims.press.kind.${it.name.lowercase()}"), Palette.textSecondary, selected = it in kinds)
        }, "press-kind")
        chips?.let { ArticleKind.entries[it].let { k -> if (!kinds.remove(k)) kinds += k } }
        val next = FeedFilter(kinds.toSet(), countries.toSet(), range.from ?: 0)
        if (next != applied) {
            applied = next
            reload()
        }
        list(ui, Rect(r.x, bar.bottom + 4 + 13 + 6, r.w, (r.bottom - bar.bottom - 23).coerceAtLeast(0)))
    }

    private fun height(item: FeedItem, w: Int) = heights.getOrPut(item.id) { Newsprint.height(card(item), w) }

    private fun card(item: FeedItem): Article {
        if (cards.size >= MAX_CARDS) cards.clear()
        return cards.getOrPut(item.id) { PressModel.card(item) }
    }

    private fun list(ui: Ui, r: Rect) {
        val w = r.w - SCROLL_GUTTER
        if (w != width) { width = w; heights.clear() }
        val feed = ClientNews.feed
        val loading = ClientNews.feedLoading
        if (feed.isEmpty()) {
            if (loading) repeat(3) { ui.skeleton(Rect(r.x, r.y + it * (SKELETON_H + GAP), w, SKELETON_H)) }
            else ui.emptyState(r, tr("kami_claims.press.empty"), tr("kami_claims.press.empty.desc"))
            return
        }
        val total = feed.sumOf { height(it, w) + GAP } + if (loading || ClientNews.more) SKELETON_H else 0
        ui.scroll("press-feed", r, total) { c ->
            var y = c.y
            feed.forEach { item ->
                val box = Rect(c.x, y, w, height(item, w))
                val view = box.intersect(r)
                if (!view.isEmpty) {
                    Newsprint.draw(ui, box, card(item), "press-card:${item.id}")
                    if (ui.hovering(view)) {
                        ui.cursor = Cursor.HAND
                        Draw.outline(ui.g, box, Palette.brass)
                    }
                    if (ui.pressed(view) != null) open(item.id)
                }
                y += box.h + GAP
            }
            if (loading || ClientNews.more) {
                ui.skeleton(Rect(c.x, y, w, SKELETON_H))
                if (!loading && y < r.bottom + SKELETON_H) ClientNews.requestFeed(page = true)
            }
        }
    }

    private fun article(ui: Ui, r: Rect) {
        ui.onEscape(20) { shown = 0 }
        val back = tr("kami_libs.common.back")
        if (ui.button(Rect(r.x, r.y, buttonWidth(back, Icons.BACK), CONTROL_H), back, Icons.BACK, key = "press-back")) shown = 0
        var area = r.dropTop(CONTROL_H + 4)
        val dto = ClientNews.article?.takeIf { it.id == shown }
        if (dto == null) {
            if (ClientNews.articleLoading) ui.skeleton(area.top(120)) else ui.emptyState(area, tr("kami_claims.press.missing"), tr("kami_claims.press.missing.desc"))
            return
        }
        if (dto.corrected.isNotBlank()) {
            ui.noticeBar(area.top(18), Icons.WARNING, tr("kami_claims.press.corrected", Corrections.date(dto.corrected)), Severity.WARNING, key = "press-corrected")
            area = area.dropTop(22)
        }
        val view = built?.takeIf { it.first === dto }?.second ?: PressModel.article(dto).also { built = dto to it }
        ui.newsprintView(area, view, "press-article:${dto.id}")
    }

    private companion object {
        const val GAP = 6
        const val SKELETON_H = 60
        const val MAX_CARDS = 100
    }
}
