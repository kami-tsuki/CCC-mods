package kami.libs.discord

import net.dv8tion.jda.api.utils.MarkdownSanitizer

internal object Sanitize {
    private const val ZWSP = "​"
    private val emoji = Regex("<a?:(\\w+):\\d+>")
    private val whitespace = Regex("[\\r\\n\\t]+")
    private val stripped = Regex("[§\\p{Cc}\\p{Cf}]")
    private val formatting = Regex("§.")
    private val controls = Regex("[\\p{Cc}&&[^\\n]]")
    private val ansi = Regex("\u001B\\[[0-9;]*[A-Za-z]")
    private val reserved = Regex("discord|clyde", RegexOption.IGNORE_CASE)
    private val roleMention = Regex("<@&(\\d+)>")
    private val heading = Regex("(?m)^(\\s*)(#|-#)")
    private val inlineHeading = Regex("(?<=\n)(\\s*)(#|-#)")
    private val brackets = Regex("[\\[\\]]")

    fun inbound(text: String): String = emoji.replace(text) { ":${it.groupValues[1]}:" }.replace(whitespace, " ").replace(stripped, "").trim()

    fun cap(text: String, max: Int): Pair<String, Boolean> = if (text.length <= max) text to false else text.take(max) to true

    fun outbound(text: String): String {
        return Templates.outsideCode(clean(text), ::escape)
            .replace("@everyone", "@${ZWSP}everyone")
            .replace("@here", "@${ZWSP}here")
            .replace(roleMention) { "<@$ZWSP&${it.groupValues[1]}>" }
    }

    private fun escape(part: String, lineStart: Boolean) = MarkdownSanitizer.escape(part).replace(if (lineStart) heading else inlineHeading, "$1$ZWSP$2").replace(brackets, "\\\\$0")

    fun name(text: String): String {
        val safe = clean(text).replace('\n', ' ').take(80).trim().replace(reserved) { "${it.value.take(1)}$ZWSP${it.value.drop(1)}" }
        return if (safe.length >= 2) safe else safe.padEnd(2, '_').ifBlank { "Minecraft" }
    }

    fun console(line: String): String = clean(ansi.replace(line, "")).replace("```", "'''")

    private fun clean(text: String) = text.replace(formatting, "").replace(controls, "")
}
