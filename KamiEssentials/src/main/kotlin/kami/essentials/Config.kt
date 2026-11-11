package kami.essentials

import kami.essentials.display.Sidebar
import kami.essentials.skills.SkillServer
import kami.libs.chat.Theme
import kami.libs.config.ConfigModule
import kami.libs.config.Configs
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
    val safe: List<String> = listOf("kami", "claims", "economy", "essentials", "discord", "geology", "library", "gamemode", "tp", "teleport", "give", "time", "weather", "kick", "ban", "pardon", "op", "deop", "whitelist", "difficulty", "gamerule", "effect", "summon", "kill", "clear", "setblock", "fill", "stop", "save-all", "list"),
    val redact: List<String> = listOf("msg", "tell", "w", "r", "teammsg", "tm", "login", "register", "changepassword", "countrychat", "cc", "adminchat", "ac", "reply"),
)

@Serializable
data class DiscordStatus(
    val presence: String = "{online} online",
    val presenceDebounceSeconds: Int = 30,
    val topic: String = "🟢 {online}/{max} online · up {uptime} · Countries: {countries}",
    val topicOffline: String = "🔴 Server offline",
    val topicMinutes: Int = 30,
)

@Serializable
data class MotdSettings(val enabled: Boolean = true, val title: String = "Kami", val currency: String = "¤", val frameSeconds: Int = 6, val itemsPerFrame: Int = 3, val frames: Int = 4)

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

private val DEFAULT_SKILL_XP = mapOf(
    "ore" to 6.0, "stone" to 0.2, "log" to 1.2, "crop" to 3.0, "breed" to 2.0, "fish" to 4.0, "kill" to 0.4, "craft" to 8.0,
    "deposit" to 6.0, "trade" to 9.0, "deal" to 3.0, "smelt" to 3.0
)

@Serializable
data class SkillSettings(
    val enabled: Boolean = true,
    val maxLevel: Int = 150,
    val classBonus: Double = 0.10,
    val gateScale: Double = 0.395,
    val gatePower: Double = 1.5,
    val playtimeXpPerHour: Double = 300.0,
    val backfillShare: Double = 0.7,
    val backfillCapHours: Double = 100.0,
    val xpBase: Double = 40.0,
    val xpScale: Double = 530.0,
    val xpPower: Double = 0.5,
    val hourlyFull: Int = 120,
    val xp: Map<String, Double> = DEFAULT_SKILL_XP,
)

@Serializable
data class Settings(
    val tradeDistance: Int = 16,
    val tradeConfirmSeconds: Int = 3,
    val tradeRequestSeconds: Int = 60,
    val tradeXpDaily: Int = 12,
    val tradeXpMinValue: Long = 100,
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
    val skills: SkillSettings = SkillSettings(),
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
    tradeConfirmSeconds = tradeConfirmSeconds.coerceIn(2, 30),
    tradeRequestSeconds = tradeRequestSeconds.coerceIn(10, 600),
    tradeXpDaily = tradeXpDaily.coerceIn(0, 1000),
    tradeXpMinValue = tradeXpMinValue.coerceAtLeast(0),
    sidebarLines = sidebarLines.filter { it.isEmpty() || it in Sidebar.LINES }.take(15),
    inlineGeneral = inlineGeneral.copy(
        actionTtlSeconds = inlineGeneral.actionTtlSeconds.coerceIn(10, 3600),
        maxTokensPerMessage = inlineGeneral.maxTokensPerMessage.coerceIn(1, 64)
    ),
    inlineTokens = inlineTokens.sane(),
    channels = channels.sane(),
    skills = skills.copy(
        maxLevel = skills.maxLevel.coerceIn(10, 1000),
        classBonus = skills.classBonus.coerceIn(0.0, 1.0),
        gateScale = skills.gateScale.coerceIn(0.0, 10.0),
        gatePower = skills.gatePower.coerceIn(1.0, 3.0),
        playtimeXpPerHour = skills.playtimeXpPerHour.coerceIn(0.0, 10_000.0),
        xpBase = skills.xpBase.coerceAtLeast(1.0),
        xpScale = skills.xpScale.coerceAtLeast(0.0),
        xpPower = skills.xpPower.coerceIn(0.5, 3.0),
        hourlyFull = skills.hourlyFull.coerceAtLeast(1),
        xp = (DEFAULT_SKILL_XP + skills.xp).mapValues { it.value.coerceIn(0.0, 1000.0) }
    ),
    discord = discord.copy(
        chat = discord.chat.copy(maxLength = discord.chat.maxLength.coerceIn(32, 1024)),
        console = discord.console.copy(flushSeconds = discord.console.flushSeconds.coerceIn(1, 30)),
        status = discord.status.copy(
            presenceDebounceSeconds = discord.status.presenceDebounceSeconds.coerceAtLeast(15),
            topicMinutes = discord.status.topicMinutes.coerceAtLeast(10)
        ),
        verify = discord.verify.copy(codeMinutes = discord.verify.codeMinutes.coerceIn(1, 60), attempts = discord.verify.attempts.coerceAtLeast(1), windowMinutes = discord.verify.windowMinutes.coerceAtLeast(1))
    ),
    motd = motd.copy(frameSeconds = motd.frameSeconds.coerceIn(5, 300), itemsPerFrame = motd.itemsPerFrame.coerceIn(1, 4), frames = motd.frames.coerceIn(1, 20)),
)

