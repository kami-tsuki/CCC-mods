package kami.libs.discord

import net.dv8tion.jda.api.EmbedBuilder
import net.dv8tion.jda.api.entities.MessageEmbed
import java.time.Instant

class EmbedField(val name: String, val value: String, val inline: Boolean = true)

class Card(
    val title: String, val description: String = "", val color: Int = DiscordFormat.NEUTRAL,
    val fields: List<EmbedField> = emptyList(), val footer: String? = null, val timestamp: Long? = null, val thumbnail: String? = null
) {
    internal fun embed(): MessageEmbed {
        var left = TOTAL
        fun take(text: String, max: Int) = Sanitize.card(text).take(minOf(max, left)).also { left -= it.length }
        val b = EmbedBuilder().setColor(color)
        b.setTitle(take(title, TITLE).ifBlank { "-" })
        take(description, DESCRIPTION).takeIf { it.isNotBlank() }?.let(b::setDescription)
        footer?.let { take(it, FOOTER) }?.takeIf { it.isNotBlank() }?.let { b.setFooter(it) }
        timestamp?.let { b.setTimestamp(Instant.ofEpochMilli(it)) }
        thumbnail?.takeIf { it.startsWith("https://") && it.length <= URL }?.let(b::setThumbnail)
        for (f in fields.take(FIELDS)) {
            val name = take(f.name, NAME)
            val value = take(f.value, VALUE)
            if (name.isBlank() || value.isBlank()) continue
            b.addField(name, value, f.inline)
        }
        return b.build()
    }

    companion object {
        const val COMPACT_FIELDS = 4

        fun compact(
            title: String, description: String = "", color: Int = DiscordFormat.NEUTRAL, fields: List<EmbedField> = emptyList(),
            footer: String? = null, timestamp: Long? = null, thumbnail: String? = null
        ) = Card(title, description, color, fields.take(COMPACT_FIELDS), footer, timestamp, thumbnail)

        private const val TOTAL = 6000
        private const val TITLE = 256
        private const val DESCRIPTION = 4096
        private const val FOOTER = 2048
        private const val FIELDS = 25
        private const val NAME = 256
        private const val VALUE = 1024
        private const val URL = 2000
    }
}
