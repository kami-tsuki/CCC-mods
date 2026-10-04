package kami.libs.discord

import kami.libs.discord.Outbox.Channel
import net.dv8tion.jda.api.entities.channel.attribute.IWebhookContainer
import net.dv8tion.jda.api.entities.Message
import net.dv8tion.jda.api.entities.WebhookClient
import net.dv8tion.jda.api.exceptions.ErrorResponseException
import net.dv8tion.jda.api.requests.ErrorResponse

internal object Webhooks {
    private const val NAME = "Kami"
    private val clients = HashMap<Channel, WebhookClient<Message>>()
    private val waiting = HashMap<Channel, MutableList<(WebhookClient<Message>?) -> Unit>>()
    private val failed = HashSet<Channel>()
    private var epoch = 0

    fun get(channel: Channel, callback: (WebhookClient<Message>?) -> Unit) {
        var known = false
        var mine = 0
        val (hit, first) = synchronized(this) {
            val cached = clients[channel]
            known = cached == null && channel in failed
            mine = epoch
            cached to (cached == null && !known && waiting.getOrPut(channel) { ArrayList() }.also { it += callback }.size == 1)
        }
        if (hit != null) callback(hit) else if (known) callback(null) else if (first) resolve(channel, mine)
    }

    @Synchronized
    fun invalidate(channel: Channel) {
        clients.remove(channel)
    }

    @Synchronized
    fun clear() {
        epoch++
        clients.clear()
        waiting.clear()
        failed.clear()
    }

    fun stale(error: Throwable): Boolean =
        (error as? ErrorResponseException)?.errorResponse.let { it == ErrorResponse.UNKNOWN_WEBHOOK || it == ErrorResponse.INVALID_WEBHOOK_TOKEN }

    private fun resolve(channel: Channel, mine: Int) {
        val jda = Discord.jda ?: return done(channel, mine, null)
        val keys = Discord.connected
        val (url, channelId) = if (channel == Channel.CHAT) keys.chatWebhookUrl to keys.chatChannelId else keys.consoleWebhookUrl to keys.consoleChannelId
        if (url.isNotBlank()) {
            val client = runCatching { WebhookClient.createClient(jda, url) }.getOrNull()
            if (client == null) Discord.warnOnce("url-$channel", "The $channel webhook URL in kami-discord-bot.json is invalid")
            return done(channel, mine, client)
        }
        val container = channelId.toLongOrNull()?.let { jda.getChannelById(IWebhookContainer::class.java, it) }
        if (container == null) {
            Discord.warnOnce("channel-$channel", "The $channel channel $channelId was not found")
            return done(channel, mine, null)
        }
        val self = jda.selfUser.idLong
        val fail = { e: Throwable ->
            Discord.warnOnce("hook-$channel", "Cannot get a webhook for the $channel channel (${e.message}); give the bot Manage Webhooks or set a webhook URL")
            done(channel, mine, null)
        }
        try {
            container.retrieveWebhooks().queue({ hooks ->
                try {
                    val own = hooks.firstOrNull { it.name == NAME && it.ownerAsUser?.idLong == self }
                    if (own != null) done(channel, mine, WebhookClient.createClient(jda, own.url))
                    else container.createWebhook(NAME).queue({ done(channel, mine, WebhookClient.createClient(jda, it.url)) }, fail)
                } catch (e: Exception) {
                    fail(e)
                }
            }, fail)
        } catch (e: Exception) {
            fail(e)
        }
    }

    private fun done(channel: Channel, mine: Int, client: WebhookClient<Message>?) {
        val callbacks = synchronized(this) {
            if (mine != epoch) return
            if (client != null) clients[channel] = client else failed += channel
            waiting.remove(channel).orEmpty()
        }
        callbacks.forEach { it(client) }
    }
}
