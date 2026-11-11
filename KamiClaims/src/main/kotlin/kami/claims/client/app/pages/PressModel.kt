package kami.claims.client.app.pages

import kami.claims.client.store.ClaimsStore
import kami.claims.client.store.ClientNews
import kami.claims.net.ArticleDto
import kami.claims.net.FeedItem
import kami.claims.net.PressCountry
import kami.claims.press.Author
import kami.claims.press.RefSnap
import kami.libs.mc.ItemSpec
import kami.libs.press.Block
import kami.libs.press.Doc
import kami.libs.press.Markup
import kami.libs.press.Span
import kami.libs.ui.press.Article
import kami.libs.ui.press.FlagLook
import kami.libs.ui.press.NewsData
import kami.libs.ui.press.Newsprint
import kami.libs.ui.press.PriceStamp
import kami.libs.ui.style.Format
import kami.libs.ui.text.Translations
import kami.libs.ui.text.tr
import java.util.Locale

object PressModel {
    fun countries(): List<PressCountry> {
        val seen = ClientNews.feed.map { it.country } + ClientNews.article?.let { listOf(it.country) + it.involved }.orEmpty() + ClientNews.info.country
        val claimed = ClaimsStore.countries.filter { it.id.isNotEmpty() }.map { PressCountry(it.id, it.name, it.color, it.flag.pattern, it.flag.emblem, it.flag.secondary) }
        return (seen + claimed).filter { c -> c.id.isNotEmpty() && c.id.none(Char::isWhitespace) }.distinctBy { it.id }.sortedBy { it.name.lowercase() }
    }

    fun look(c: PressCountry) = FlagLook(c.color, c.pattern, c.emblem, c.secondary)

    fun itemName(id: String) = ItemSpec.stack(id).takeIf { !it.isEmpty }?.hoverName?.string ?: id

    private fun system(key: String, part: String, args: List<String>, fallback: String): String {
        if (key.isEmpty()) return fallback
        val k = "kami_claims.news.sys.$key.$part"
        return if (Translations.lookup(k) != null) tr(k, *args.toTypedArray()) else fallback
    }

    private fun signature(created: Long, country: PressCountry, authors: List<Author>) =
        Newsprint.signature(Format.exact(created), country.name, authors.map { it.name })

    fun headline(i: FeedItem) = system(i.templateKey, "title", i.templateArgs, i.title)

    fun card(i: FeedItem) = Article(headline(i), i.subtitle, Doc(emptyList()), signature(i.created, i.country, i.authors))

    private class Memo(val refs: List<RefSnap>, val locale: Locale, val claimed: List<*>, val data: NewsData)

    private var memo: Memo? = null

    fun norm(key: String) = key.lowercase().removePrefix("minecraft:")

    fun data(refs: List<RefSnap>): NewsData {
        val locale = Format.locale
        val claimed = ClaimsStore.countries
        memo?.takeIf { it.refs === refs && it.locale == locale && it.claimed === claimed }?.let { return it.data }
        val flags = HashMap<String, FlagLook>()
        countries().forEach { flags[norm(it.id)] = look(it) }
        refs.filter { it.kind == "flag" }.forEach { flags[norm(it.key)] = FlagLook(it.color, it.pattern, it.emblem, it.secondary) }
        val prices = refs.filter { it.kind == "price" }.associate { norm(it.key) to PriceStamp(if (it.value > 0) Format.money(it.value) else "-", if (it.at > 0) Format.exact(it.at) else "") }
        val heads = refs.filter { it.kind == "head" && it.name.isNotBlank() }.associate { norm(it.key) to it.name }
        val names = HashMap<String, String>()
        countries().forEach { names[norm(it.id)] = it.name }
        refs.filter { it.kind == "flag" && it.name.isNotBlank() }.forEach { names[norm(it.key)] = it.name }
        return NewsData(flag = { flags[norm(it)] }, price = { prices[norm(it)] }, head = { heads[norm(it)] }, country = { names[norm(it)] }).also { memo = Memo(refs, locale, claimed, it) }
    }

    fun article(a: ArticleDto): Article {
        val lead = system(a.templateKey, "body", a.templateArgs, "").takeIf { it.isNotBlank() }?.let { listOf(Block.Para(listOf(Span(it)))) }.orEmpty()
        return Article(
            system(a.templateKey, "title", a.templateArgs, a.title), a.subtitle, Doc(lead + Markup.parse(a.body).blocks),
            signature(a.created, a.country, a.authors), data(a.refs)
        )
    }
}
