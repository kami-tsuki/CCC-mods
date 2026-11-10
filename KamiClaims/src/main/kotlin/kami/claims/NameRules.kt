package kami.claims

import java.text.Normalizer

object NameRules {
    private val marks = Regex("\\p{M}+")

    fun allows(c: Char) = c.isLetterOrDigit() || c == '_' || c == '-'

    fun valid(name: String) = name.all(::allows) && key(name).isNotEmpty()

    fun key(name: String) = Normalizer.normalize(name, Normalizer.Form.NFKD).replace(marks, "").lowercase().filter { it.isLetterOrDigit() }
}
