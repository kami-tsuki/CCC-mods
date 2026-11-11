package kami.claims.net

import kami.claims.Config
import kami.claims.KamiClaims
import kami.claims.Names
import kami.claims.Realm
import kami.claims.gov.Perm
import kami.claims.now
import kami.claims.press.News
import kami.claims.press.NewsStore
import kami.claims.press.PressRules
import kami.claims.service.Fail
import kami.claims.service.Service
import kami.claims.social.Perms
import kami.libs.db.Db
import kami.libs.net.ActPayload
import kami.libs.press.Dye
import kami.libs.press.Markup
import kami.libs.press.PressCost
import kami.libs.text.Phrase
import kami.libs.util.RateLimit
import kami.libs.util.Time
import kotlinx.serialization.KSerializer
import net.minecraft.server.level.ServerPlayer
import net.neoforged.neoforge.network.PacketDistributor
import java.util.UUID

object NewsNet {
    private val reads = RateLimit<UUID>(40, 5000L)
    private val writes = RateLimit<UUID>(12, 5000L)
    private val READS = setOf("news_info", "news_feed", "news_article", "news_drafts", "news_draft")
    private val SUPPLIES = setOf(PressRules.PAPER, PressRules.INK) + Dye.entries.map { it.item }
    private val cfg get() = Config.s.press

    fun forget(p: ServerPlayer) {
        reads.forget(p.uuid)
        writes.forget(p.uuid)
    }

    fun handle(p: ServerPlayer, a: ActPayload) {
        try {
            dispatch(p, a)
        } catch (e: Exception) {
            KamiClaims.LOG.warn("News request {} failed: {}", a.name, e.toString())
            runCatching { push(p, "result", ResultDto.serializer(), ResultDto(a.name, false, Phrase.of("kami_claims.error.server").json(), "ERROR")) }
        }
    }

    private fun dispatch(p: ServerPlayer, a: ActPayload) {
        if (!Perms.has(p, Perms.USE)) return
        if (a.name in READS) {
            if (reads.allow(p.uuid)) read(p, a)
            return
        }
        if (!writes.allow(p.uuid)) return push(p, "result", ResultDto.serializer(), ResultDto(a.name, false, Phrase.of("kami_libs.admin.rate_limited").json(), "COOLDOWN"))
        var focus = 0L
        val result = try {
            val message = write(p, a.name, a.args) { focus = it }
            ResultDto(a.name, true, message.json(), id = focus)
        } catch (e: Fail) {
            ResultDto(a.name, false, e.phrase.json(), e.reason)
        } catch (e: Exception) {
            KamiClaims.LOG.error("News action ${a.name} failed", e)
            ResultDto(a.name, false, Phrase.of("kami_claims.error.server").json(), "ERROR")
        }
        val autosaved = result.ok && a.name == "news_draft_save" && a.args.getOrNull(3) == "1"
        if (!autosaved) drafts(p, focus)
        push(p, "result", ResultDto.serializer(), result)
        if (result.ok && a.name == "news_publish") Net.broadcastOpen(p.server)
    }

    private fun write(p: ServerPlayer, name: String, a: List<String>, focus: (Long) -> Unit): Phrase {
        fun id() = a.getOrNull(0)?.toLongOrNull() ?: throw Fail("kami_claims.error.number")
        return when (name) {
            "news_draft_new" -> News.create(p).also { focus(it.id) }.let { Phrase.of("kami_claims.news.done.created") }
            "news_draft_save" -> {
                News.save(p, id().also(focus), a.getOrElse(1) { "" }, a.getOrElse(2) { "" }, PressDto.join(a, 4), a.getOrNull(3) == "1")
                Phrase.of("kami_claims.news.done.saved")
            }
            "news_draft_lock" -> News.lock(p, id().also(focus)).let { Phrase.of("kami_claims.news.done.locked") }
            "news_draft_takeover" -> News.takeover(p, id().also(focus), a.getOrNull(1) == "1").let { Phrase.of("kami_claims.news.done.locked") }
            "news_draft_release" -> News.release(p, id().also(focus)).let { Phrase.of("kami_claims.news.done.released") }
            "news_draft_delete" -> News.delete(p, id()).let { Phrase.of("kami_claims.news.done.deleted") }
            "news_publish" -> News.publish(p, id())
            else -> throw Fail("kami_claims.error.unknown_action")
        }
    }

