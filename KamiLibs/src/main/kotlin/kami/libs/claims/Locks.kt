package kami.libs.claims

import kami.libs.text.Phrase

object Locks {
    fun level(feature: Phrase, level: Int) = Phrase.of("kami_libs.lock.level", feature, level)

    fun capacity(name: Phrase, max: Int, nextLevel: Int?, nextMax: Int?): Phrase =
        if (nextLevel != null && nextMax != null) Phrase.of("kami_libs.lock.capacity_next", name, max, nextLevel, nextMax)
        else Phrase.of("kami_libs.lock.capacity", name, max)

    fun noCountry() = Phrase.of("kami_libs.lock.no_country")

    fun research(node: Phrase) = Phrase.of("kami_libs.lock.research", node)

    fun embargo(country: String) = Phrase.of("kami_libs.lock.embargo", country)
}
