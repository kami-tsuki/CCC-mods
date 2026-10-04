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
        "provinceBecame" to Theme.LINK, "provinceGiven" to Theme.LINK,
        "leaderHandover" to Theme.VALUE, "succession" to Theme.VALUE
    )

    fun name(id: String) = Names.of(server, id)

    fun fire(key: String, country: Country, values: Map<String, String>) {
        val cfg = Config.s.announce[key] ?: return
        if (cfg.mc && Config.s.broadcast) {
            val text = Phrase.of("kami_claims.announce.$key", *values.values.map { Phrase.value(it) }.toTypedArray())
            server?.playerList?.broadcastSystemMessage(Mail.chat.info(text.component()), false)
        }
        if (cfg.discord && cfg.template.isNotBlank()) DiscordApi.event(Embed(Templates.fill(cfg.template, values.mapValues { (k, v) -> if (k == "level") v else Templates.code(v) }), country.color.takeIf { it != 0 } ?: colours.getValue(key)))
    }
}
