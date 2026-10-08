package kami.libs.discord

import net.dv8tion.jda.api.entities.channel.middleman.MessageChannel

internal object Push {
    fun market(card: Card): Boolean {
        if (!DiscordApi.marketOn) return false
        val channel = Discord.jda?.getChannelById(MessageChannel::class.java, Discord.marketId)
        if (channel == null) {
            Discord.warnOnce("market-channel", "The market channel ${Discord.marketId} was not found, cannot post price updates")
            return false
        }
        channel.sendMessageEmbeds(card.embed()).setAllowedMentions(emptySet()).queue(null) {
            Discord.warnOnce("market-${it.javaClass.simpleName}", "Market post failed: ${it.message}")
        }
        return true
    }

    fun dm(userId: Long, card: Card) {
        if (!DiscordApi.ready) return
        val embed = card.embed()
        Discord.jda?.openPrivateChannelById(userId)?.flatMap { it.sendMessageEmbeds(embed).setAllowedMentions(emptySet()) }?.queue(null) {
            Discord.warnOnce("dm", "Direct message to a linked player failed (further failures are not logged): ${it.message}")
        }
    }
}
