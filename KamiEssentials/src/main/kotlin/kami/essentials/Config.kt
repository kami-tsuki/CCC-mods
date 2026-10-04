package kami.essentials

import kami.essentials.display.Sidebar
import kami.libs.chat.Theme
import kami.libs.config.ConfigModule
import kami.libs.config.Section
import kotlinx.serialization.Serializable

@Serializable
data class InlineGeneral(
    val enabled: Boolean = true,
    val actionTtlSeconds: Int = 180,
    val maxTokensPerMessage: Int = 16,
    val itemViewer: Boolean = true,
    val inventoryViewer: Boolean = true,
    val enderChestViewer: Boolean = true,
    val posSuggestTp: Boolean = true,
)

@Serializable
data class InlineTokens(
    val item: List<String> = listOf("i", "item"),
    val inventory: List<String> = listOf("inv", "inventory"),
    val enderChest: List<String> = listOf("ec", "enderchest"),
    val health: List<String> = listOf("health"),
    val hunger: List<String> = listOf("hunger"),
    val armor: List<String> = listOf("armor"),
    val xp: List<String> = listOf("xp"),
    val level: List<String> = listOf("level"),
    val pos: List<String> = listOf("pos"),
    val balance: List<String> = listOf("balance", "bal"),
    val country: List<String> = listOf("country"),
    val rank: List<String> = listOf("rank"),
    val ping: List<String> = listOf("ping"),
    val tps: List<String> = listOf("tps"),
)

@Serializable
data class InlineStyle(
    val textColor: Int = Theme.TEXT,
    val linkColor: Int = Theme.LINK,
    val bracketColor: Int = Theme.MUTED,
    val valueColor: Int = Theme.VALUE,
    val positiveColor: Int = Theme.OK,
    val warningColor: Int = Theme.WARN,
    val negativeColor: Int = Theme.BAD,
    val healthIcon: String = "❤",
    val hungerIcon: String = "🍗",
    val armorIcon: String = "⛨",
    val xpIcon: String = "✦",
)

@Serializable
data class ChatChannelStyle(
    val globalIcon: String = "🌐",
    val countryIcon: String = "🗺",
    val adminIcon: String = "⏻",
    val globalColor: Int = Theme.MUTED,
    val countryColor: Int = Theme.OK,
    val adminColor: Int = Theme.WARN,
)

@Serializable
data class DiscordChat(val toDiscord: Boolean = true, val toMinecraft: Boolean = true, val maxLength: Int = 256)

@Serializable
data class DiscordEvents(
    val join: Boolean = true,
    val leave: Boolean = true,
    val death: Boolean = true,
    val advancement: Boolean = true,
    val start: Boolean = true,
    val stop: Boolean = true,
)

@Serializable
data class DiscordTemplates(
    val username: String = "{player}",
    val join: String = "{player} joined",
    val leave: String = "{player} left",
    val death: String = "{message}",
    val advancement: String = "{message}",
    val start: String = "Server started",
    val stop: String = "Server stopped",
)

@Serializable
data class DiscordAvatar(
    val primary: String = "https://mc-heads.net/avatar/{uuid}/64",
    val fallback: String = "https://crafatar.com/avatars/{uuid}?size=64&overlay",
    val server: String = "",
)

@Serializable
data class DiscordConsole(
    val enabled: Boolean = true,
    val commandBlocks: Boolean = false,
    val flushSeconds: Int = 3,
    val redact: List<String> = listOf("msg", "tell", "w", "r", "teammsg", "tm", "login", "register", "changepassword"),
)

@Serializable
data class DiscordStatus(
    val presence: String = "{online} players online",
    val presenceDebounceSeconds: Int = 30,
    val topic: String = "🟢 {online}/{max} online · up {uptime} · {countries} countries",
    val topicOffline: String = "🔴 Server offline",
    val topicMinutes: Int = 30,
)

@Serializable
data class MotdSettings(val enabled: Boolean = true, val title: String = "Kami", val frameSeconds: Int = 6, val itemsPerFrame: Int = 3, val frames: Int = 4)

@Serializable
data class DiscordVerify(val codeMinutes: Int = 15, val attempts: Int = 5, val windowMinutes: Int = 10, val banSync: Boolean = true)

