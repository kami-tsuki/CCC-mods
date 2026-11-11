package kami.claims.press

import kami.libs.text.Phrase
import kotlinx.serialization.Serializable

enum class ArticleKind { SYSTEM, COUNTRY, EDITORIAL }

@Serializable
data class Author(val id: String, val name: String)

@Serializable
data class RefSnap(
    val kind: String, val key: String, val value: Long = 0, val at: Long = 0, val name: String = "",
    val color: Int = 0, val pattern: Int = 0, val emblem: Int = 0, val secondary: Int = 0
)

data class Article(
    val id: Long,
    val kind: ArticleKind,
    val countryId: String?,
    val country: String,
    val involved: List<String>,
    val title: String,
    val subtitle: String,
    val body: String,
    val authors: List<Author>,
    val refs: List<RefSnap>,
    val templateKey: String,
    val templateArgs: List<String>,
    val created: Long,
    val dedupe: String? = null,
    val deletedBy: String? = null,
    val deletedAt: Long? = null,
    val deletedReason: String? = null,
    val correctedNote: String? = null,
    val discord: List<String> = emptyList()
) {
    val deleted get() = deletedAt != null

    fun headline(): Phrase = when {
        templateKey.isEmpty() -> Phrase.literal(title)
        templateKey in News.BULK -> Phrase.of("kami_claims.news.sys.$templateKey.title")
        else -> Phrase.of("kami_claims.news.sys.$templateKey.title", *templateArgs.toTypedArray())
    }
}

object Corrections {
    fun mark(at: Long) = at.toString()

    fun date(note: String): String = note.toLongOrNull()?.let { java.time.LocalDate.ofEpochDay(it / kami.libs.util.Time.DAY_MS).toString() } ?: note
}

data class Draft(
    val id: Long,
    val countryId: String,
    val title: String,
    val subtitle: String,
    val body: String,
    val contributors: List<String>,
    val lockHolder: String?,
    val lockUntil: Long,
    val lockSince: Long,
    val updated: Long
)
