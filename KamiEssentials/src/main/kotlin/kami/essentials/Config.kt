package kami.essentials

import kami.essentials.display.Sidebar
import kami.libs.config.KamiConfig
import kami.libs.config.Section
import kotlinx.serialization.Serializable

@Serializable
data class Settings(
    val tradeDistance: Int = 16,
    val tradeConfirmSeconds: Int = 3,
    val tradeRequestSeconds: Int = 60,
    val chat: Boolean = true,
    val joinLeave: Boolean = true,
    val deaths: Boolean = true,
    val tab: Boolean = true,
    val tabTitle: String = "Kami",
    val sidebar: Boolean = true,
    val sidebarTitle: String = "Kami",
    val sidebarLines: List<String> = listOf("country", "balance", "", "playtime", "kills", "deaths", "", "online", "ping"),
)

private fun Settings.sane() = copy(
    tradeDistance = tradeDistance.coerceAtLeast(-2),
    tradeConfirmSeconds = tradeConfirmSeconds.coerceIn(0, 30),
    tradeRequestSeconds = tradeRequestSeconds.coerceIn(10, 600),
    sidebarLines = sidebarLines.filter { it.isEmpty() || it in Sidebar.LINES }.take(15),
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
                "deaths" to "Replace the death messages."
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
        )
    )

    private val file = KamiConfig("essentials", Settings(), sections, sane = { it.sane() })

    val s: Settings get() = file.value

    fun load() = file.load()
}
