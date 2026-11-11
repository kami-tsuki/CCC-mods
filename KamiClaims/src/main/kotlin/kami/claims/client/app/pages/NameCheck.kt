package kami.claims.client.app.pages

import kami.claims.NameRules
import kami.claims.net.Limits
import kami.libs.ui.text.tr

internal object NameCheck {
    fun allows(c: Char) = NameRules.allows(c) || c == ' '

    fun min(limits: Limits?) = limits?.nameMin ?: 3

    fun max(limits: Limits?) = limits?.nameMax ?: 24

    fun problem(name: String, limits: Limits?): String? {
        val text = name.trim()
        return when {
            text.length < min(limits) -> tr("kami_claims.wizard.name.error.short", min(limits))
            !text.all(::allows) || NameRules.key(text).isEmpty() -> tr("kami_claims.wizard.name.error.chars")
            else -> null
        }
    }
}
