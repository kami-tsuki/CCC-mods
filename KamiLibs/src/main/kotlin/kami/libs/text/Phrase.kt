package kami.libs.text

import kami.libs.chat.Theme
import kami.libs.ui.style.Format
import kami.libs.ui.text.tr
import kami.libs.ui.text.trOr
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import net.minecraft.locale.Language
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.MutableComponent

@Serializable
class Phrase(val key: String = "", val args: List<Phrase> = emptyList(), val text: String = "", val value: Boolean = false) {
    fun asValue() = Phrase(key, args, text, true)

    fun json(): String = json.encodeToString(this)

    fun component(): MutableComponent {
        val out = when {
            key.isEmpty() -> Component.literal(text)
            text.isNotEmpty() && !Language.getInstance().has(key) -> Component.literal(text)
            else -> Text.msg(key, *args.map { it.component() }.toTypedArray())
        }
        return if (value) out.withColor(Theme.VALUE) else out
    }

    fun resolve(): String = when {
        key.isEmpty() -> text
        text.isNotEmpty() -> trOr(key, text)
        else -> tr(key, *args.map { it.resolve() }.toTypedArray())
    }

    companion object {
        private val json = Json { encodeDefaults = false; ignoreUnknownKeys = true }

        fun of(key: String, vararg args: Any) = Phrase(key, args.map(::wrap))
        fun or(key: String, fallback: String) = Phrase(key, text = fallback)
        fun literal(text: Any) = Phrase(text = text.toString())
        fun value(v: Any) = wrap(v).asValue()
        fun plural(key: String, n: Long) = of(if (n == 1L) "$key.one" else "$key.other", Format.number(n))
        fun money(n: Long) = of("kami_libs.unit.money", Format.number(n)).asValue()

        fun parse(text: String): Phrase? =
            if (text.startsWith("{\"")) runCatching { json.decodeFromString<Phrase>(text) }.getOrNull() else null

        private fun wrap(v: Any) = v as? Phrase ?: literal(v)
    }
}
