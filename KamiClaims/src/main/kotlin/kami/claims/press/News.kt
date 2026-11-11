package kami.claims.press

import kami.claims.Config
import kami.claims.Country
import kami.claims.LedgerKind
import kami.claims.KamiClaims
import kami.claims.Names
import kami.claims.Realm
import kami.claims.economy.Treasury
import kami.claims.gov.Perm
import kami.claims.now
import kami.claims.service.Announce
import kami.claims.service.Fail
import kami.claims.service.Service
import kami.libs.admin.Players
import kami.libs.db.SavePoint
import kami.libs.economy.MarketApi
import kami.libs.press.Doc
import kami.libs.press.Markup
import kami.libs.press.PressCost
import kami.libs.press.Ref
import kami.libs.text.Phrase
import kami.libs.util.Time
import kami.libs.util.toUuidOrNull
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.item.ItemStack
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.min

object News {
    const val MAX_REFS = 40
    val BULK = setOf("digest", "marketReport", "rankingReport")
    private val icon = Regex("^[a-z0-9_]{1,32}$")
    private val lock = Any()
    private val articleSeq = AtomicLong()
    private val draftSeq = AtomicLong()
    private val cfg get() = Config.s.press

    private fun nextArticle() = articleSeq.updateAndGet { maxOf(it, NewsStore.maxArticle()) + 1 }

    private fun nextDraft() = draftSeq.updateAndGet { maxOf(it, NewsStore.maxDraft()) + 1 }

    private fun refsOf(body: String): Pair<Doc, List<Ref>> {
        val doc = Markup.parse(body)
        return doc to Markup.refs(doc).also { if (it.size > MAX_REFS) throw Fail("kami_claims.news.error.refs", MAX_REFS) }
    }

    fun forget(countryId: String) = synchronized(lock) { NewsStore.deleteDraftsOf(listOf(countryId)) }

    fun list(before: Long = Long.MAX_VALUE, limit: Int = 30, kinds: Set<ArticleKind> = emptySet(), countries: Set<String> = emptySet(), since: Long = 0): List<Article> =
        NewsStore.list(before, limit.coerceIn(1, 100), kinds, countries, since)

    fun article(id: Long): Article? = NewsStore.article(id)

    fun drafts(p: ServerPlayer): List<Draft> = NewsStore.drafts(Service.need(p, Perm.NEWS_WRITE).id)

    private fun draftOf(p: ServerPlayer, id: Long, perm: Perm): Pair<Country, Draft> {
        val c = Service.need(p, perm)
        return c to (NewsStore.draft(id)?.takeIf { it.countryId == c.id } ?: throw Fail("kami_claims.news.error.draft_missing"))
    }

    private fun holder(d: Draft) = Names.of(Announce.server, d.lockHolder.orEmpty())

    fun create(p: ServerPlayer): Draft = synchronized(lock) {
        val c = Service.need(p, Perm.NEWS_WRITE)
        if (PressRules.slotsFull(NewsStore.draftCount(c.id), cfg.draftSlots)) throw Fail("kami_claims.news.error.slots", cfg.draftSlots)
        val t = now()
        Draft(nextDraft(), c.id, "", "", "", emptyList(), p.stringUUID, PressRules.lockExpiry(t, cfg.lockMinutes), t, t).also(NewsStore::save)
    }

    fun lock(p: ServerPlayer, id: Long): Draft = synchronized(lock) {
        val (_, d) = draftOf(p, id, Perm.NEWS_WRITE)
        val t = now()
        val state = PressRules.lockState(d.lockHolder, d.lockUntil, p.stringUUID, t)
        if (state == PressRules.Lock.OTHER) throw Fail("kami_claims.news.error.locked", holder(d))
        d.copy(lockHolder = p.stringUUID, lockUntil = PressRules.lockExpiry(t, cfg.lockMinutes), lockSince = if (state == PressRules.Lock.MINE) d.lockSince else t).also(NewsStore::save)
    }

    fun takeover(p: ServerPlayer, id: Long, force: Boolean): Draft = synchronized(lock) {
        val (_, d) = draftOf(p, id, Perm.NEWS_WRITE)
        val t = now()
        val state = PressRules.lockState(d.lockHolder, d.lockUntil, p.stringUUID, t)
        if (!PressRules.mayTakeOver(state, PressRules.savedSinceLock(d.updated, d.lockSince), force)) throw Fail("kami_claims.news.error.takeover", holder(d))
        d.copy(lockHolder = p.stringUUID, lockUntil = PressRules.lockExpiry(t, cfg.lockMinutes), lockSince = if (state == PressRules.Lock.MINE) d.lockSince else t).also(NewsStore::save)
    }

    fun release(p: ServerPlayer, id: Long) {
        synchronized(lock) {
            val (_, d) = draftOf(p, id, Perm.NEWS_WRITE)
            if (PressRules.lockState(d.lockHolder, d.lockUntil, p.stringUUID, now()) == PressRules.Lock.MINE) NewsStore.save(d.copy(lockHolder = null, lockUntil = 0))
        }
    }