@Serializable
data class DiscordSettings(
    val enabled: Boolean = true,
    val language: String = "en_us",
    val chat: DiscordChat = DiscordChat(),
    val events: DiscordEvents = DiscordEvents(),
    val templates: DiscordTemplates = DiscordTemplates(),
    val avatar: DiscordAvatar = DiscordAvatar(),
    val console: DiscordConsole = DiscordConsole(),
    val status: DiscordStatus = DiscordStatus(),
    val verify: DiscordVerify = DiscordVerify(),
)

@Serializable
data class Settings(
    val tradeDistance: Int = 16,
    val tradeConfirmSeconds: Int = 3,
    val tradeRequestSeconds: Int = 60,
    val chat: Boolean = true,
    val joinLeave: Boolean = true,
    val deaths: Boolean = true,
    val achievements: Boolean = true,
    val tab: Boolean = true,
    val tabTitle: String = "Kami",
    val sidebar: Boolean = true,
    val sidebarTitle: String = "Kami",
    val sidebarLines: List<String> = listOf("country", "balance", "", "playtime", "kills", "deaths", "", "online", "ping"),
    val inlineGeneral: InlineGeneral = InlineGeneral(),
    val inlineTokens: InlineTokens = InlineTokens(),
    val inlineStyle: InlineStyle = InlineStyle(),
    val channels: ChatChannelStyle = ChatChannelStyle(),
    val discord: DiscordSettings = DiscordSettings(),
    val motd: MotdSettings = MotdSettings(),
)

private val tokenAlias = Regex("""[a-z0-9_:-]{1,24}""")

private fun aliases(items: List<String>, fallback: List<String>) =
    items.map { it.trim().lowercase() }.filter { it.matches(tokenAlias) }.distinct().ifEmpty { fallback }

private fun InlineTokens.sane() = copy(
    item = aliases(item, InlineTokens().item),
    inventory = aliases(inventory, InlineTokens().inventory),
    enderChest = aliases(enderChest, InlineTokens().enderChest),
    health = aliases(health, InlineTokens().health),
    hunger = aliases(hunger, InlineTokens().hunger),
    armor = aliases(armor, InlineTokens().armor),
    xp = aliases(xp, InlineTokens().xp),
    level = aliases(level, InlineTokens().level),
    pos = aliases(pos, InlineTokens().pos),
    balance = aliases(balance, InlineTokens().balance),
    country = aliases(country, InlineTokens().country),
    rank = aliases(rank, InlineTokens().rank),
    ping = aliases(ping, InlineTokens().ping),
    tps = aliases(tps, InlineTokens().tps),
)

private fun icon(text: String, fallback: String): String =
    text.trim().take(4).ifBlank { fallback }

private fun ChatChannelStyle.sane() = copy(
    globalIcon = icon(globalIcon, ChatChannelStyle().globalIcon),
    countryIcon = icon(countryIcon, ChatChannelStyle().countryIcon),
    adminIcon = icon(adminIcon, ChatChannelStyle().adminIcon),
)

private fun Settings.sane() = copy(
    tradeDistance = tradeDistance.coerceAtLeast(-2),
    tradeConfirmSeconds = tradeConfirmSeconds.coerceIn(0, 30),
    tradeRequestSeconds = tradeRequestSeconds.coerceIn(10, 600),
    sidebarLines = sidebarLines.filter { it.isEmpty() || it in Sidebar.LINES }.take(15),
    inlineGeneral = inlineGeneral.copy(
        actionTtlSeconds = inlineGeneral.actionTtlSeconds.coerceIn(10, 3600),
        maxTokensPerMessage = inlineGeneral.maxTokensPerMessage.coerceIn(1, 64)
    ),
    inlineTokens = inlineTokens.sane(),
    channels = channels.sane(),
    discord = discord.copy(
        chat = discord.chat.copy(maxLength = discord.chat.maxLength.coerceIn(32, 1024)),
        console = discord.console.copy(flushSeconds = discord.console.flushSeconds.coerceIn(1, 30)),
        status = discord.status.copy(
            presenceDebounceSeconds = discord.status.presenceDebounceSeconds.coerceAtLeast(15),
            topicMinutes = discord.status.topicMinutes.coerceAtLeast(10)
        ),
        verify = discord.verify.copy(codeMinutes = discord.verify.codeMinutes.coerceIn(1, 60), attempts = discord.verify.attempts.coerceAtLeast(1))
    ),
    motd = motd.copy(frameSeconds = motd.frameSeconds.coerceIn(5, 300), itemsPerFrame = motd.itemsPerFrame.coerceIn(1, 4), frames = motd.frames.coerceIn(1, 20)),
)

