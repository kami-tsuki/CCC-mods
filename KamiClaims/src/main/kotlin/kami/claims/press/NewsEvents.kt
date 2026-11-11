package kami.claims.press

import kami.claims.Config
import kami.claims.Country
import kami.claims.KamiClaims
import kami.claims.Realm
import kami.claims.now
import kami.libs.util.Time

object NewsEvents {
    private enum class Tier { ALWAYS, WAR }

    private class Plan(val template: String, val args: List<String>, val tier: Tier)

    private fun plan(key: String, v: Map<String, String>): Plan? {
        fun get(name: String) = v[name].orEmpty()
        val country = get("country")
        val other = get("other")
        return when (key) {
            "founded" -> Plan(key, listOf(country, get("leader")), Tier.ALWAYS)
            "disbanded" -> Plan(key, listOf(country), Tier.ALWAYS)
            "warDeclared", "allianceFormed" -> Plan(key, listOf(country, other), Tier.ALWAYS)
            "warEnded" -> when (val code = get("code")) {
                "attacker" -> Plan("warEnded.won", listOf(country, other), Tier.ALWAYS)
                "defender" -> Plan("warEnded.won", listOf(other, country), Tier.ALWAYS)
                else -> Plan("warEnded.$code", listOf(country, other), Tier.ALWAYS)
            }
            "warPhase" -> Plan("warPhase.${get("phase")}", listOf(country, other), Tier.WAR)
            "warProvince", "provinceBecame", "provinceReleased" -> Plan(key, listOf(get("province"), get("parent")), if (key == "warProvince") Tier.WAR else Tier.ALWAYS)
            "provinceGiven" -> Plan(key, listOf(get("province"), get("parent"), other), Tier.ALWAYS)
            "leaderHandover", "succession" -> Plan(key, listOf(country, get("old"), get("leader")), Tier.ALWAYS)
            "renamed" -> Plan(key, listOf(get("old"), country), Tier.ALWAYS)
            "flagChanged" -> Plan(key, listOf(country), Tier.ALWAYS)
            else -> null
        }
    }

    fun on(key: String, country: Country, values: Map<String, String>) {
        runCatching { fire(key, country, values) }.onFailure { KamiClaims.LOG.warn("News event {} failed: {}", key, it.toString()) }
    }

    private fun fire(key: String, country: Country, values: Map<String, String>) {
        val cfg = Config.s.press
        if (cfg.events[key] != true) return
        val plan = plan(key, values) ?: return
        val other = Realm.country(values["other"]?.takeIf { it.isNotBlank() } ?: values["parent"])
        val involved = listOfNotNull(country, other, Realm.country(values["parent"]).takeIf { key == "provinceGiven" }).distinctBy { it.id }
        val ids = involved.map { it.id }
        val t = now()
        val day = t / Time.DAY_MS
        val dedupe = when (plan.tier) {
            Tier.ALWAYS -> "$key:${ids.sorted().joinToString("+")}:$day" + if (key == "renamed") ":${values["country"].orEmpty()}" else ""
            Tier.WAR -> {
                val war = "war:${values["warId"]?.takeIf { it.isNotBlank() } ?: PressRules.warKey(country.id, other?.id.orEmpty())}:"
                val kind = "$war${plan.template}:"
                if (!PressRules.warAllowed(NewsStore.dedupeCount(war), NewsStore.dedupeCount(kind, t - t % Time.DAY_MS), cfg)) return
                "$kind$t"
            }
        }
        News.system(plan.template, involved, plan.args, dedupe)
    }
}
