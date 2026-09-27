package kami.libs.ui.text

import kami.libs.text.Phrase
import kami.libs.ui.style.Format
import net.minecraft.locale.Language
import java.util.IllegalFormatException

object Translations {
    var lookup: (String) -> String? = { key -> Language.getInstance().takeIf { it.has(key) }?.getOrDefault(key) }
}

fun tr(key: String, vararg args: Any): String {
    val pattern = Translations.lookup(key) ?: return key
    if (args.isEmpty()) return pattern.replace("%%", "%")
    return try {
        String.format(Format.locale, pattern, *args)
    } catch (e: IllegalFormatException) {
        pattern
    }
}

fun trn(key: String, n: Long, vararg args: Any): String =
    tr(if (n == 1L) "$key.one" else "$key.other", Format.number(n), *args)

fun trn(key: String, n: Int, vararg args: Any): String = trn(key, n.toLong(), *args)

fun trOr(key: String, fallback: String): String = if (Translations.lookup(key) != null) tr(key) else fallback

fun trJson(json: String): String = Phrase.parse(json)?.resolve() ?: json
