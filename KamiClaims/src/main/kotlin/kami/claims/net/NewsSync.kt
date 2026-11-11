package kami.claims.net

import kami.claims.NameRules
import kami.claims.KamiClaims
import kami.claims.press.Article
import kami.claims.press.ArticleKind
import kami.claims.press.Author
import kami.claims.press.Draft
import kami.claims.press.PressRules
import kami.claims.press.RefSnap
import kami.libs.db.Db
import kami.libs.net.Packets
import kami.libs.press.CostTable
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import net.minecraft.network.protocol.common.custom.CustomPacketPayload

@Serializable
data class PressCountry(val id: String = "", val name: String = "", val color: Int = 0, val pattern: Int = 0, val emblem: Int = 0, val secondary: Int = 0)

@Serializable
data class FeedItem(
    val id: Long = 0, val kind: String = ArticleKind.COUNTRY.name, val title: String = "", val subtitle: String = "", val country: PressCountry = PressCountry(),
    val authors: List<Author> = emptyList(), val created: Long = 0, val templateKey: String = "", val templateArgs: List<String> = emptyList()
)

@Serializable
data class FeedDto(val tag: Int = 0, val before: Long = 0, val items: List<FeedItem> = emptyList(), val more: Boolean = false)

@Serializable
data class ArticleDto(
    val id: Long = 0, val kind: String = ArticleKind.COUNTRY.name, val title: String = "", val subtitle: String = "", val body: String = "",
    val country: PressCountry = PressCountry(), val involved: List<PressCountry> = emptyList(), val authors: List<Author> = emptyList(),
    val refs: List<RefSnap> = emptyList(), val templateKey: String = "", val templateArgs: List<String> = emptyList(), val created: Long = 0, val corrected: String = ""
)

@Serializable
data class DraftDto(
    val id: Long = 0, val title: String = "", val subtitle: String = "", val body: String = "", val chars: Int = 0, val contributors: List<Author> = emptyList(),
    val lock: String = PressDto.FREE, val holder: Author? = null, val leftMs: Long = 0, val takeover: Boolean = false, val updated: Long = 0,
    val need: Map<String, Int> = emptyMap(), val refs: List<RefSnap> = emptyList()
)

@Serializable
data class DraftsDto(val drafts: List<DraftDto> = emptyList(), val focus: Long = 0)

@Serializable
data class InfoDto(
    val country: PressCountry = PressCountry(), val cap: Int = 0, val published: Int = 0, val slots: Int = 0, val drafts: Int = 0, val fee: Long = 0, val treasury: Long = 0,
    val canWrite: Boolean = false, val canPublish: Boolean = false, val cost: CostTable = CostTable(), val titleMax: Int = 80, val subtitleMax: Int = 120,
    val bodyMax: Int = 5000, val autosaveMinSeconds: Int = 10, val lockMinutes: Int = 5, val have: Map<String, Int> = emptyMap()
)

@Serializable
data class ResultDto(val name: String = "", val ok: Boolean = true, val message: String = "", val reason: String = "", val id: Long = 0)

object PressDto {
    const val FREE = "free"
    const val FEED_MAX = 20
    const val MAX_AUTHORS = 32
    const val MAX_INVOLVED = 8
    const val MAX_REFS = 64
    const val MAX_ARGS = 16
    const val MAX_FILTER = 8
    const val CHUNK = 4000
    const val MAX_JSON = 300_000

    fun split(text: String): List<String> = text.chunked(CHUNK)

    fun join(args: List<String>, from: Int) = args.drop(from).joinToString("")

    fun kinds(csv: String): Set<ArticleKind> = csv.split(',').take(MAX_FILTER).mapNotNull { k -> ArticleKind.entries.firstOrNull { it.name == k } }.toSet()

    fun ids(csv: String): Set<String> = csv.split(',').filter { it.isNotBlank() && it.length <= 64 && it.all(NameRules::allows) }.take(MAX_FILTER).toSet()

    fun item(a: Article, lookup: (String) -> PressCountry?) = FeedItem(
        a.id, a.kind.name, a.title, a.subtitle, owner(a, lookup), a.authors.take(MAX_AUTHORS), a.created, a.templateKey, a.templateArgs.take(MAX_ARGS)
    )

    fun article(a: Article, lookup: (String) -> PressCountry?) = ArticleDto(
        a.id, a.kind.name, a.title, a.subtitle, a.body, owner(a, lookup), a.involved.take(MAX_INVOLVED).map { lookup(it) ?: PressCountry(it) },
        a.authors.take(MAX_AUTHORS), a.refs.take(MAX_REFS), a.templateKey, a.templateArgs.take(MAX_ARGS), a.created, a.correctedNote.orEmpty()
    )

    fun draft(d: Draft, viewer: String, now: Long, name: (String) -> String, need: Map<String, Int>, withBody: Boolean, refs: List<RefSnap> = emptyList()): DraftDto {
        val state = PressRules.lockState(d.lockHolder, d.lockUntil, viewer, now)
        val free = state == PressRules.Lock.FREE
        return DraftDto(
            d.id, d.title, d.subtitle, if (withBody) d.body else "", d.body.length, d.contributors.take(MAX_AUTHORS).map { Author(it, name(it)) },
            state.name.lowercase(), d.lockHolder?.takeIf { !free }?.let { Author(it, name(it)) }, if (free) 0 else d.lockUntil - now,
            PressRules.mayTakeOver(state, PressRules.savedSinceLock(d.updated, d.lockSince), true), d.updated, need, refs
        )
    }

    private fun owner(a: Article, lookup: (String) -> PressCountry?) = a.countryId?.let { lookup(it) ?: PressCountry(it, a.country) } ?: PressCountry(name = a.country)

    fun <T> decode(serializer: kotlinx.serialization.KSerializer<T>, json: String, fallback: T): T =
        try { Db.json.decodeFromString(serializer, json) } catch (_: SerializationException) { fallback } catch (_: IllegalArgumentException) { fallback }
}

class NewsPacket(val kind: String, val json: String) : CustomPacketPayload {
    override fun type() = TYPE

    companion object {
        private val net = Packets.forMod(KamiClaims.ID)
        val TYPE = CustomPacketPayload.Type<NewsPacket>(net.id("news"))
        val CODEC = net.codec<NewsPacket>(
            { b, v -> b.writeUtf(v.kind, 16); b.writeUtf(v.json, PressDto.MAX_JSON) },
            { b -> NewsPacket(b.readUtf(16), b.readUtf(PressDto.MAX_JSON)) }
        )
    }
}
