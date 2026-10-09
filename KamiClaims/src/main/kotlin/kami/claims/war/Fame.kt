package kami.claims.war

import kami.claims.Config
import kami.claims.Country
import kami.claims.Rating
import kami.claims.Realm
import kami.claims.now
import kami.claims.service.Fail
import kami.claims.service.Words.days
import kami.claims.service.Words.v
import kami.libs.text.Phrase
import net.minecraft.server.level.ServerPlayer
import net.minecraft.stats.Stats
import kotlin.math.ceil

object Fame {
    private val w get() = Config.s.war

    fun rate(p: ServerPlayer, c: Country, stars: Int): Phrase {
        if (stars !in 1..10) throw Fail("kami_claims.error.fame.range")
        val id = p.stringUUID
        if (id in c.members) throw Fail("kami_claims.error.fame.own")
        val hours = p.stats.getValue(Stats.CUSTOM.get(Stats.PLAY_TIME)) / 72_000.0
        if (hours < w.fameMinPlaytimeHours) throw Fail("kami_claims.error.fame.playtime", v(fmt(w.fameMinPlaytimeHours)))
        c.ratings[id]?.let {
            val left = it.at + w.fameChangeDays * Config.s.dayMillis - now()
            if (left > 0) throw Fail("kami_claims.error.fame.cooldown", days(ceil(left.toDouble() / Config.s.dayMillis).toLong()))
        }
        c.ratings[id] = Rating(stars, now(), hours)
        Realm.changed()
        return Phrase.of("kami_claims.done.fame.rated", v(c.name), v(fmt(stars / 2.0)))
    }

    internal fun score(c: Country): Double? = score(c, extremeVoters(c.ratings.keys))

    fun scores(): Map<String, Double?> {
        val countries = Realm.data.countries.values
        val extremes = extremeVoters(countries.flatMapTo(HashSet()) { it.ratings.keys })
        return countries.associate { it.id to score(it, extremes) }
    }

    private fun score(c: Country, extremes: Set<String>): Double? {
        var weight = 0.0
        var sum = 0.0
        c.ratings.forEach { (id, r) ->
            if (id in c.members) return@forEach
            val wi = (r.hours / w.fameFullWeightHours).coerceAtMost(1.0) * if (id in extremes) w.fameExtremeFactor else 1.0
            weight += wi
            sum += wi * r.stars / 2.0
        }
        if (weight < w.fameShowWeight) return null
        return (w.famePriorWeight * w.famePriorStars + sum) / (w.famePriorWeight + weight)
    }

    fun count(c: Country) = c.ratings.size

    private fun extremeVoters(ids: Set<String>): Set<String> {
        val all = Realm.data.countries.values.map { it.ratings }
        return ids.filterTo(HashSet()) { id ->
            val stars = all.mapNotNull { it[id]?.stars }
            stars.isNotEmpty() && stars.count { it == 1 || it == 10 } * 5 >= stars.size * 4
        }
    }

    private fun fmt(x: Double) = if (x % 1.0 == 0.0) x.toInt().toString() else x.toString()
}
