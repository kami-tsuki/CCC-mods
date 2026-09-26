package kami.essentials

import kami.essentials.display.Sidebar
import kami.libs.chat.Theme
import kami.libs.config.KamiConfig
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
)

object Config {
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

    private val file = KamiConfig("essentials", Settings(), sections, sane = { it.sane() })

    val s: Settings get() = file.value

    fun load() = file.load()
}
