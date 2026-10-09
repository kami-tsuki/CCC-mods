package kami.claims.service

import kami.claims.Config
import kami.claims.Country
import kami.claims.Names
import kami.claims.social.Mail
import kami.libs.chat.Theme
import kami.libs.discord.DiscordApi
import kami.libs.discord.Embed
import kami.libs.discord.Templates
import kami.libs.text.Phrase
import net.minecraft.server.MinecraftServer

object Announce {
    var server: MinecraftServer? = null

    private val colours = mapOf(
        "founded" to Theme.ACCENT, "levelUp" to Theme.ACCENT,
        "allianceFormed" to Theme.OK, "embargoOff" to Theme.OK,
        "allianceEnded" to Theme.WARN, "provinceReleased" to Theme.WARN,
        "embargoOn" to Theme.BAD, "disbanded" to Theme.BAD,
        "provinceBecame" to Theme.LINK, "provinceGiven" to Theme.LINK, "warProvince" to Theme.BAD,
        "leaderHandover" to Theme.VALUE, "succession" to Theme.VALUE,
        "warDeclared" to Theme.BAD, "warAnswered" to Theme.WARN, "warPhase" to Theme.WARN, "warEnded" to Theme.OK, "warJoined" to Theme.BAD
    )

    fun name(id: String) = Names.of(server, id)

    fun fire(key: String, country: Country, values: Map<String, String>, phrases: Map<String, Phrase> = emptyMap()) {
        val cfg = Config.s.announce[key] ?: return
        if (cfg.mc && Config.s.broadcast) {
            val text = Phrase.of("kami_claims.announce.$key", *values.map { (k, v) -> phrases[k]?.asValue() ?: Phrase.value(v) }.toTypedArray())
            server?.playerList?.broadcastSystemMessage(Mail.chat.info(text.component()), false)
        }
        if (cfg.discord && cfg.template.isNotBlank()) DiscordApi.event(Embed(Templates.fillCoded(cfg.template, values), country.color.takeIf { it != 0 } ?: colours.getValue(key)))
    }
}
