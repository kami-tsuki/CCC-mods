package kami.libs.chat.inline

import kami.libs.chat.Theme
import net.minecraft.network.chat.ClickEvent
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.HoverEvent
import net.minecraft.network.chat.MutableComponent

object InlineChat {
    private val token = Regex("""\[[^\[\]\r\n]{1,48}]""")
    private val url = Regex("""https?://\S+""")

    fun render(text: String, plain: (String) -> Component, link: (String) -> Component, inline: (String) -> Component?): MutableComponent {
        val out = Component.empty()
        var from = 0
        while (from < text.length) {
            val tokenHit = token.find(text, from)
            val urlHit = url.find(text, from)
            val hit = when {
                tokenHit == null -> urlHit
                urlHit == null -> tokenHit
                tokenHit.range.first <= urlHit.range.first -> tokenHit
                else -> urlHit
            } ?: break
            if (hit.range.first > from) out.append(plain(text.substring(from, hit.range.first)))
            val raw = hit.value
            val rendered = if (raw.startsWith("http://") || raw.startsWith("https://")) link(raw)
            else inline(raw.substring(1, raw.length - 1).trim().lowercase()) ?: plain(raw)
            out.append(rendered)
            from = hit.range.last + 1
        }
        if (from < text.length) out.append(plain(text.substring(from)))
        return out
    }

    fun pill(
        label: String,
        textColor: Int = Theme.VALUE,
        bracketColor: Int = Theme.MUTED,
        hover: HoverEvent? = null,
        click: ClickEvent? = null,
        underlined: Boolean = false,
    ): MutableComponent {
        val out = Component.empty()
        val style = Component.literal(label).withStyle {
            var next = it.withColor(textColor).withUnderlined(underlined)
            if (hover != null) next = next.withHoverEvent(hover)
            if (click != null) next = next.withClickEvent(click)
            next
        }
        out.append(Component.literal("[").withColor(bracketColor))
        out.append(style)
        out.append(Component.literal("]").withColor(bracketColor))
        return out
    }

    fun pill(
        content: Component,
        bracketColor: Int = Theme.MUTED,
        hover: HoverEvent? = null,
        click: ClickEvent? = null,
        underlined: Boolean = false,
    ): MutableComponent {
        val out = Component.empty()
        val styled = content.copy().withStyle {
            var next = it.withUnderlined(underlined)
            if (hover != null) next = next.withHoverEvent(hover)
            if (click != null) next = next.withClickEvent(click)
            next
        }
        out.append(Component.literal("[").withColor(bracketColor))
        out.append(styled)
        out.append(Component.literal("]").withColor(bracketColor))
        return out
    }
}
