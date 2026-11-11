package kami.claims.client.store

import kami.claims.net.ArticleDto
import kami.claims.net.DraftDto
import kami.claims.net.DraftsDto
import kami.claims.net.FeedDto
import kami.claims.net.FeedItem
import kami.claims.net.InfoDto
import kami.claims.net.PressDto
import kami.claims.net.ResultDto
import kami.claims.press.ArticleKind
import kami.libs.press.Cost
import kami.libs.press.Markup
import kami.libs.press.PressCost

data class FeedFilter(val kinds: Set<ArticleKind> = emptySet(), val countries: Set<String> = emptySet(), val since: Long = 0)

object ClientNews {
    private const val POLL_MS = 3000L
    private const val MAX_FEED = 400
    private const val MAX_DRAFTS = 50

    var feed: List<FeedItem> = emptyList()
        private set
    var more = false
        private set
    var feedLoading = false
        private set
    var article: ArticleDto? = null
        private set
    var articleLoading = false
        private set
    var drafts: List<DraftDto> = emptyList()
        private set
    var draft: DraftDto? = null
        private set
    var focus = 0L
        private set
    var info = InfoDto()
        private set
    var rev = 0
        private set
    var infoLoaded = false
        private set

    private var filter = FeedFilter()
    private var tag = 0
    private var polled = 0L
    private val listeners = ArrayList<() -> Unit>()
    private val results = ArrayList<(ResultDto) -> Unit>()

    fun listen(listener: () -> Unit): () -> Unit {
        listeners += listener
        return { listeners -= listener }
    }

    fun onResult(listener: (ResultDto) -> Unit): () -> Unit {
        results += listener
        return { results -= listener }
    }

    fun cost(title: String, subtitle: String, body: String): Cost = PressCost.of(title, subtitle, Markup.parse(body), info.cost)

    fun requestInfo() = ClaimsStore.quiet("news_info")

    fun requestFeed(next: FeedFilter = filter, page: Boolean = false) {
        if (!page || next != filter) {
            filter = next
            tag++
            feed = emptyList()
            more = false
        }
        feedLoading = true
        val before = if (page) feed.lastOrNull()?.id ?: 0 else 0
        ClaimsStore.quiet("news_feed", before.toString(), PressDto.FEED_MAX.toString(), next.kinds.joinToString(",") { it.name }, next.countries.joinToString(","), next.since.toString(), tag.toString())
        changed()
    }

    fun requestArticle(id: Long) {
        articleLoading = true
        article = null
        ClaimsStore.quiet("news_article", id.toString())
        changed()
    }

    fun requestDrafts() = ClaimsStore.quiet("news_drafts")

    fun requestDraft(id: Long) = ClaimsStore.quiet("news_draft", id.toString())

    fun poll(open: Long = 0, force: Boolean = false) {
        val t = System.currentTimeMillis()
        if (!force && t - polled < POLL_MS) return
        polled = t
        requestInfo()
        requestDrafts()
        if (open != 0L) requestDraft(open)
    }

    fun newDraft() = ClaimsStore.quiet("news_draft_new")

    fun save(id: Long, title: String, subtitle: String, body: String, auto: Boolean) =
        ClaimsStore.quiet("news_draft_save", id.toString(), title, subtitle, if (auto) "1" else "0", *PressDto.split(body).toTypedArray())

    fun lock(id: Long) = ClaimsStore.quiet("news_draft_lock", id.toString())

    fun takeover(id: Long, force: Boolean) = ClaimsStore.quiet("news_draft_takeover", id.toString(), if (force) "1" else "0")

    fun release(id: Long) = ClaimsStore.quiet("news_draft_release", id.toString())

    fun delete(id: Long) = ClaimsStore.quiet("news_draft_delete", id.toString())

    fun publish(id: Long) = ClaimsStore.quiet("news_publish", id.toString())

    fun receive(kind: String, json: String) {
        when (kind) {
            "info" -> {
                info = PressDto.decode(InfoDto.serializer(), json, InfoDto())
                infoLoaded = true
            }
            "feed" -> feedPage(PressDto.decode(FeedDto.serializer(), json, FeedDto(tag = -1)))
            "article" -> {
                articleLoading = false
                article = PressDto.decode(ArticleDto.serializer(), json, ArticleDto()).takeIf { it.id != 0L }
            }
            "drafts" -> PressDto.decode(DraftsDto.serializer(), json, DraftsDto()).let { d ->
                drafts = d.drafts.take(MAX_DRAFTS)
                if (d.focus != 0L) focus = d.focus
            }
            "draft" -> draft = PressDto.decode(DraftDto.serializer(), json, DraftDto()).takeIf { it.id != 0L }
            "result" -> PressDto.decode(ResultDto.serializer(), json, ResultDto(ok = false)).let { r -> results.toList().forEach { it(r) } }
            else -> return
        }
        changed()
    }

    private fun feedPage(dto: FeedDto) {
        if (dto.tag == -1) {
            feedLoading = false
            more = false
            return
        }
        if (dto.tag != tag) return
        feedLoading = false
        val seen = feed.mapTo(HashSet()) { it.id }
        feed = (feed + dto.items.take(PressDto.FEED_MAX).filter { it.id !in seen }).take(MAX_FEED)
        more = dto.more && feed.size < MAX_FEED
    }

    fun clear() {
        feed = emptyList()
        more = false
        feedLoading = false
        article = null
        articleLoading = false
        drafts = emptyList()
        draft = null
        focus = 0
        info = InfoDto()
        infoLoaded = false
        filter = FeedFilter()
        tag++
        polled = 0
        changed()
    }

    private fun changed() {
        rev++
        listeners.toList().forEach { it() }
    }
}
