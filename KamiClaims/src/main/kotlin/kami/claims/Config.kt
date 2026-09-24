package kami.claims

import kami.libs.config.Configs
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import java.nio.file.Files

@Serializable
data class Rule(
    val access: Map<Action, Access>, val machines: Boolean = false, val pvp: Boolean = false,
    val explosions: Boolean = false, val fire: Boolean = false, val fluid: Boolean = false
)

@Serializable
data class TypeDef(val price: Int, val period: Int = 1, val job: String? = null, val rule: Rule)

@Serializable
data class JobCfg(val type: String, val actions: List<Action>, val pay: Int, val quota: Int, val period: Int = 1, val blocks: List<String> = emptyList())

private fun rule(brk: Access, place: Access, interact: Access, container: Access, machines: Boolean = false) =
    Rule(mapOf(Action.BREAK to brk, Action.PLACE to place, Action.INTERACT to interact, Action.CONTAINER to container), machines)

private fun type(price: Int, period: Int = 1, job: String? = null, rule: Rule) = TypeDef(price, period, job, rule)

@Serializable
data class Settings(
    val dayMillis: Long = 86_400_000,
    val dimensions: List<String> = listOf("minecraft:overworld"),
    val defaultType: String = "civic",
    val freeChunks: Int = 9,
    val freeBonusMembers: Int = 5,
    val freeBonusCap: Int = 6,
    val maxDebt: Int = 3,
    val reserveDays: Int = 5,
    val inactiveDays: Int = 31,
    val successionDays: Int = 31,
    val inviteDays: Int = 7,
    val capitalCooldownDays: Int = 7,
    val maxPlots: Int = 4,
    val residentialTax: Int = 5,
    val shutdownDays: Int = 3,
    val releaseDays: Int = 3,
    val jobShare: Double = 0.6,
    val maxRect: Int = 400,
    val maxJobPay: Int = 100,
    val maxCatchUp: Int = 4000,
    val nameLength: List<Int> = listOf(3, 24),
    val freeBlocks: List<String> = listOf("create:track"),
    val nomanslandAllow: List<Action> = listOf(Action.INTERACT, Action.CONTAINER),
    val nomanslandPvp: Boolean = false,
    val mobGriefing: Boolean = false,
    val nomanslandExplosions: Boolean = false,
    val nomanslandFire: Boolean = false,
    val nomanslandFluid: Boolean = false,
    val pistonProtection: Boolean = true,
    val plotAllied: List<Action> = listOf(Action.INTERACT),
    val caps: Map<Cap, Rank> = mapOf(
        Cap.CLAIM to Rank.CHANCELLOR, Cap.CAPITAL to Rank.PRESIDENT, Cap.TAX to Rank.CHANCELLOR, Cap.RULES to Rank.CHANCELLOR,
        Cap.WITHDRAW to Rank.CHANCELLOR, Cap.INVITE to Rank.OFFICER, Cap.MEMBERS to Rank.OFFICER, Cap.RANK to Rank.CHANCELLOR,
        Cap.JOBS to Rank.OFFICER, Cap.PLOT to Rank.CITIZEN, Cap.DETAILS to Rank.CHANCELLOR, Cap.PROVINCE to Rank.CHANCELLOR
    ),
    val provinceTaxRateBounds: List<Double> = listOf(0.0, 0.5),
    val maxProvinceDebt: Int = 3,
    val notify: Boolean = true,
    val titles: Boolean = true,
    val broadcast: Boolean = true,
    val guiCooldown: Int = 4,
    val mailLimit: Int = 10,
    val coins: Map<String, Int> = linkedMapOf(
        "numismatics:spur" to 1,
        "numismatics:bevel" to 8,
        "numismatics:sprocket" to 16,
        "numismatics:cog" to 64,
        "numismatics:crown" to 512,
        "numismatics:sun" to 4096
    ),
    val types: Map<String, TypeDef> = linkedMapOf(
        "civic" to type(1, rule = rule(Access.CITIZEN, Access.CITIZEN, Access.ALLIED, Access.CITIZEN)),
        "mining" to type(3, job = "miner", rule = rule(Access.JOB, Access.WORKER, Access.ALLIED, Access.WORKER, true)),
        "farming" to type(2, job = "farmer", rule = rule(Access.JOB, Access.JOB, Access.ALLIED, Access.WORKER, true)),
        "forestry" to type(2, job = "forester", rule = rule(Access.JOB, Access.JOB, Access.ALLIED, Access.WORKER, true)),
        "factory" to type(3, job = "worker", rule = rule(Access.WORKER, Access.WORKER, Access.WORKER, Access.WORKER, true)),
        "market" to type(4, rule = rule(Access.CITIZEN, Access.CITIZEN, Access.ANY, Access.CITIZEN)),
        "residential" to type(5, rule = rule(Access.OFFICER, Access.OFFICER, Access.OFFICER, Access.OFFICER)),
        "infrastructure" to type(1, rule = rule(Access.OFFICER, Access.OFFICER, Access.ANY, Access.OFFICER)),
        "wilderness" to type(1, 7, rule = rule(Access.NONE, Access.NONE, Access.ALLIED, Access.ALLIED))
    ),
    val jobs: Map<String, JobCfg> = linkedMapOf(
        "miner" to JobCfg("mining", listOf(Action.BREAK), 6, 64, 1, listOf("#c:ores", "#minecraft:base_stone_overworld")),
        "farmer" to JobCfg("farming", listOf(Action.BREAK), 4, 48, 1, listOf("#minecraft:crops")),
        "forester" to JobCfg("forestry", listOf(Action.BREAK), 4, 32, 1, listOf("#minecraft:logs")),
        "worker" to JobCfg("factory", listOf(Action.BREAK, Action.PLACE), 3, 100, 1)
    )
) {
    fun min(cap: Cap) = caps[cap] ?: Rank.PRESIDENT

    val dimensionSet: Set<String> by lazy { dimensions.toHashSet() }
    val freeBlockSet: Set<String> by lazy { freeBlocks.toHashSet() }
    val nomanslandAllowSet: Set<Action> by lazy { nomanslandAllow.toHashSet() }
    val plotAlliedSet: Set<Action> by lazy { plotAllied.toHashSet() }
}

