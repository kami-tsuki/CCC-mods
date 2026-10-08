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

    private companion object {
        const val TOTAL = 6000
        const val TITLE = 256
        const val DESCRIPTION = 4096
        const val FOOTER = 2048
        const val FIELDS = 25
        const val NAME = 256
        const val VALUE = 1024
        const val URL = 2000
    }
}
