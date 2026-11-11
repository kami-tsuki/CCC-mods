package kami.libs.press

private const val ZWSP = "​"
private val special = Regex("[\\\\*_~`|\\[\\]<>#]")
private val lead = Regex("^[#>+-]")
private val ordered = Regex("^(\\d+)\\.")

private fun Ref.label(): String = when (this) {
    is Ref.Price, is Ref.Item, is Ref.Icon -> "[${id.substringAfter(':').replace('_', ' ')}]"
    is Ref.Flag, is Ref.Head -> ""
}

private fun escape(text: String) = text.replace(special) { "\\${it.value}" }.replace("@", "@$ZWSP").replace("://", ":$ZWSP//")

private fun wrap(text: String, marker: String): String {
    if (marker.isEmpty() || text.isBlank()) return text
    val body = text.trim()
    return text.takeWhile { it.isWhitespace() } + marker + body + marker + text.takeLastWhile { it.isWhitespace() }
}

private fun discordLine(spans: List<Span>): String {
    val line = spans.joinToString("") { s ->
        val text = s.ref?.label() ?: escape(s.text)
        wrap(text, (if (s.bold) "**" else "") + (if (s.italic) "*" else ""))
    }
    return line.replace(lead) { "\\${it.value}" }.replace(ordered) { "${it.groupValues[1]}\\." }
}

private fun plainLine(spans: List<Span>) = spans.joinToString("") { it.ref?.label() ?: it.text }

fun Doc.toDiscord(): String = blocks.joinToString("\n") { b ->
    when (b) {
        is Block.Heading -> "#".repeat(b.level) + " " + discordLine(b.spans)
        is Block.Para -> discordLine(b.spans)
        is Block.Quote -> "> " + discordLine(b.spans)
        is Block.Bullets -> b.items.joinToString("\n") { "- " + discordLine(it) }
        Block.Rule -> "▬▬▬▬▬▬▬▬"
    }
}

fun Doc.toPlain(): String = blocks.joinToString("\n") { b ->
    when (b) {
        is Block.Heading -> plainLine(b.spans)
        is Block.Para -> plainLine(b.spans)
        is Block.Quote -> "> " + plainLine(b.spans)
        is Block.Bullets -> b.items.joinToString("\n") { "- " + plainLine(it) }
        Block.Rule -> "---"
    }
}
