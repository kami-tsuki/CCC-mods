package kami.claims.press

import kami.claims.Config
import kami.claims.Realm
import kami.claims.discord.CountryChannels
import kami.claims.service.Announce
import kami.claims.social.Mail
import kami.libs.discord.DiscordApi
import kami.libs.discord.DiscordText
import kami.libs.discord.Embed
import kami.libs.press.Block
import kami.libs.press.Doc
import kami.libs.press.Markup
import kami.libs.press.Span
import kami.libs.text.Phrase

object NewsDelivery {
    private const val QUEUE_MAX = 20
    private class Pending(val id: Long, var embeds: List<Embed>)

    private val queue = ArrayDeque<Pending>()

    fun deliver(article: Article) {
        if (Config.s.broadcast) {
            val title = Phrase.of("kami_claims.news.headline", article.country.ifEmpty { "-" }, article.headline())
            Announce.server?.playerList?.broadcastSystemMessage(Mail.chat.info(title.component()), false)
        }
        val system = article.templateKey.isNotEmpty()
        val title = if (system) DiscordText.phrase(article.headline()) else article.title
        val body = resolve(article, system)
        val announced = system && Announce.posts(article.templateKey.substringBefore('.'))
        if (Config.s.press.countryRoom && !announced && article.involved.size <= 1) article.countryId?.let(Realm::live)?.let { CountryChannels.post(it, NewsFormat.room(title, article.subtitle, body), NewsFormat.COLOR, card = true) }
        if (Config.s.press.newsRoom && DiscordApi.newsConfigured) enqueue(article.id, embeds(article))
    }

    fun update(article: Article) {
        if (!Config.s.press.newsRoom) return
        val embeds = embeds(article)
        if (synchronized(queue) { queue.firstOrNull { it.id == article.id }?.also { it.embeds = embeds } } != null) return
        val ids = ids(article)
        if (ids.isEmpty()) { if (DiscordApi.newsConfigured) enqueue(article.id, embeds); return }
        DiscordApi.editNews(ids, embeds) { NewsStore.setDiscord(article.id, it.map(Long::toString)) }
    }

    fun retract(article: Article) {
        synchronized(queue) { queue.removeAll { it.id == article.id } }
        val ids = ids(article)
        if (ids.isEmpty()) return
        DiscordApi.deleteNews(ids)
        NewsStore.setDiscord(article.id, emptyList())
    }

    fun flush() {
        if (!DiscordApi.newsOn) return
        val batch = synchronized(queue) { queue.toList().also { queue.clear() } }
        batch.forEach { p -> DiscordApi.news(p.embeds) { NewsStore.setDiscord(p.id, it.map(Long::toString)) } }
    }

    private fun enqueue(id: Long, embeds: List<Embed>) {
        synchronized(queue) {
            queue += Pending(id, embeds)
            while (queue.size > QUEUE_MAX) queue.removeFirst()
        }
        flush()
    }

    private fun ids(article: Article) = (NewsStore.article(article.id)?.discord ?: article.discord).mapNotNull(String::toLongOrNull)

    private fun embeds(article: Article): List<Embed> {
        val system = article.templateKey.isNotEmpty()
        val title = if (system) DiscordText.phrase(article.headline()) else article.title
        val footer = if (system) "System" else listOf(article.country).filter { it.isNotEmpty() }.plus(article.authors.joinToString(", ") { it.name }).joinToString(" · ")
        return NewsFormat.embeds(if (system) title else NewsFormat.plain(title), article.subtitle, resolve(article, system), footer, article.created)
    }

    private fun resolve(article: Article, system: Boolean): Doc {
        val doc = NewsFormat.resolve(Markup.parse(article.body), article.refs)
        if (!system) return doc
        val key = "kami_claims.news.sys.${article.templateKey}.body"
        val phrase = if (article.templateKey in News.BULK) Phrase.of(key) else Phrase.of(key, *article.templateArgs.toTypedArray())
        return Doc(listOf(Block.Para(listOf(Span(DiscordText.phrase(phrase))))) + doc.blocks)
    }
}