private val sections = listOf(
    Section(
        "trade.json", "Player to player trading with /trade.",
        mapOf(
            "tradeDistance" to "How close both players must be. -1 is anywhere, -2 is the same dimension, any other number is blocks.",
            "tradeConfirmSeconds" to "Countdown after both accepted. Any change in the offers stops it.",
            "tradeRequestSeconds" to "How long a trade request stays open."
        )
    ),
    Section(
        "chat.json", "Chat, join, leave and death messages.",
        mapOf(
            "chat" to "Show chat with country tags and hover info.",
            "joinLeave" to "Replace the join and leave messages.",
            "deaths" to "Replace the death messages.",
            "achievements" to "Replace advancement and achievement announcements.",
            "channels" to "Chat channel badge style.",
            "channels.globalIcon" to "Icon shown in the global chat badge.",
            "channels.countryIcon" to "Icon shown in the country chat badge.",
            "channels.adminIcon" to "Icon shown in the admin chat badge.",
            "channels.globalColor" to "Color of global chat badge.",
            "channels.countryColor" to "Color of country chat badge.",
            "channels.adminColor" to "Color of admin chat badge."
        )
    ),
    Section(
        "display.json", "Tab list and sidebar.",
        mapOf(
            "tab" to "Show country tags, a header and a footer in the tab list.",
            "tabTitle" to "Title at the top of the tab list.",
            "sidebar" to "Show the stats sidebar. Players can hide it with /scoreboard.",
            "sidebarTitle" to "Title of the sidebar.",
            "sidebarLines" to "Lines from top to bottom: ${Sidebar.LINES.keys.joinToString()}. An empty string is a spacer."
        )
    ),
    Section(
        "motd.json", "Server list MOTD: title with live stats and a rotating market ticker.",
        mapOf(
            "motd" to "Server list message.",
            "motd.enabled" to "Replace the server.properties motd with the live one.",
            "motd.title" to "Name shown at the start of the first line.",
            "motd.frameSeconds" to "Seconds each ticker page stays before the next one (5-300).",
            "motd.itemsPerFrame" to "Market items per ticker page (1-4); items that do not fit the line are dropped.",
            "motd.frames" to "Number of ticker pages to rotate through (1-20)."
        )
    ),
    Section(
        "discord.json", "Discord bot. Keys and token live in config/kami-discord-bot.json.",
        mapOf(
            "discord" to "Discord bot settings.",
            "discord.enabled" to "Turn the Discord bot on. It stays idle and joins stay open until the token, guild and chat channel are set in config/kami-discord-bot.json.",
            "discord.language" to "Language of bot replies and kick messages.",
            "discord.chat" to "Chat relay.",
            "discord.chat.toDiscord" to "Send global chat to Discord.",
            "discord.chat.toMinecraft" to "Show messages of linked Discord users in game.",
            "discord.chat.maxLength" to "Longest Discord message shown in game (32 to 1024). Longer ones are cut and show the full text on hover.",
            "discord.events" to "Which events are posted to the chat channel.",
            "discord.events.join" to "Player joined.",
            "discord.events.leave" to "Player left.",
            "discord.events.death" to "Player died.",
            "discord.events.advancement" to "Player made an advancement.",
            "discord.events.start" to "Server started.",
            "discord.events.stop" to "Server stopped.",
            "discord.templates" to "Texts of posted messages. Placeholders: {player} {uuid} {country} {message} {advancement}.",
            "discord.templates.username" to "Name shown on relayed chat messages.",
            "discord.templates.join" to "Join event text.",
            "discord.templates.leave" to "Leave event text.",
            "discord.templates.death" to "Death event text.",
            "discord.templates.advancement" to "Advancement event text.",
            "discord.templates.start" to "Server start text.",
            "discord.templates.stop" to "Server stop text.",
            "discord.avatar" to "Avatar images for relayed messages.",
            "discord.avatar.primary" to "Avatar URL template, {uuid} is the player uuid.",
            "discord.avatar.fallback" to "Used while the primary service is down.",
            "discord.avatar.server" to "Icon of server start and stop events. Empty for none.",
            "discord.console" to "Command log in the console channel.",
            "discord.console.enabled" to "Post executed commands to the console channel.",
            "discord.console.commandBlocks" to "Include commands run by command blocks.",
            "discord.console.flushSeconds" to "Seconds between batches (1 to 30).",
            "discord.console.redact" to "Commands whose arguments are hidden, without the slash.",
            "discord.status" to "Bot presence and channel topic.",
            "discord.status.presence" to "Presence text. Placeholders: {online} {max}.",
            "discord.status.presenceDebounceSeconds" to "Minimum seconds between presence updates (at least 15).",
            "discord.status.topic" to "Chat channel topic. Placeholders: {online} {max} {uptime} {countries}.",
            "discord.status.topicOffline" to "Topic set when the server stops.",
            "discord.status.topicMinutes" to "Minutes between topic checks (at least 10). Only changed text is sent.",
            "discord.verify" to "Account linking.",
            "discord.verify.codeMinutes" to "How long a link code works (1 to 60).",
            "discord.verify.attempts" to "Wrong codes allowed per window.",
            "discord.verify.windowMinutes" to "Length of the attempt window.",
            "discord.verify.banSync" to "Kick linked players when their Discord account is banned."
        )
    ),
    Section(
        "inline-chat/general.json", "Inline chat tokens and click actions.",
        mapOf(
            "inlineGeneral" to "Global inline chat behavior.",
            "inlineGeneral.enabled" to "Turn inline chat tokens on or off.",
            "inlineGeneral.actionTtlSeconds" to "How long clickable inline actions stay valid.",
            "inlineGeneral.maxTokensPerMessage" to "Most inline tokens expanded in one chat message.",
            "inlineGeneral.itemViewer" to "Allow click-to-open item previews from [item].",
            "inlineGeneral.inventoryViewer" to "Allow click-to-open inventory views from [inv].",
            "inlineGeneral.enderChestViewer" to "Allow click-to-open ender chest views from [ec].",
            "inlineGeneral.posSuggestTp" to "Suggest a teleport command when [pos] is clicked."
        )
    ),
    Section(
        "inline-chat/tokens.json", "Aliases for inline chat placeholders.",
        mapOf(
            "inlineTokens" to "Aliases for each inline chat token.",
            "inlineTokens.item" to "Aliases for hand item.",
            "inlineTokens.inventory" to "Aliases for inventory viewer.",
            "inlineTokens.enderChest" to "Aliases for ender chest viewer.",
            "inlineTokens.health" to "Aliases for health display.",
            "inlineTokens.hunger" to "Aliases for hunger display.",
            "inlineTokens.armor" to "Aliases for armor display.",
            "inlineTokens.xp" to "Aliases for current XP progress.",
            "inlineTokens.level" to "Aliases for level display.",
            "inlineTokens.pos" to "Aliases for position and dimension.",
            "inlineTokens.balance" to "Aliases for economy balance.",
            "inlineTokens.country" to "Aliases for country name.",
            "inlineTokens.rank" to "Aliases for country rank.",
            "inlineTokens.ping" to "Aliases for current ping.",
            "inlineTokens.tps" to "Aliases for server TPS."
        )
    ),
    Section(
        "inline-chat/style.json", "Inline chat colors and symbols.",
        mapOf(
            "inlineStyle" to "Shared visual style for inline placeholders.",
            "inlineStyle.textColor" to "Normal chat text color.",
            "inlineStyle.linkColor" to "URL and interactive token color.",
            "inlineStyle.bracketColor" to "Bracket color around tokens.",
            "inlineStyle.valueColor" to "Neutral token value color.",
            "inlineStyle.positiveColor" to "Positive status token color.",
            "inlineStyle.warningColor" to "Warning status token color.",
            "inlineStyle.negativeColor" to "Negative status token color.",
            "inlineStyle.healthIcon" to "Icon appended to health values.",
            "inlineStyle.hungerIcon" to "Icon appended to hunger values.",
            "inlineStyle.armorIcon" to "Icon appended to armor values.",
            "inlineStyle.xpIcon" to "Icon appended to XP values."
        )
    )
)

object Config : ConfigModule<Settings>("essentials", Settings.serializer(), Settings(), sections, sane = { it.sane() })