    private fun read(p: ServerPlayer, a: ActPayload) {
        when (a.name) {
            "news_info" -> push(p, "info", InfoDto.serializer(), info(p))
            "news_feed" -> feed(p, a.args)
            "news_article" -> push(p, "article", ArticleDto.serializer(), a.args.getOrNull(0)?.toLongOrNull()?.let(News::article)?.takeIf { !it.deleted }?.let { PressDto.article(it, ::lookup) } ?: ArticleDto())
            "news_drafts" -> drafts(p, 0)
            "news_draft" -> push(p, "draft", DraftDto.serializer(), a.args.getOrNull(0)?.toLongOrNull()?.let { id -> runCatching { News.drafts(p) }.getOrNull()?.firstOrNull { it.id == id } }?.let { draft(p, it, true) } ?: DraftDto())
        }
    }

    private fun lookup(id: String) = Realm.data.countries[id]?.let { PressCountry(it.id, it.name, it.color, it.flag.pattern, it.flag.emblem, it.flag.secondary) }

    private fun feed(p: ServerPlayer, a: List<String>) {
        val before = a.getOrNull(0)?.toLongOrNull()?.takeIf { it > 0 } ?: Long.MAX_VALUE
        val limit = (a.getOrNull(1)?.toIntOrNull() ?: PressDto.FEED_MAX).coerceIn(1, PressDto.FEED_MAX)
        val since = a.getOrNull(4)?.toLongOrNull()?.coerceAtLeast(0) ?: 0
        val tag = a.getOrNull(5)?.toIntOrNull() ?: 0
        val rows = News.list(before, limit + 1, PressDto.kinds(a.getOrNull(2).orEmpty()), PressDto.ids(a.getOrNull(3).orEmpty()), since)
        push(p, "feed", FeedDto.serializer(), FeedDto(tag, a.getOrNull(0)?.toLongOrNull() ?: 0, rows.take(limit).map { PressDto.item(it, ::lookup) }, rows.size > limit))
    }

    private fun draft(p: ServerPlayer, d: kami.claims.press.Draft, body: Boolean) = PressDto.draft(
        d, p.stringUUID, now(), { Names.of(p.server, it) }, if (body) PressRules.need(PressCost.of(d.title, d.subtitle, Markup.parse(d.body), cfg.cost)) else emptyMap(), body,
        if (body) News.live(d.body) else emptyList()
    )

    private fun drafts(p: ServerPlayer, focus: Long) {
        val list = runCatching { News.drafts(p) }.getOrDefault(emptyList())
        push(p, "drafts", DraftsDto.serializer(), DraftsDto(list.map { draft(p, it, false) }, focus))
    }

    private fun info(p: ServerPlayer): InfoDto {
        val c = Realm.of(p.stringUUID) ?: return InfoDto()
        val write = runCatching { Service.need(p, Perm.NEWS_WRITE) }.isSuccess
        val publish = runCatching { Service.need(p, Perm.NEWS_PUBLISH) }.isSuccess
        val t = now()
        return InfoDto(
            lookup(c.id) ?: PressCountry(c.id, c.name), PressRules.capFor(cfg, c.id), NewsStore.published(c.id, t - t % Time.DAY_MS), cfg.draftSlots, NewsStore.draftCount(c.id),
            cfg.feeSpur, c.treasury, write, publish, cfg.cost, cfg.titleMax, cfg.subtitleMax, cfg.bodyMax, cfg.autosaveMinSeconds, cfg.lockMinutes,
            if (write) News.carried(p).filterKeys { it in SUPPLIES } else emptyMap()
        )
    }

    private fun <T> push(p: ServerPlayer, kind: String, serializer: KSerializer<T>, value: T) {
        if (p.connection.hasChannel(NewsPacket.TYPE)) PacketDistributor.sendToPlayer(p, NewsPacket(kind, Db.json.encodeToString(serializer, value)))
    }
}
