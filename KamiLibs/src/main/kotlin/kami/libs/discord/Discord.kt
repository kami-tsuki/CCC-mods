package kami.libs.discord

import kami.libs.log.Log
import net.dv8tion.jda.api.JDA
import net.dv8tion.jda.api.JDABuilder
import net.dv8tion.jda.api.Permission
import net.dv8tion.jda.api.entities.Activity
import net.dv8tion.jda.api.entities.Guild
import net.dv8tion.jda.api.entities.Message
import net.dv8tion.jda.api.entities.UserSnowflake
import net.dv8tion.jda.api.entities.channel.middleman.StandardGuildMessageChannel
import net.dv8tion.jda.api.exceptions.ErrorResponseException
import net.dv8tion.jda.api.exceptions.InvalidTokenException
import net.dv8tion.jda.api.interactions.commands.DefaultMemberPermissions
import net.dv8tion.jda.api.interactions.commands.OptionType
import net.dv8tion.jda.api.interactions.commands.build.Commands
import net.dv8tion.jda.api.interactions.commands.build.OptionData
import net.dv8tion.jda.api.interactions.commands.build.SlashCommandData
import net.dv8tion.jda.api.interactions.commands.build.SubcommandData
import net.dv8tion.jda.api.requests.ErrorResponse
import net.dv8tion.jda.api.requests.GatewayIntent
import net.dv8tion.jda.api.utils.ChunkingFilter
import net.dv8tion.jda.api.utils.MemberCachePolicy
import net.dv8tion.jda.api.utils.messages.MessageRequest
import net.minecraft.server.MinecraftServer
import java.util.EnumSet
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

internal object Discord {
    val log = Log.of("discord")
    val features = CopyOnWriteArrayList<DiscordFeature>()

    @Volatile var state = DiscordState.OFF
    @Volatile var keys = DiscordKeys()
    @Volatile var connected = DiscordKeys()
    @Volatile var jda: JDA? = null
    @Volatile var started = false
    @Volatile var epoch = 0
    @Volatile private var server: MinecraftServer? = null
    @Volatile private var lastTopic: String? = null
    @Volatile private var lastPresence: String? = null
    private var flushSeconds = 3
    private val warned = ConcurrentHashMap.newKeySet<String>()
    private val intents = listOf(GatewayIntent.GUILD_MESSAGES, GatewayIntent.MESSAGE_CONTENT, GatewayIntent.GUILD_MEMBERS, GatewayIntent.GUILD_MODERATION)

    val active get() = state == DiscordState.CONNECTING || state == DiscordState.READY
    val consoleOn get() = connected.consoleChannelId.isNotBlank() || connected.consoleWebhookUrl.isNotBlank()
    val guildId get() = connected.guildId.toLongOrNull() ?: 0L
    val chatId get() = connected.chatChannelId.toLongOrNull() ?: 0L

    fun warnOnce(kind: String, message: String) {
        if (warned.add(kind)) log.warn(message)
    }

    fun post(action: () -> Unit) {
        server?.execute { runCatching(action).onFailure { log.error("Discord callback failed: {}", it.toString()) } }
    }

    fun each(action: (DiscordFeature) -> Unit) = post { features.forEach { f -> runCatching { action(f) }.onFailure { log.error("Discord feature failed: {}", it.toString()) } } }

    @Synchronized
    fun start(server: MinecraftServer, flushSeconds: Int) {
        if (started) return
        started = true
        this.server = server
        this.flushSeconds = flushSeconds.coerceAtLeast(1)
        keys = KeyFile.load()
        Outbox.start(this.flushSeconds)
        connect()
    }

    fun stop(timeoutMs: Long) {
        val client = synchronized(this) {
            if (!started) return
            started = false
            epoch++
            jda
        }
        val end = System.currentTimeMillis() + timeoutMs
        if (client != null) Outbox.drain(end)
        synchronized(this) {
            jda = null
            state = DiscordState.OFF
        }
        Webhooks.clear()
        Outbox.stop()
        client?.let { close(it, end - System.currentTimeMillis()) }
    }

    fun reload(server: MinecraftServer) {
        val old = synchronized(this) {
            if (!started) return
            this.server = server
            keys = KeyFile.load()
            if (keys == connected && state != DiscordState.DISABLED && state != DiscordState.OFF) return
            epoch++
            state = DiscordState.OFF
            jda.also { jda = null }
        }
        Outbox.reset()
        Webhooks.clear()
        Thread({
            old?.let { close(it, 5000) }
            synchronized(this) { if (started) connect() }
        }, "kami-discord-reload").apply { isDaemon = true }.start()
    }

    fun disable(mine: Int, reason: String) {
        val client = synchronized(this) {
            if (epoch != mine) return
            state = DiscordState.DISABLED
            jda.also { jda = null }
        }
        log.error("Discord bot disabled: {}", reason)
        Outbox.reset()
        Webhooks.clear()
        client?.shutdownNow()
    }

    fun ready(client: JDA, mine: Int) {
        val guild = client.getGuildById(guildId) ?: return disable(mine, "guild ${connected.guildId} not found, is the bot invited to it?")
        synchronized(this) {
            if (epoch != mine) return
            jda = client
            state = DiscordState.READY
        }
        Outbox.open()
        guild.updateCommands().addCommands(commands().map(::data)).queue(null) { log.error("Slash command registration failed: {}", it.message) }
        each { it.onReady() }
    }

    fun commands(): List<SlashCommand> = features.flatMap { it.commands() }

