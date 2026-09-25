package kami.claims

import kami.libs.config.KamiConfig
import kami.libs.config.Section
import kotlinx.serialization.Serializable

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
    private val sections = listOf(
        Section(
            "general.json", "Countries, upkeep and time. Durations count in real days.",
            mapOf(
                "dayMillis" to "Length of one upkeep day in milliseconds. 86400000 is one real day.",
                "dimensions" to "Dimensions where land can be claimed.",
                "freeChunks" to "Chunks every country gets for free, the capital included.",
                "freeBonusMembers" to "Members needed for one extra free chunk.",
                "freeBonusCap" to "Most extra free chunks a country can earn from members.",
                "maxDebt" to "Unpaid days before a chunk is lost.",
                "reserveDays" to "Days a lost chunk stays reserved for its old country.",
                "inactiveDays" to "Days without any member online before a country loses its free chunks.",
                "successionDays" to "Days a president can be offline before the chancellor takes over.",
                "inviteDays" to "Days an invite stays open.",
                "capitalCooldownDays" to "Days between two capital moves.",
                "maxRect" to "Most chunks one area selection may cover.",
                "maxCatchUp" to "Most missed days worked off at once after downtime.",
                "nameLength" to "Shortest and longest country name.",
                "provinceTaxRateBounds" to "Lowest and highest percent tribute a province can pay, 0 to 1.",
                "maxProvinceDebt" to "Missed tribute payments before the ruling country gets an urgent warning.",
                "guiCooldown" to "Ticks between two GUI actions of one player."
            )
        ),
        Section(
            "chunk-types.json", "Chunk types, their daily price and who may do what.",
            mapOf(
                "defaultType" to "Type of a freshly claimed chunk.",
                "types" to "price is paid every period days. Access: NONE, OFFICER, JOB, WORKER, CITIZEN, ALLIED or ANY.",
                "types.*.period" to "Days between two payments.",
                "types.*.job" to "Job that works in this chunk type, or null.",
                "types.*.rule" to "Default rules. Countries can change them in game.",
                "types.*.rule.machines" to "Create machines may break blocks here.",
                "types.*.rule.pvp" to "Players may fight here.",
                "types.*.rule.explosions" to "Explosions break blocks here.",
                "types.*.rule.fire" to "Fire may spread into this chunk.",
                "types.*.rule.fluid" to "Fluids may flow into this chunk."
            )
        ),
        Section(
            "jobs.json", "Jobs and their daily pay. Countries can change pay and quota in game.",
            mapOf(
                "jobShare" to "Most of the daily income that may go to job pay, 0 to 1.",
                "maxJobPay" to "Highest pay a country can set for one job.",
                "jobs" to "type is the chunk type, actions count towards the quota, blocks limits what counts (tags start with #).",
                "jobs.*.quota" to "Actions needed per period to get paid.",
                "jobs.*.period" to "Days per work period."
            )
        ),
        Section(
            "plots.json", "Player plots inside residential chunks.",
            mapOf(
                "maxPlots" to "Most plots one player can own.",
                "residentialTax" to "Default daily rent of a plot.",
                "shutdownDays" to "Unpaid days before the owner is locked out.",
                "releaseDays" to "Days after the lockout before others can take the plot.",
                "plotAllied" to "What allied players may do on a plot."
            )
        ),
        Section(
            "protection.json", "Protection outside of countries and a few global rules.",
            mapOf(
                "freeBlocks" to "Blocks anyone may place anywhere, like train tracks.",
                "nomanslandAllow" to "What players may do in unclaimed land: BREAK, PLACE, INTERACT or CONTAINER.",
                "nomanslandPvp" to "Players may fight in unclaimed land.",
                "mobGriefing" to "Mobs like creepers and endermen may change blocks in the claim dimensions.",
                "nomanslandExplosions" to "Explosions break blocks in unclaimed land.",
                "nomanslandFire" to "Fire spreads in unclaimed land.",
                "nomanslandFluid" to "Fluids flow in unclaimed land.",
                "pistonProtection" to "Stop pistons from pushing blocks across claim borders."
            )
        ),
        Section(
            "ranks.json", "Lowest rank that may use each feature.",
            mapOf("caps" to "Ranks from low to high: BANISHED, ALLIED, CITIZEN, OFFICER, CHANCELLOR, PRESIDENT.")
        ),
        Section(
            "messages.json", "Messages and notifications.",
            mapOf(
                "notify" to "Show the country name when a player walks into other land.",
                "titles" to "Use a big title when crossing a border between countries.",
                "broadcast" to "Tell the whole server when a country is founded.",
                "mailLimit" to "Most messages kept for an offline member."
            )
        )
    )

    private val file = KamiConfig("claims", Settings(), sections, legacy = "kami_claims.json", sane = { it.sane() })

    var s: Settings
        get() = file.value
        set(value) { file.value = value }

    fun load() = file.load()
}
