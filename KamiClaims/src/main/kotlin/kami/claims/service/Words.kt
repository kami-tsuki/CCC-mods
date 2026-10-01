package kami.claims.service

import kami.claims.Rank
import kami.claims.TaxMode
import kami.libs.text.Phrase
import kami.libs.ui.style.Format

object Words {
    fun v(x: Any): Phrase = Phrase.value(x)
    fun num(n: Number): Phrase = Phrase.value(Format.number(n.toLong()))
    fun money(n: Number): Phrase = Phrase.money(n.toLong())
    fun count(key: String, n: Number): Phrase = Phrase.plural(key, n.toLong()).asValue()
    fun days(n: Number) = count("kami_claims.unit.day", n)
    fun chunks(n: Number) = count("kami_claims.unit.chunk", n)
    fun type(id: String) = Phrase.or("kami_claims.chunk_type.$id", id.replaceFirstChar(Char::uppercase)).asValue()
    fun job(id: String) = Phrase.or("kami_claims.job.$id", id).asValue()
    fun feature(id: String): Phrase =
        if (id.startsWith("claim_type:")) Phrase.of("kami_claims.feature.claim_type", type(id.substringAfter(':'))).asValue()
        else Phrase.of("kami_claims.feature.${id.replace(':', '.')}").asValue()

    fun rank(r: Rank) = Phrase.of("kami_claims.rank.${r.name.lowercase()}").asValue()

    fun rate(price: Number, period: Int): Phrase {
        val amount = Phrase.of("kami_libs.unit.money", Format.number(price.toLong()))
        return (if (period <= 1) Phrase.of("kami_libs.unit.per_day", amount) else Phrase.of("kami_claims.rate.every", amount, Phrase.plural("kami_claims.unit.day", period.toLong()))).asValue()
    }

    fun tribute(mode: TaxMode, amount: Double) = (
        if (mode == TaxMode.PERCENT) Phrase.of("kami_claims.tribute.percent", Format.number((amount * 100).toLong()))
        else Phrase.of("kami_claims.tribute.flat", Format.number(amount.toLong()))
    ).asValue()
}