    fun presence(text: String) {
        val client = jda?.takeIf { state == DiscordState.READY } ?: return
        if (text == lastPresence) return
        lastPresence = text
        client.presence.setActivity(Activity.playing(text.take(128)))
    }

    fun topic(text: String) {
        val client = jda?.takeIf { state == DiscordState.READY } ?: return
        if (text == lastTopic) return
        val channel = client.getChannelById(StandardGuildMessageChannel::class.java, chatId) ?: return
        channel.manager.setTopic(text.take(1024)).queue({ lastTopic = text }) { warnOnce("topic", "Cannot set the chat channel topic: ${it.message}") }
    }

    fun role(userId: Long, add: Boolean) {
        val guild = guild() ?: return
        val roleId = connected.verifiedRoleId.toLongOrNull() ?: return
        val role = guild.getRoleById(roleId) ?: return warnOnce("role", "Verified role $roleId not found")
        val self = guild.selfMember
        if (!self.hasPermission(Permission.MANAGE_ROLES)) return warnOnce("perm", "The bot lacks the Manage Roles permission")
        if (!self.canInteract(role)) return warnOnce("hierarchy", "The verified role is not below the bot's highest role")
        val user = UserSnowflake.fromId(userId)
        val action = if (add) guild.addRoleToMember(user, role) else guild.removeRoleFromMember(user, role)
        action.queue(null) { e ->
            if ((e as? ErrorResponseException)?.errorResponse != ErrorResponse.UNKNOWN_MEMBER) warnOnce("role-${e.javaClass.simpleName}", "Role change failed: ${e.message}")
        }
    }

    fun members(ids: Collection<Long>, result: (Map<Long, Member>?) -> Unit) {
        val guild = guild() ?: return post { result(null) }
        if (ids.isEmpty()) return post { result(emptyMap()) }
        val role = connected.verifiedRoleId.toLongOrNull()?.let(guild::getRoleById)
        val found = ConcurrentHashMap<Long, Member>()
        val failed = AtomicBoolean()
        val batches = ids.distinct().chunked(100)
        val left = AtomicInteger(batches.size)
        val finish = { if (left.decrementAndGet() == 0) post { result(if (failed.get()) null else found.toMap()) } }
        batches.forEach { batch ->
            guild.retrieveMembersByIds(batch)
                .onSuccess { list ->
                    runCatching { list.forEach { found[it.idLong] = Member(it.idLong, it.effectiveName, role == null || it.roles.contains(role)) } }.onFailure { failed.set(true) }
                    finish()
                }
                .onError {
                    failed.set(true)
                    finish()
                }
        }
    }

    fun bans(result: (Set<Long>?) -> Unit) {
        val guild = guild() ?: return post { result(null) }
        val banned = ConcurrentHashMap.newKeySet<Long>()
        guild.retrieveBanList().forEachAsync { banned += it.user.idLong; true }
            .whenComplete { _, error -> post { result(if (error == null) banned.toSet() else null) } }
    }

    fun banned(userId: Long, result: (Boolean?) -> Unit) {
        val guild = guild() ?: return post { result(null) }
        guild.retrieveBan(UserSnowflake.fromId(userId)).queue({ post { result(true) } }) { e ->
            post { result(if ((e as? ErrorResponseException)?.errorResponse == ErrorResponse.UNKNOWN_BAN) false else null) }
        }
    }

    private fun guild(): Guild? = jda?.takeIf { state == DiscordState.READY }?.getGuildById(guildId)

    private fun close(client: JDA, timeoutMs: Long) {
        client.shutdown()
        if (!client.awaitShutdown(timeoutMs.coerceAtLeast(0), TimeUnit.MILLISECONDS)) client.shutdownNow()
    }

    private fun connect() {
        val k = keys
        connected = k
        warned.clear()
        lastTopic = null
        lastPresence = null
        if (k.token.isBlank() || k.guildId.toLongOrNull() == null || k.chatChannelId.toLongOrNull() == null) {
            state = DiscordState.OFF
            log.info("Discord bot idle: set token, guildId and chatChannelId in config/kami-discord-bot.json")
            return
        }
        state = DiscordState.CONNECTING
        val mine = ++epoch
        Thread({ build(k, mine) }, "kami-discord-connect").apply { isDaemon = true }.start()
    }

    private fun build(k: DiscordKeys, mine: Int) {
        try {
            MessageRequest.setDefaultMentions(EnumSet.of(Message.MentionType.USER))
            val client = JDABuilder.createLight(k.token, intents)
                .setMemberCachePolicy(MemberCachePolicy.NONE)
                .setChunkingFilter(ChunkingFilter.NONE)
                .setEnableShutdownHook(false)
                .addEventListeners(Gateway(mine))
                .build()
            val stale = synchronized(this) { (epoch != mine).also { if (!it) jda = client } }
            if (stale) client.shutdownNow()
        } catch (e: InvalidTokenException) {
            disable(mine, "the bot token is invalid")
        } catch (e: Exception) {
            disable(mine, e.message ?: e.javaClass.simpleName)
        }
    }

    private fun data(c: SlashCommand): SlashCommandData {
        val data = Commands.slash(c.name, c.description)
        if (c.subcommands.isEmpty()) data.addOptions(c.options.map(::option))
        else data.addSubcommands(c.subcommands.map { SubcommandData(it.name, it.description).addOptions(it.options.map(::option)) })
        if (c.admin) data.setDefaultPermissions(DefaultMemberPermissions.DISABLED)
        return data
    }

    private fun option(o: SlashOption) = OptionData(OptionType.STRING, o.name, o.description, o.required)
}