private fun Settings.sane(): Settings {
    val valid = types.filterValues { it.price >= 0 && it.period >= 1 }.ifEmpty { Settings().types }
    return copy(
        dayMillis = dayMillis.coerceAtLeast(1000),
        freeBonusMembers = freeBonusMembers.coerceAtLeast(1),
        maxDebt = maxDebt.coerceAtLeast(1),
        jobShare = jobShare.coerceIn(0.0, 1.0),
        nameLength = nameLength.takeIf { it.size == 2 && it[0] in 1..it[1] && it[1] <= 48 } ?: listOf(3, 24),
        types = valid,
        caps = Cap.values().associateWith { caps[it] ?: Settings().caps.getValue(it) },
        maxRect = maxRect.coerceIn(1, 4096),
        defaultType = defaultType.takeIf { it in valid } ?: valid.keys.first(),
        provinceTaxRateBounds = provinceTaxRateBounds.takeIf { it.size == 2 && it[0] in 0.0..it[1] && it[1] <= 1.0 } ?: listOf(0.0, 0.5),
        maxProvinceDebt = maxProvinceDebt.coerceAtLeast(1)
    )
}

object Config {
    private val json = Configs.json { coerceInputValues = true }
    var s = Settings()

    fun load() {
        val path = Configs.file("kami_claims.json")
        val parsed = if (Files.exists(path)) runCatching { json.decodeFromString<Settings>(Files.readString(path)) } else null
        s = (parsed?.getOrNull() ?: Settings()).sane()
        parsed?.exceptionOrNull()?.let { KamiClaims.LOG.error("Invalid kami_claims.json, using defaults without overwriting it", it) }
            ?: Files.writeString(path, json.encodeToString(s))
    }
}