    fun save(p: ServerPlayer, id: Long, title: String, subtitle: String, body: String, auto: Boolean): Draft = synchronized(lock) {
        val (_, d) = draftOf(p, id, Perm.NEWS_WRITE)
        val t = now()
        val state = PressRules.lockState(d.lockHolder, d.lockUntil, p.stringUUID, t)
        if (state == PressRules.Lock.OTHER) throw Fail("kami_claims.news.error.lock_lost")
        if (!PressRules.autosaveAllowed(d.updated, t, cfg.autosaveMinSeconds, auto)) throw Fail("kami_claims.news.error.autosave")
        val text = Markup.clean(body, cfg.bodyMax)
        refsOf(text)
        val next = d.copy(
            title = Markup.cleanLine(title, cfg.titleMax), subtitle = Markup.cleanLine(subtitle, cfg.subtitleMax), body = text,
            lockHolder = p.stringUUID, lockUntil = PressRules.lockExpiry(t, cfg.lockMinutes), lockSince = if (state == PressRules.Lock.MINE) d.lockSince else t, updated = t
        )
        val changed = next.title != d.title || next.subtitle != d.subtitle || next.body != d.body
        next.copy(contributors = PressRules.contributors(d.contributors, p.stringUUID, changed)).also(NewsStore::save)
    }

    fun delete(p: ServerPlayer, id: Long) = synchronized(lock) {
        NewsStore.deleteDraft(draftOf(p, id, Perm.NEWS_PUBLISH).second.id)
    }

    fun publish(p: ServerPlayer, id: Long): Phrase = synchronized(lock) {
        val (c, d) = draftOf(p, id, Perm.NEWS_PUBLISH)
        val t = now()
        if (PressRules.lockState(d.lockHolder, d.lockUntil, p.stringUUID, t) == PressRules.Lock.OTHER) throw Fail("kami_claims.news.error.locked", holder(d))
        val title = Markup.cleanLine(d.title, cfg.titleMax)
        val subtitle = Markup.cleanLine(d.subtitle, cfg.subtitleMax)
        val body = Markup.clean(d.body, cfg.bodyMax)
        if (title.isEmpty() || body.isEmpty()) throw Fail("kami_claims.news.error.empty")
        val (doc, found) = refsOf(body)
        val need = PressRules.need(PressCost.of(title, subtitle, doc, cfg.cost))
        val short = PressRules.missing(need, carried(p))
        if (short.isNotEmpty()) throw Fail("kami_claims.news.error.materials", short.entries.joinToString { "${it.value}x ${itemName(it.key)}" })
        val fee = cfg.feeSpur
        if (c.treasury < fee) throw Fail("kami_claims.news.error.fee", fee)
        val dayStart = t - t % Time.DAY_MS
        if (PressRules.capReached(NewsStore.published(c.id, dayStart), PressRules.capFor(cfg, c.id))) throw Fail("kami_claims.news.error.cap")
        val refs = found.map { snapshot(it, t) }
        val authors = PressRules.authors(d.contributors, p.stringUUID).map { Author(it, Names.of(p.server, it)) }
        val article = Article(nextArticle(), ArticleKind.COUNTRY, c.id, c.name, listOf(c.id), title, subtitle, body, authors, refs, "", emptyList(), t)
        val charged = if (fee > 0) Treasury.bank(c, LedgerKind.FEE, -fee, p.stringUUID, "press") else 0L
        if (fee > 0 && charged == 0L) throw Fail("kami_claims.news.error.fee", fee)
        need.forEach { (item, n) -> take(p, item, n) }
        p.inventory.setChanged()
        try {
            NewsStore.insert(article)
            NewsStore.deleteDraft(d.id)
        } catch (e: Exception) {
            KamiClaims.LOG.error("Publishing article {} failed, rolling back", article.id, e)
            runCatching { NewsStore.remove(article.id) }
            if (charged != 0L) Treasury.bank(c, LedgerKind.FEE, -charged, p.stringUUID, "press refund")
            need.forEach { (item, n) -> give(p, item, n) }
            p.inventory.setChanged()
            throw Fail("kami_claims.error.server")
        }
        runCatching(SavePoint::run).onFailure { KamiClaims.LOG.error("Save after publishing article {} failed", article.id, it) }
        deliver(article)
        Phrase.of("kami_claims.news.done.published", title)
    }

    private fun deliver(article: Article) {
        try {
            NewsDelivery.deliver(article)
        } catch (e: Exception) {
            KamiClaims.LOG.error("Delivering article {} failed", article.id, e)
        }
    }

