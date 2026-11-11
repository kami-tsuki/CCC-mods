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
import net.dv8tion.jda.api.interactions.commands.build.SubcommandGroupData
import net.dv8tion.jda.api.requests.ErrorResponse
import net.dv8tion.jda.api.requests.GatewayIntent
import net.dv8tion.jda.api.utils.ChunkingFilter
import net.dv8tion.jda.api.utils.cache.CacheFlag
import net.dv8tion.jda.api.utils.MemberCachePolicy
import net.dv8tion.jda.api.utils.messages.MessageRequest
import net.minecraft.server.MinecraftServer
import java.util.EnumSet
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

internal object Discord {
    val log = Log.of("discord")
    val features = CopyOnWriteArrayList<DiscordFeature>(listOf(Mc))

    @Volatile var state = DiscordState.OFF
    @Volatile var keys = DiscordKeys()
    @Volatile var connected = DiscordKeys()
    @Volatile var jda: JDA? = null
    @Volatile var started = false
    @Volatile var epoch = 0
    @Volatile private var server: MinecraftServer? = null
    @Volatile private var lastTopic: String? = null
    @Volatile private var lastTopicAttempt = 0L
    @Volatile private var lastPresence: String? = null
    private const val QUEUE_CAP = 500
    private val backlog = AtomicInteger()
    private val dropped = AtomicInteger()
    @Volatile private var lastDropLog = 0L
    private val worker = ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS, LinkedBlockingQueue(64)) { r -> Thread(r, "kami-discord-work").apply { isDaemon = true } }
    const val CLOSE_MS = 5000L
    private const val TOPIC_COOLDOWN_MS = 300_000L
    private val RETRY_SECONDS = longArrayOf(5, 15, 60)
    private val warned = ConcurrentHashMap.newKeySet<String>()
    private val intents = listOf(GatewayIntent.GUILD_MESSAGES, GatewayIntent.MESSAGE_CONTENT, GatewayIntent.GUILD_MEMBERS, GatewayIntent.GUILD_MODERATION)

    val active get() = state == DiscordState.CONNECTING || state == DiscordState.READY
    val consoleOn get() = connected.consoleChannelId.isNotBlank() || connected.consoleWebhookUrl.isNotBlank()
    val guildId get() = connected.guildId.toLongOrNull() ?: 0L
    val chatId get() = connected.chatChannelId.toLongOrNull() ?: 0L
    val consoleId get() = connected.consoleChannelId.toLongOrNull() ?: 0L
    val marketId get() = connected.marketChannelId.toLongOrNull() ?: 0L
    val newsId get() = connected.newsChannelId.toLongOrNull() ?: 0L

    fun warnOnce(kind: String, message: String) {
        if (warned.add(kind)) log.warn(message)
    }

    fun guard(kind: String, failed: () -> Unit = {}, action: () -> Unit) {
        try {
            action()
        } catch (e: Exception) {
            warnOnce("$kind-${e.javaClass.simpleName}", "Discord $kind failed: ${e.message}")
            failed()
        }
    }

    fun post(droppable: Boolean = false, action: () -> Unit) {
        val s = server ?: return
        if (droppable && backlog.incrementAndGet() > QUEUE_CAP) {
            backlog.decrementAndGet()
            dropped.incrementAndGet()
            val now = System.currentTimeMillis()
            if (now - lastDropLog > MINUTE_MS) {
                lastDropLog = now
                log.warn("Discord task queue is full, dropped {} tasks", dropped.getAndSet(0))
            }
            return
        }
        s.execute {
            try {
                runCatching(action).onFailure { log.error("Discord callback failed: {}", it.toString()) }
            } finally {
                if (droppable) backlog.decrementAndGet()
            }
        }
    }

    fun each(droppable: Boolean = false, action: (DiscordFeature) -> Unit) = post(droppable) { features.forEach { f -> runCatching { action(f) }.onFailure { log.error("Discord feature failed: {}", it.toString()) } } }

    fun work(action: () -> Unit): Boolean = try {
        worker.execute { runCatching(action).onFailure { log.error("Discord task failed: {}", it.toString()) } }
        true
    } catch (_: RejectedExecutionException) {
        false
    }

    @Synchronized
    fun start(server: MinecraftServer, flushSeconds: Int) {
        if (started) return
        keys = loadKeys(DiscordKeys())
        started = true
        this.server = server
        Outbox.start(flushSeconds.coerceAtLeast(1))
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
        if (client != null) guard("drain") { Outbox.drain(end) }
        synchronized(this) {
            jda = null
            state = DiscordState.OFF
        }
        Webhooks.clear()
        Outbox.stop()
        client?.let { close(it, end - System.currentTimeMillis()) }
    }

    fun reload(server: MinecraftServer, flushSeconds: Int) {
        val (old, mine) = synchronized(this) {
            if (!started) return
            this.server = server
            Outbox.start(flushSeconds.coerceAtLeast(1))
            keys = loadKeys(keys)
            if (keys == connected && active) return
            state = DiscordState.OFF
            val client = jda
            jda = null
            client to ++epoch
        }
        Outbox.reset()
        Webhooks.clear()
        Thread({
            old?.let { close(it, CLOSE_MS) }
            synchronized(this) { if (started && epoch == mine) connect() }
        }, "kami-discord-reload").apply { isDaemon = true }.start()
    }

    fun disable(mine: Int, reason: String, client: JDA? = null) {
        val current = synchronized(this) {
            if (epoch != mine) return
            state = DiscordState.DISABLED
            jda.also { jda = null }
        }
        log.error("Discord bot disabled: {}", reason)
        Outbox.reset()
        Webhooks.clear()
        current?.shutdownNow()
        if (client !== current) client?.shutdownNow()
    }

    fun ready(client: JDA, mine: Int) {
        val guild = client.getGuildById(guildId) ?: return disable(mine, "guild ${connected.guildId} not found, is the bot invited to it?", client)
        val adopted = synchronized(this) { adopt(client, mine).also { if (it) state = DiscordState.READY } }
        if (!adopted) return
        Webhooks.clear()
        guard("permissions") { audit(guild) }
        guard("market") { market(guild) }
        guard("commands") { guild.updateCommands().addCommands(commands().map(::data)).queue(null) { log.error("Slash command registration failed: {}", it.message) } }
        guard("outbox") { Outbox.open() }
        each { it.onReady() }
    }

    private fun market(guild: Guild) {
        if (marketId == 0L) return log.info("Discord market updates are off, set marketChannelId in kami-discord-bot.json to turn them on")
        val channel = guild.getGuildChannelById(marketId) ?: return log.warn("Discord market channel {} was not found in the guild, market updates are off", marketId)
        log.info("Discord market updates go to #{}", channel.name)
    }

    private fun audit(guild: Guild) {
        val self = guild.selfMember
        if (self.hasPermission(Permission.ADMINISTRATOR)) return
        val missing = listOf(Permission.MANAGE_ROLES, Permission.BAN_MEMBERS).filterNot { self.hasPermission(it) }.map { it.getName() } +
            listOf(chatId to listOf(Permission.VIEW_CHANNEL, Permission.MESSAGE_SEND, Permission.MANAGE_WEBHOOKS, Permission.MANAGE_CHANNEL), consoleId to listOf(Permission.VIEW_CHANNEL, Permission.MANAGE_WEBHOOKS), marketId to listOf(Permission.VIEW_CHANNEL, Permission.MESSAGE_SEND, Permission.MESSAGE_EMBED_LINKS), newsId to listOf(Permission.VIEW_CHANNEL, Permission.MESSAGE_SEND, Permission.MESSAGE_EMBED_LINKS))
                .flatMap { (id, perms) -> guild.getGuildChannelById(id)?.let { c -> perms.filterNot { self.hasPermission(c, it) }.map { "${it.getName()} in #${c.name}" } }.orEmpty() }
        log.info("Discord bot roles: {}", self.roles.joinToString { it.name })
        val category = guild.getCategoryById(Rooms.categoryId)?.let { c ->
            (listOf(Permission.MANAGE_CHANNEL, Permission.MANAGE_PERMISSIONS, Permission.MANAGE_WEBHOOKS) + Rooms.MEMBER).distinct().filterNot { self.hasPermission(c, it) }.map { "${it.getName()} in category ${c.name}" }
        }.orEmpty()
        if (missing.isNotEmpty() || category.isNotEmpty()) log.warn("Discord bot is missing permissions: {}", (missing + category).joinToString())
        if (missing.any { it.startsWith(Permission.MANAGE_WEBHOOKS.getName()) }) log.warn("Without Manage Webhooks the bot posts plain messages under its own name")
    }

    fun commands(): List<SlashCommand> = features.flatMap { it.commands() }

    fun presence(text: String) {
        val client = readyClient() ?: return
        if (text == lastPresence) return
        lastPresence = text
        client.presence.setActivity(Activity.playing(text.take(128)))
    }

    fun topic(text: String, force: Boolean = false) {
        val client = readyClient() ?: return
        if (text == lastTopic) return
        val now = System.currentTimeMillis()
        if (!force && now - lastTopicAttempt < TOPIC_COOLDOWN_MS) return
        val channel = client.getChannelById(StandardGuildMessageChannel::class.java, chatId) ?: return
        lastTopicAttempt = now
        lastTopic = text
        channel.manager.setTopic(text.take(1024)).queue(null) {
            lastTopic = null
            warnOnce("topic", "Cannot set the chat channel topic: ${it.message}")
        }
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

    fun members(ids: Collection<Long>, done: (Map<Long, Member>?) -> Unit) {
        val result = fresh(done)
        val guild = guild() ?: return post { result(null) }
        if (ids.isEmpty()) return post { result(emptyMap()) }
        val role = verifiedRole(guild)
        val found = ConcurrentHashMap<Long, Member>()
        val failed = AtomicBoolean()
        val batches = ids.distinct().chunked(100)
        val left = AtomicInteger(batches.size)
        val finish = { if (left.decrementAndGet() == 0) post { result(if (failed.get()) null else found.toMap()) } }
        batches.forEach { batch ->
            guild.retrieveMembersByIds(batch)
                .onSuccess { list ->
                    runCatching { list.forEach { found[it.idLong] = Member(it.idLong, role == null || it.roles.contains(role)) } }.onFailure { failed.set(true) }
                    finish()
                }
                .onError {
                    failed.set(true)
                    finish()
                }
        }
    }

    fun info(userId: Long, timeoutMs: Long): GuildInfo? {
        val guild = guild() ?: return null
        val m = guild.getMemberById(userId)
            ?: if (timeoutMs <= 0) return null.also { guild.retrieveMemberById(userId).queue({}, {}) }
            else runCatching { guild.retrieveMemberById(userId).submit().get(timeoutMs, TimeUnit.MILLISECONDS) }.getOrNull() ?: return null
        return guildInfo(guild, m)
    }

    fun info(userId: Long, done: (GuildInfo?) -> Unit) {
        val result = fresh(done)
        val guild = guild() ?: return post { result(null) }
        val cached = guild.getMemberById(userId)
        if (cached != null) return post { result(guildInfo(guild, cached)) }
        guild.retrieveMemberById(userId).queue({ m -> post { result(guildInfo(guild, m)) } }, { post { result(null) } })
    }

    private fun guildInfo(guild: Guild, m: net.dv8tion.jda.api.entities.Member): GuildInfo {
        val role = verifiedRole(guild)
        return GuildInfo(m.user.name, m.nickname.orEmpty(), m.effectiveAvatarUrl, m.timeJoined.toInstant().toEpochMilli(), m.roles.map { it.name }, role != null && m.roles.contains(role))
    }

    fun holders(done: (Set<Long>?) -> Unit) {
        val result = fresh(done)
        val guild = guild() ?: return post { result(null) }
        val role = verifiedRole(guild) ?: return post { result(null) }
        guild.findMembersWithRoles(role)
            .onSuccess { list ->
                val ids = list.mapTo(HashSet()) { it.idLong }
                post { result(ids) }
            }
            .onError { post { result(null) } }
    }

    fun bans(done: (Set<Long>?) -> Unit) {
        val result = fresh(done)
        val guild = guild() ?: return post { result(null) }
        val banned = ConcurrentHashMap.newKeySet<Long>()
        guild.retrieveBanList().forEachAsync { banned += it.user.idLong; true }
            .whenComplete { _, error -> post { result(if (error == null) banned.toSet() else null) } }
    }

    fun banned(userId: Long, done: (Boolean?) -> Unit) {
        val result = fresh(done)
        val guild = guild() ?: return post { result(null) }
        guild.retrieveBan(UserSnowflake.fromId(userId)).queue({ post { result(true) } }) { e ->
            post { result(if ((e as? ErrorResponseException)?.errorResponse == ErrorResponse.UNKNOWN_BAN) false else null) }
        }
    }

    private fun <T> fresh(result: (T?) -> Unit): (T?) -> Unit {
        val mine = epoch
        return { value -> result(if (epoch == mine) value else null) }
    }

    private fun loadKeys(fallback: DiscordKeys) = try {
        KeyFile.load()
    } catch (e: Exception) {
        log.error("Cannot read kami-discord-bot.json: {}", e.message)
        fallback
    }

    private fun adopt(client: JDA, mine: Int): Boolean = synchronized(this) {
        if (epoch != mine || !active) return false
        if (jda !== client) {
            jda?.shutdownNow()
            jda = client
        }
        true
    }

    private fun verifiedRole(guild: Guild) = connected.verifiedRoleId.toLongOrNull()?.let(guild::getRoleById)

    private fun readyClient() = jda?.takeIf { state == DiscordState.READY }

    fun guild(): Guild? = readyClient()?.getGuildById(guildId)

    private fun close(client: JDA, timeoutMs: Long) {
        try {
            client.shutdown()
            if (!client.awaitShutdown(timeoutMs.coerceAtLeast(0), TimeUnit.MILLISECONDS)) client.shutdownNow()
        } catch (_: Exception) {
            client.shutdownNow()
        }
    }

    private fun connect() {
        val k = keys
        connected = k
        warned.clear()
        lastTopic = null
        lastTopicAttempt = 0L
        lastPresence = null
        if (!DiscordApi.configured) {
            state = DiscordState.OFF
            if (KeyFile.broken) log.warn("Discord bot idle: config/kami-discord-bot.json is unreadable, joins are blocked until it is fixed and /discord reload is run")
            else if (k.token.isBlank()) log.info("Discord bot idle: set token, guildId and chatChannelId in config/kami-discord-bot.json")
            else log.warn("Discord bot idle: guildId and chatChannelId in config/kami-discord-bot.json must be numeric ids")
            return
        }
        state = DiscordState.CONNECTING
        val mine = ++epoch
        Thread({ build(k, mine) }, "kami-discord-connect").apply { isDaemon = true }.start()
    }

    private fun build(k: DiscordKeys, mine: Int) {
        var attempt = 0
        while (epoch == mine && state == DiscordState.CONNECTING) {
            try {
                MessageRequest.setDefaultMentions(EnumSet.of(Message.MentionType.USER))
                val client = JDABuilder.createLight(k.token, intents)
                    .enableCache(CacheFlag.MEMBER_OVERRIDES)
                    .setMemberCachePolicy(MemberCachePolicy.NONE)
                    .setChunkingFilter(ChunkingFilter.NONE)
                    .setEnableShutdownHook(false)
                    .addEventListeners(Gateway(mine))
                    .build()
                if (!adopt(client, mine)) client.shutdownNow()
                return
            } catch (_: InvalidTokenException) {
                return disable(mine, "the bot token is invalid")
            } catch (e: Exception) {
                val wait = RETRY_SECONDS[minOf(attempt++, RETRY_SECONDS.lastIndex)]
                log.warn("Discord connection failed ({}), retrying in {}s", e.message ?: e.javaClass.simpleName, wait)
                try {
                    TimeUnit.SECONDS.sleep(wait)
                } catch (_: InterruptedException) {
                    return
                }
            }
        }
    }

    private fun data(c: SlashCommand): SlashCommandData {
        val data = Commands.slash(c.name, c.description)
        if (c.subcommands.isEmpty()) data.addOptions(c.options.map(::option))
        else {
            val (groups, subs) = c.subcommands.partition { it.subcommands.isNotEmpty() }
            data.addSubcommands(subs.map(::sub))
            data.addSubcommandGroups(groups.map { g -> SubcommandGroupData(g.name, g.description).addSubcommands(g.subcommands.map(::sub)) })
        }
        if (c.admin) data.setDefaultPermissions(DefaultMemberPermissions.DISABLED)
        return data
    }

    private fun sub(c: SlashCommand) = SubcommandData(c.name, c.description).addOptions(c.options.map(::option))

    private fun option(o: SlashOption) = OptionData(OptionType.STRING, o.name, o.description, o.required, o.complete != null).also { d -> o.choices.forEach { d.addChoice(it, it) } }
}
