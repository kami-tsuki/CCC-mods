package kami.libs.discord

import net.dv8tion.jda.api.entities.Message
import net.dv8tion.jda.api.entities.MessageEmbed
import net.dv8tion.jda.api.entities.WebhookClient
import net.dv8tion.jda.api.entities.channel.middleman.MessageChannel
import net.dv8tion.jda.api.exceptions.InsufficientPermissionException
import net.dv8tion.jda.api.requests.restaction.MessageCreateAction
import net.dv8tion.jda.api.requests.restaction.WebhookMessageCreateAction
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

internal object Outbox {
    enum class Channel { CHAT, CONSOLE }

    class Task(
        val build: (WebhookClient<Message>) -> WebhookMessageCreateAction<Message>,
        val plain: (MessageChannel) -> MessageCreateAction,
        val sent: (Long) -> Unit = {}
    )

    private class Lane {
        val queue = ArrayDeque<Task>()
        var busy = false
        var open = false
        var dropped = 0
    }

    private const val CAP = 200
    private const val CONSOLE_CAP = 2000
    private const val LIMIT = MESSAGE_MAX - 20
    private val lanes = Channel.entries.associateWith { Lane() }
    private val lines = ArrayDeque<String>()
    private var executor: ScheduledExecutorService? = null
    private var period = 0
    private var gen = 0

    @Synchronized
    fun start(flushSeconds: Int) {
        if (executor != null && period == flushSeconds) return
        period = flushSeconds
        executor?.shutdownNow()
        executor = Executors.newSingleThreadScheduledExecutor { Thread(it, "kami-discord-outbox").apply { isDaemon = true } }
            .also { it.scheduleWithFixedDelay({ runCatching(::flushConsole) }, flushSeconds.toLong(), flushSeconds.toLong(), TimeUnit.SECONDS) }
    }

    @Synchronized
    fun stop() {
        executor?.shutdownNow()
        executor = null
        reset()
    }

    @Synchronized
    fun open() {
        gen++
        lanes.forEach { (channel, lane) ->
            lane.open = true
            lane.busy = false
            pump(lane, channel)
        }
    }

    @Synchronized
    fun reset() {
        gen++
        lanes.values.forEach {
            it.open = false
            it.busy = false
            it.queue.clear()
            it.dropped = 0
        }
        lines.clear()
    }

    @Synchronized
    fun submit(channel: Channel, task: Task) {
        val lane = lanes.getValue(channel)
        if (lane.queue.size >= CAP) {
            lane.dropped++
            return
        }
        lane.queue.addLast(task)
        pump(lane, channel)
    }

    @Synchronized
    fun console(line: String) {
        if (lines.size >= CONSOLE_CAP) {
            lines.removeFirst()
            lanes.getValue(Channel.CONSOLE).dropped++
        }
        lines += line
    }

    fun drain(deadline: Long) {
        runCatching(::flushConsole)
        while (System.currentTimeMillis() < deadline && !idle()) Thread.sleep(25)
    }

    @Synchronized
    private fun idle() = lanes.values.none { it.open && (it.busy || it.queue.isNotEmpty() || it.dropped > 0) }

    @Synchronized
    private fun flushConsole() {
        if (lines.isEmpty()) return
        val batch = lines.toList()
        lines.clear()
        chunks(batch).forEach { chunk -> submit(Channel.CONSOLE, text("```\n$chunk```")) }
    }

    private fun chunks(batch: List<String>): List<String> {
        val out = ArrayList<String>()
        val current = StringBuilder()
        for (line in batch.flatMap { it.chunked(LIMIT) }) {
            if (current.isNotEmpty() && current.length + line.length + 1 > LIMIT) {
                out += current.toString()
                current.clear()
            }
            if (current.isNotEmpty()) current.append('\n')
            current.append(line)
        }
        if (current.isNotEmpty()) out += current.toString()
        return out
    }

    private fun pump(lane: Lane, channel: Channel) {
        if (!lane.open || lane.busy) return
        val task = if (lane.dropped > 0) notice(lane.dropped).also { lane.dropped = 0 } else lane.queue.removeFirstOrNull() ?: return
        lane.busy = true
        send(lane, channel, task, true, gen)
    }

    private fun notice(count: Int) = text("*$count messages dropped*")

    private fun text(content: String) = Task({ it.sendMessage(content).setAllowedMentions(emptySet()) }, { it.sendMessage(content).setAllowedMentions(emptySet()) })

    fun embed(embed: MessageEmbed) = Task({ it.sendMessageEmbeds(embed) }, { it.sendMessageEmbeds(embed) })

    private fun send(lane: Lane, channel: Channel, task: Task, retry: Boolean, mine: Int) {
        Webhooks.get(channel) { client ->
            if (client == null) return@get sendPlain(lane, channel, task, mine)
            val fail = { e: Throwable ->
                sendFailed(e)
                finish(lane, channel, mine)
            }
            try {
                task.build(client).queue({
                    finish(lane, channel, mine)
                    task.sent(it.idLong)
                }, { e ->
                    if (retry && Webhooks.stale(e)) {
                        Webhooks.invalidate(channel)
                        send(lane, channel, task, false, mine)
                    } else if (Webhooks.stale(e)) {
                        sendPlain(lane, channel, task, mine)
                    } else {
                        fail(e)
                    }
                })
            } catch (e: Exception) {
                fail(e)
            }
        }
    }

    private fun sendFailed(e: Throwable) = Discord.warnOnce("send-${e.javaClass.simpleName}", "Discord send failed: ${e.message}")

    private fun sendPlain(lane: Lane, channel: Channel, task: Task, mine: Int) {
        val id = if (channel == Channel.CHAT) Discord.chatId else Discord.consoleId
        val target = Discord.jda?.getChannelById(MessageChannel::class.java, id)
        if (target == null) {
            Discord.warnOnce("plain-$channel", "The $channel channel $id was not found, cannot send messages")
            return finish(lane, channel, mine)
        }
        val fail = { e: Throwable ->
            if (e is InsufficientPermissionException) Discord.warnOnce("plain-perm-$channel", "The bot cannot send messages in the $channel channel: ${e.message}")
            else sendFailed(e)
            finish(lane, channel, mine)
        }
        try {
            task.plain(target).queue({
                finish(lane, channel, mine)
                task.sent(it.idLong)
            }, fail)
        } catch (e: Exception) {
            fail(e)
        }
    }

    @Synchronized
    private fun finish(lane: Lane, channel: Channel, mine: Int) {
        if (mine != gen) return
        lane.busy = false
        pump(lane, channel)
    }
}
