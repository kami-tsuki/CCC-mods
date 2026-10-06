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
    val unexplainedSingle: Long = 5_000
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
            )
        ),
        sane = { s ->
            s.copy(
                coins = s.coins.filterValues { it > 0 }.ifEmpty { LibSettings().coins },
                coinAlertRatio = s.coinAlertRatio.coerceAtLeast(1.0),
                coinAlertMinVolume = s.coinAlertMinVolume.coerceAtLeast(0),
                coinFlagNet = s.coinFlagNet.coerceAtLeast(0),
                unexplainedAlert = s.unexplainedAlert.coerceAtLeast(1),
                unexplainedSingle = s.unexplainedSingle.coerceAtLeast(1)
            )
        }
    )

    val s: LibSettings get() = file.value
}