private val sections = listOf(
    Section(
        "trade.json", "Player trading (/trade).",
        mapOf(
            "tradeDistance" to "Max distance: -1 anywhere, -2 same dimension, else blocks.",
            "tradeConfirmSeconds" to "Countdown (2-30) after both accept; any change stops it.",
            "tradeRequestSeconds" to "Seconds a request stays open.",
            "tradeXpDaily" to "Max trades per player per rolling 24 h that give XP/progress.",
            "tradeXpMinValue" to "Min market value of each side's offer for a trade to give XP/progress."
        )
    ),
    Section(
        "chat.json", "Chat, join/leave and death messages.",
        mapOf(
            "chat" to "Chat with country tags and hover info.",
            "joinLeave" to "Replace join/leave messages.",
            "deaths" to "Replace death messages.",
            "achievements" to "Replace advancement announcements.",
            "channels" to "Chat badge style.",
            "channels.globalIcon" to "Global badge icon.",
            "channels.countryIcon" to "Country badge icon.",
            "channels.adminIcon" to "Staff badge icon.",
            "channels.globalColor" to "Global badge color.",
            "channels.countryColor" to "Country badge color.",
            "channels.adminColor" to "Staff badge color."
        )
    ),
    Section(
        "display.json", "Tab list and sidebar.",
        mapOf(
            "tab" to "Country tags, header and footer in the tab list.",
            "tabTitle" to "Title at the top of the tab list.",
            "sidebar" to "Show the stats sidebar (players can hide it with /scoreboard).",
            "sidebarTitle" to "Title of the sidebar.",
            "sidebarLines" to "Lines top to bottom: ${Sidebar.LINES.keys.joinToString()}. Empty string = spacer."
        )
    ),
    Section(
        "motd.json", "Server list MOTD with live stats and market ticker.",
        mapOf(
            "motd" to "Server list message.",
            "motd.enabled" to "Replace the server.properties MOTD.",
            "motd.title" to "Name at the start of line 1.",
            "motd.currency" to "Text after each price (default font characters only).",
            "motd.frameSeconds" to "Seconds per ticker page (5-300).",
            "motd.itemsPerFrame" to "Items per page (1-4); overflow is dropped.",
            "motd.frames" to "Ticker pages (1-20)."
        )
    ),
    Section(
        "discord.json", "Discord bot. Token, guild and channel live in config/kami-discord-bot.json.",
        mapOf(
            "discord" to "Discord bot settings.",
            "discord.enabled" to "Enable the bot. Idle and joins stay open until token, guild and chat channel are set.",
            "discord.language" to "Language of bot replies and kick messages.",
            "discord.chat" to "Chat relay.",
            "discord.chat.toDiscord" to "Relay global chat to Discord.",
            "discord.chat.toMinecraft" to "Show linked users' Discord messages in game.",
            "discord.chat.maxLength" to "Max Discord message length in game (32-1024); longer is cut, full text on hover.",
            "discord.events" to "Events posted to the chat channel.",
            "discord.events.join" to "Post player joins.",
            "discord.events.leave" to "Post player leaves.",
            "discord.events.death" to "Post player deaths.",
            "discord.events.advancement" to "Post advancements.",
            "discord.events.start" to "Post server start.",
            "discord.events.stop" to "Post server stop.",
            "discord.templates" to "Message texts. Placeholders: {player} {uuid} {country} {message} {advancement}.",
            "discord.templates.username" to "Name shown on relayed chat messages.",
            "discord.templates.join" to "Join text.",
            "discord.templates.leave" to "Leave text.",
            "discord.templates.death" to "Death text.",
            "discord.templates.advancement" to "Advancement text.",
            "discord.templates.start" to "Server start text.",
            "discord.templates.stop" to "Server stop text.",
            "discord.avatar" to "Avatar images for relayed messages.",
            "discord.avatar.primary" to "Avatar URL, {uuid} = player uuid.",
            "discord.avatar.fallback" to "Avatar URL used while the primary is down.",
            "discord.avatar.server" to "Icon for start/stop events; empty = none.",
            "discord.console" to "Command log in the console channel.",
            "discord.console.enabled" to "Post executed commands to the console channel.",
            "discord.console.commandBlocks" to "Include command block commands.",
            "discord.console.flushSeconds" to "Seconds between batches (1-30).",
            "discord.console.safe" to "Root commands logged with arguments; every other command is logged as its root name only (names without the leading slash).",
            "discord.console.redact" to "Subcommands whose arguments are hidden even under a safe root (names without the leading slash).",
            "discord.status" to "Bot presence and channel topic.",
            "discord.status.presence" to "Presence text. Placeholders: {online} {max}.",
            "discord.status.presenceDebounceSeconds" to "Seconds between presence updates (min 15).",
            "discord.status.topic" to "Channel topic. Placeholders: {online} {max} {uptime} {countries}.",
            "discord.status.topicOffline" to "Topic set when the server stops.",
            "discord.status.topicMinutes" to "Minutes between topic checks (min 10); only changes are sent.",
            "discord.verify" to "Account linking.",
            "discord.verify.codeMinutes" to "Link code lifetime in minutes (1-60).",
            "discord.verify.attempts" to "Wrong codes allowed per window.",
            "discord.verify.windowMinutes" to "Attempt window length in minutes.",
            "discord.verify.banSync" to "Kick linked players when their Discord account is banned."
        )
    ),
    Section(
        "skills.json", "Personal skill tree.",
        mapOf(
            "skills" to "Personal levels, XP and the skill tree.",
            "skills.enabled" to "Gain skill XP and use skill effects.",
            "skills.maxLevel" to "Highest personal level (10-1000). Points are level + level / 10.",
            "skills.classBonus" to "Extra XP from sources of the chosen class (0.1 = 10%).",
            "skills.gateScale" to "Active play hours needed for level L: gateScale * L ^ gatePower (0.395 and 1.5 give about 100 h for level 40 and 726 h for level 150).",
            "skills.gatePower" to "Exponent of the playtime gate (1-3).",
            "skills.playtimeXpPerHour" to "XP per active hour, added directly to every player (no class needed, no fades, no class bonus). Active means an action or a look change within the last minute; walking alone does not count.",
            "skills.xpBase" to "XP from level L to L+1: xpBase + xpScale * L ^ xpPower.",
            "skills.xpScale" to "Scale of the XP curve (530 with power 0.5 keeps a player at 900 XP per hour on the playtime gate).",
            "skills.xpPower" to "Exponent of the XP curve (0.5-3).",
            "skills.hourlyFull" to "Full XP actions per source and chunk per hour; then 50%, 10% and none. A total of four times this per source applies as well.",
            "skills.xp" to "XP per source: ore, stone, log, crop, breed, fish, kill (per max health), craft (first craft of an item), deposit (research items, log scale), smelt (per ingot taken from a furnace), trade (per log10 of the value, other countries at peace), deal (per log10 of the value, any other player order or auction). Missing sources fall back to the defaults."
        )
    ),
    Section(
        "inline-chat/general.json", "Inline chat tokens.",
        mapOf(
            "inlineGeneral" to "Global inline chat behavior.",
            "inlineGeneral.enabled" to "Enable inline tokens.",
            "inlineGeneral.actionTtlSeconds" to "Seconds a clickable action stays valid.",
            "inlineGeneral.maxTokensPerMessage" to "Max tokens expanded per message.",
            "inlineGeneral.itemViewer" to "Click-to-open [item] views.",
            "inlineGeneral.inventoryViewer" to "Click-to-open [inv] views.",
            "inlineGeneral.enderChestViewer" to "Click-to-open [ec] views.",
            "inlineGeneral.posSuggestTp" to "Suggest a teleport command on [pos] click."
        )
    ),
    Section(
        "inline-chat/tokens.json", "Aliases for inline chat placeholders.",
        mapOf(
            "inlineTokens" to "Aliases per token.",
            "inlineTokens.item" to "Aliases for hand item.",
            "inlineTokens.inventory" to "Aliases for inventory.",
            "inlineTokens.enderChest" to "Aliases for ender chest.",
            "inlineTokens.health" to "Aliases for health.",
            "inlineTokens.hunger" to "Aliases for hunger.",
            "inlineTokens.armor" to "Aliases for armor.",
            "inlineTokens.xp" to "Aliases for XP.",
            "inlineTokens.level" to "Aliases for level.",
            "inlineTokens.pos" to "Aliases for position.",
            "inlineTokens.balance" to "Aliases for balance.",
            "inlineTokens.country" to "Aliases for country.",
            "inlineTokens.rank" to "Aliases for rank.",
            "inlineTokens.ping" to "Aliases for ping.",
            "inlineTokens.tps" to "Aliases for TPS."
        )
    ),
    Section(
        "inline-chat/style.json", "Inline chat colors and icons.",
        mapOf(
            "inlineStyle" to "Style of inline placeholders.",
            "inlineStyle.textColor" to "Text color.",
            "inlineStyle.linkColor" to "Link color.",
            "inlineStyle.bracketColor" to "Bracket color.",
            "inlineStyle.valueColor" to "Neutral value color.",
            "inlineStyle.positiveColor" to "Positive color.",
            "inlineStyle.warningColor" to "Warning color.",
            "inlineStyle.negativeColor" to "Negative color.",
            "inlineStyle.healthIcon" to "Health icon.",
            "inlineStyle.hungerIcon" to "Hunger icon.",
            "inlineStyle.armorIcon" to "Armor icon.",
            "inlineStyle.xpIcon" to "XP icon."
        )
    )
)

object Config : ConfigModule<Settings>("essentials", Settings.serializer(), Settings(), sections, sane = { it.sane() }) {
    init {
        Configs.onReload("essentials") { SkillServer.reapply(); null }
    }
}