    fun system(template: String, countries: List<Country>, args: List<String>, dedupe: String? = null, body: String = ""): Article? = synchronized(lock) {
        if (dedupe != null && NewsStore.dedupeExists(dedupe)) return null
        val t = now()
        if (template !in BULK && PressRules.hourlyFull(NewsStore.limited(t - Time.HOUR_MS, BULK), cfg.systemPerHour)) {
            if (cfg.digest) NewsDigest.add(listOf(template) + args)
            return null
        }
        val first = countries.firstOrNull()
        Article(
            nextArticle(), ArticleKind.SYSTEM, first?.id, first?.name.orEmpty(), countries.map { it.id }, "", "", body, emptyList(), emptyList(),
            template, args, t, dedupe
        ).also { NewsStore.insert(it); deliver(it) }
    }

    private fun plain(stack: ItemStack) = !stack.isEmpty && stack.componentsPatch.isEmpty

    internal fun carried(p: ServerPlayer): Map<String, Int> =
        p.inventory.items.filter(::plain).groupingBy { itemId(it) }.fold(0) { sum, stack -> sum + stack.count }

    private fun itemId(stack: ItemStack) = BuiltInRegistries.ITEM.getKey(stack.item).toString()

    private fun itemName(id: String) = ResourceLocation.tryParse(id)?.let { BuiltInRegistries.ITEM.get(it).description.string } ?: id

    private fun take(p: ServerPlayer, id: String, count: Int) {
        var left = count
        for (stack in p.inventory.items) {
            if (left == 0) break
            if (!plain(stack) || itemId(stack) != id) continue
            val moved = min(left, stack.count)
            stack.shrink(moved)
            left -= moved
        }
    }

    private fun give(p: ServerPlayer, id: String, count: Int) {
        val stack = item(id) ?: return
        stack.count = count
        p.inventory.placeItemBackInInventory(stack)
    }

    private fun item(id: String): ItemStack? = ResourceLocation.tryParse(id)?.takeIf { BuiltInRegistries.ITEM.containsKey(it) }?.let { ItemStack(BuiltInRegistries.ITEM.get(it)) }

    private fun bad(ref: Ref): Nothing = throw Fail("kami_claims.news.error.token", ref.token)

    fun editorial(title: String, subtitle: String, body: String, author: String): Article = synchronized(lock) {
        val t = now()
        val text = Markup.clean(body, cfg.bodyMax)
        val (_, found) = refsOf(text)
        Article(
            nextArticle(), ArticleKind.EDITORIAL, null, "", emptyList(), Markup.cleanLine(title, cfg.titleMax), Markup.cleanLine(subtitle, cfg.subtitleMax), text, listOf(Author("", author)),
            found.map { snapshot(it, t) }, "", emptyList(), t
        ).also { NewsStore.insert(it); deliver(it) }
    }

    fun amend(id: Long, title: String, subtitle: String, body: String, note: String): Article? = synchronized(lock) {
        val old = NewsStore.article(id)?.takeIf { !it.deleted && it.templateKey.isEmpty() } ?: return null
        val text = Markup.clean(body, cfg.bodyMax)
        val refs = refsOf(text).second.map { snapshot(it, now()) }
        old.copy(title = Markup.cleanLine(title, cfg.titleMax), subtitle = Markup.cleanLine(subtitle, cfg.subtitleMax), body = text, refs = refs, correctedNote = note).also(NewsStore::update)
    }

    fun retract(id: Long, by: String, reason: String): Boolean = synchronized(lock) {
        val old = NewsStore.article(id)?.takeIf { !it.deleted } ?: return false
        NewsStore.markDeleted(old.id, by, now(), reason)
        true
    }

    fun live(body: String): List<RefSnap> {
        val t = now()
        return Markup.refs(Markup.parse(body)).take(MAX_REFS).mapNotNull { runCatching { snapshot(it, t) }.getOrNull() }
    }

    private fun snapshot(ref: Ref, t: Long): RefSnap = when (ref) {
        is Ref.Price -> RefSnap(ref.kind, ref.id, MarketApi.value(item(ref.item) ?: bad(ref)), t)
        is Ref.Flag -> (Realm.data.countries[ref.country] ?: bad(ref)).let { RefSnap(ref.kind, it.id, name = it.name, color = it.color, pattern = it.flag.pattern, emblem = it.flag.emblem, secondary = it.flag.secondary) }
        is Ref.Head -> RefSnap(ref.kind, ref.id, name = ref.player.toUuidOrNull()?.let { Announce.server?.let { s -> Players.name(s, it) } } ?: bad(ref))
        is Ref.Item -> if (item(ref.id) != null) RefSnap(ref.kind, ref.id) else bad(ref)
        is Ref.Icon -> if (icon.matches(ref.name)) RefSnap(ref.kind, ref.id) else bad(ref)
    }

    fun prune() {
        NewsStore.prune(now() - cfg.retentionDays * Time.DAY_MS)
        val orphans = NewsStore.draftCountries().filter { Realm.live(it) == null }
        if (orphans.isNotEmpty()) NewsStore.deleteDraftsOf(orphans)
    }
}
