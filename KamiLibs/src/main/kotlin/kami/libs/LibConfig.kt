package kami.libs

import kami.libs.config.KamiConfig
import kami.libs.config.Section
import kotlinx.serialization.Serializable

@Serializable
data class LibSettings(
    val coins: Map<String, Int> = linkedMapOf(
        "numismatics:spur" to 1, "numismatics:bevel" to 8, "numismatics:sprocket" to 16,
        "numismatics:cog" to 64, "numismatics:crown" to 512, "numismatics:sun" to 4096
    )
)

object LibConfig {
    val file = KamiConfig(
        "library", LibSettings(),
        listOf(
            Section(
                "coins.json", "Coins used as money by KamiClaims and KamiEconomy.",
                mapOf("coins" to "Coin item and what it is worth in spurs. The smallest coin should be worth 1.")
            )
        ),
        sane = { s -> s.copy(coins = s.coins.filterValues { it > 0 }.ifEmpty { LibSettings().coins }) }
    )

    val s: LibSettings get() = file.value
}
