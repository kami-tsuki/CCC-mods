package kami.claims

object NameRules {
    fun allows(c: Char) = c.isLetterOrDigit() || c == '_' || c == '-'

    fun valid(name: String) = name.all(::allows)
}
