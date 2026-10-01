package kami.claims.service

import kami.claims.Config
import kami.claims.Country
import kami.claims.NameRules
import kami.claims.Realm
import kami.claims.net.ResearchSync
import kami.claims.research.Tokens
import kami.claims.service.Words.num
import kami.claims.service.Words.v
import kami.claims.social.Mail
import kami.libs.chat.Tone
import kami.libs.text.Phrase

object Naming {
    private fun validate(name: String) {
        val (min, max) = Config.s.nameLength
        if (name.length !in min..max || !NameRules.valid(name)) throw Fail("kami_claims.error.name", num(min), num(max))
    }

    fun check(name: String, renaming: Country? = null) {
        validate(name)
        if (Realm.data.countries.values.any { it !== renaming && it.name.equals(name, true) || it.id == name.lowercase() }) throw Fail("kami_claims.error.name_taken")
    }

    fun rename(country: Country, name: String, actor: String?): Phrase {
        check(name, country)
        if (name.equals(country.name, true)) throw Fail("kami_claims.error.name_same")
        Tokens.spend(country, Tokens.RENAME, Config.s.renameCost, actor)
        val old = country.name
        if (country.slug.isEmpty()) country.slug = country.id
        country.name = name
        Realm.changed()
        ResearchSync.refresh(country)
        Mail.broadcast(country, Phrase.of("kami_claims.mail.renamed", v(old), v(name)), Tone.INFO)
        return Phrase.of("kami_claims.done.renamed", v(name))
    }
}
