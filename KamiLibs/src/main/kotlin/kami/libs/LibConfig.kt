package kami.libs

import kami.libs.config.KamiConfig
import kami.libs.config.Section
import kotlinx.serialization.Serializable

@Serializable
data class LibSettings(
    val coins: Map<String, Int> = linkedMapOf(
        "numismatics:spur" to 1, "numismatics:bevel" to 8, "numismatics:sprocket" to 16,
        "numismatics:cog" to 64, "numismatics:crown" to 512, "numismatics:sun" to 4096
    ),
    val coinAlertRatio: Double = 1.5,
    val coinAlertMinVolume: Long = 10_000,
    val coinFlagNet: Long = 50_000,
    val unexplainedAlert: Long = 10_000,
    val unexplainedSingle: Long = 5_000,
    val stats: Map<String, Boolean> = linkedMapOf(
        "server" to true, "session" to true, "combat" to true, "mob" to true, "block" to true, "craft" to true,
        "move" to true, "money" to true, "market" to true, "country" to true, "war" to true, "research" to true, "discord" to true
    ),
    val statsSampleSeconds: Int = 300
)

object LibConfig {
    val file = KamiConfig(
        "library", LibSettings(),
        listOf(
            Section(
                "coins.json", "Coins used as money by KamiClaims and KamiEconomy.",
                mapOf("coins" to "Coin item and what it is worth in spurs. The smallest coin should be worth 1.")
            ),
            Section(
                "coin-watch.json", "Alerts for physical coins paid in and out through bank terminals.",
                mapOf(
                    "coinAlertRatio" to "Warn when terminal deposits of the last 7 days exceed withdrawals by this factor.",
                    "coinAlertMinVolume" to "Spurs moved through terminals in 7 days before the ratio warning applies.",
                    "coinFlagNet" to "Flag a player whose terminal deposits exceed withdrawals by this many spurs.",
                    "unexplainedAlert" to "Alert admins when bank balance changes without a known cause sum to this many spurs in one minute.",
                    "unexplainedSingle" to "Alert admins when a single bank balance change without a known cause reaches this many spurs."
                )
            ),
            Section(
                "stats.json", "Statistics collected for the admin app. Everything is kept forever.",
                mapOf(
                    "stats" to "Collect a statistics group (false turns it off): server, session, combat, mob, block, craft, move, money, market, country, war, research, discord.",
                    "statsSampleSeconds" to "Seconds between samples of gauges like online players, ping, TPS and country treasuries."
                )
            )
        ),
        sane = { s ->
            s.copy(
                coins = s.coins.filterValues { it > 0 }.ifEmpty { LibSettings().coins },
                coinAlertRatio = s.coinAlertRatio.coerceAtLeast(1.0),
                coinAlertMinVolume = s.coinAlertMinVolume.coerceAtLeast(0),
                coinFlagNet = s.coinFlagNet.coerceAtLeast(0),
                unexplainedAlert = s.unexplainedAlert.coerceAtLeast(1),
                unexplainedSingle = s.unexplainedSingle.coerceAtLeast(1),
                stats = LibSettings().stats + s.stats,
                statsSampleSeconds = s.statsSampleSeconds.coerceAtLeast(30)
            )
        }
    )

    val s: LibSettings get() = file.value
}
