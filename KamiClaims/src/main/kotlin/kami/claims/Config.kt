package kami.claims

import kami.libs.config.ConfigModule
import kami.libs.config.Section
import kami.libs.util.Time
import kotlinx.serialization.Serializable

@Serializable
data class Rule(
    val access: Map<Action, Access>, val machines: Boolean = false, val pvp: Boolean = false,
    val explosions: Boolean = false, val fire: Boolean = false
)

@Serializable
data class TypeDef(val price: Int, val period: Int = 1, val job: String? = null, val rule: Rule)

@Serializable
data class JobCfg(val type: String, val actions: List<Action>, val pay: Int, val quota: Int, val period: Int = 1, val blocks: List<String> = emptyList())

private fun rule(brk: Access, place: Access, interact: Access, container: Access, machines: Boolean = false) =
    Rule(mapOf(Action.BREAK to brk, Action.PLACE to place, Action.INTERACT to interact, Action.CONTAINER to container), machines)

private fun type(price: Int, period: Int = 1, job: String? = null, rule: Rule) = TypeDef(price, period, job, rule)

@Serializable
data class Announcement(val mc: Boolean = true, val discord: Boolean = true, val template: String = "") {
    companion object {
        val defaults = linkedMapOf(
            "founded" to Announcement(template = "{leader} founded the country {country}."),
            "levelUp" to Announcement(template = "{country} reached level {level}."),
            "allianceFormed" to Announcement(template = "{country} and {other} formed an alliance."),
            "allianceEnded" to Announcement(template = "{country} ended its alliance with {other}."),
            "provinceBecame" to Announcement(template = "{province} became a province of {parent}."),
            "provinceReleased" to Announcement(template = "{province} was released into independence by {parent}."),
            "provinceGiven" to Announcement(template = "{province} was handed from {parent} to {other}."),
            "warProvince" to Announcement(template = "{province} became a War-Province of {parent} until its war debt is paid."),
            "embargoOn" to Announcement(template = "{country} imposed an embargo on {other}."),
            "embargoOff" to Announcement(template = "{country} lifted its embargo on {other}."),
            "leaderHandover" to Announcement(template = "{old} handed the presidency of {country} to {leader}."),
            "succession" to Announcement(template = "The president of {country}, {old}, was inactive. {leader} took over."),
            "disbanded" to Announcement(template = "{country} was disbanded."),
            "warDeclared" to Announcement(template = "{country} declared war on {other}."),
            "warAnswered" to Announcement(template = "{other} answered the war declaration of {country}: {choice}."),
            "warPhase" to Announcement(template = "The war between {country} and {other} entered {phase}."),
            "warEnded" to Announcement(template = "The war between {country} and {other} ended: {result}."),
            "warJoined" to Announcement(template = "{country} joined the war against {other} on the side of {side}.")
        )
    }
}

@Serializable
data class PowerFactor(val weight: Double, val scale: Double)

@Serializable
data class WarSettings(
    val declarationDays: Int = 3,
    val preparationDays: Int = 3,
    val extendMaxDays: Int = 3,
    val ceasefireMaxDays: Int = 7,
    val ceasefireMax: Int = 2,
    val settlementDays: Int = 1,
    val cooldownDays: Int = 7,
    val pairCooldownDays: Int = 21,
    val foundingGraceDays: Int = 3,
    val downtimeGapMinutes: Int = 60,
    val reminderHours: Int = 24,
    val feePct: Double = 0.05,
    val feeMin: Long = 200,
    val feeDoublingDays: Int = 14,
    val stakePowerValue: Long = 100,
    val billHit: Long = 10,
    val billKill: Long = 100,
    val billBlock: Long = 200,
    val billExplosion: Long = 150,
    val billExplosionBlock: Long = 200,
    val billAnimal: Long = 25,
    val ownClaimsFactor: Double = 0.25,
    val strongExponent: Double = 0.5,
    val weakExponent: Double = 1.5,
    val exhaustionAttackerDay: Double = 5.0,
    val exhaustionDefenderDay: Double = 3.0,
    val exhaustionDeath: Double = 0.5,
    val exhaustionPerThousand: Double = 1.0,
    val exhaustionLossPerDayCap: Double = 10.0,
    val debtGarnish: Double = 0.3,
    val safeChunkLevel: Int = 10,
    val onlineMinutes: Int = 10,
    val afkMinutes: Int = 5,
    val returnMinutes: Int = 10,
    val fameMinPlaytimeHours: Double = 2.0,
    val fameChangeDays: Int = 7,
    val famePriorStars: Double = 3.0,
    val famePriorWeight: Double = 5.0,
    val fameFullWeightHours: Double = 20.0,
    val fameExtremeFactor: Double = 0.5,
    val fameShowWeight: Double = 3.0,
    val repStart: Double = 1000.0,
    val repMax: Double = 2000.0,
    val repScale: Double = 400.0,
    val repDecay: Double = 0.03,
    val repWeeklyGainCap: Double = 60.0,
    val repWeeklyLossCap: Double = 150.0,
    val repGuardLevel: Int = 10,
    val repFeeMin: Double = 0.5,
    val repFeeMax: Double = 2.0,
    val repTradeFee: Double = 0.1,
    val repTrade: Double = 4.0,
    val repTradeRepeat: Double = 0.5,
    val repTradeMax: Int = 3,
    val repTradeDays: Int = 7,
    val repTradeMinValue: Long = 100,
    val repWin: Double = 24.0,
    val repLoss: Double = 8.0,
    val repMaterial: Double = 5.0,
    val repMaterialMax: Double = 40.0,
    val repDealKept: Double = 8.0,
    val repDealBroken: Double = 40.0,
    val repPeaceful: Double = 8.0,
    val repWithdraw: Double = 10.0,
    val repWeaker: Double = 10.0,
    val repWeakerMax: Double = 40.0,
    val repDefendAlly: Double = 20.0,
    val repDropAlly: Double = 20.0,
    val repProvinceKept: Double = 5.0,
    val repProvinceKeptDays: Int = 30,
    val repRebellion: Double = 15.0,
    val repHighTribute: Double = 5.0,
    val repInstalment: Double = 3.0,
    val repPayoff: Double = 25.0,
    val repOverdueLoan: Double = 10.0,
    val repUpkeepLoss: Double = 5.0,
    val repCleanWar: Double = 10.0,
    val repCleanWarPct: Double = 0.25,
    val repFarDamage: Double = 20.0,
    val repFar: Double = 2.0,
    val repEmbargoSpam: Double = 10.0,
    val repEmbargoDays: Int = 7,
    val power: Map<String, PowerFactor> = linkedMapOf(
        "research" to PowerFactor(0.25, 40.0), "treasury" to PowerFactor(0.20, 50_000.0), "members" to PowerFactor(0.15, 10.0),
        "chunks" to PowerFactor(0.15, 200.0), "provinces" to PowerFactor(0.075, 3.0), "provinceMembers" to PowerFactor(0.075, 15.0),
        "allies" to PowerFactor(0.10, 3.0)
    )
) {
    fun sane() = copy(
        billHit = billHit.coerceAtLeast(0), billKill = billKill.coerceAtLeast(0), billBlock = billBlock.coerceAtLeast(0), billExplosion = billExplosion.coerceAtLeast(0),
        billExplosionBlock = billExplosionBlock.coerceAtLeast(0), billAnimal = billAnimal.coerceAtLeast(0), stakePowerValue = stakePowerValue.coerceAtLeast(0),
        exhaustionAttackerDay = exhaustionAttackerDay.coerceAtLeast(0.0), exhaustionDefenderDay = exhaustionDefenderDay.coerceAtLeast(0.0),
        exhaustionDeath = exhaustionDeath.coerceAtLeast(0.0), exhaustionPerThousand = exhaustionPerThousand.coerceAtLeast(0.0),
        exhaustionLossPerDayCap = exhaustionLossPerDayCap.coerceAtLeast(0.0),
        repWeeklyGainCap = repWeeklyGainCap.coerceAtLeast(0.0), repWeeklyLossCap = repWeeklyLossCap.coerceAtLeast(0.0), repWeakerMax = repWeakerMax.coerceAtLeast(0.0),
        famePriorWeight = famePriorWeight.coerceAtLeast(0.0), fameShowWeight = fameShowWeight.coerceAtLeast(0.0), fameMinPlaytimeHours = fameMinPlaytimeHours.coerceAtLeast(0.0),
        fameFullWeightHours = fameFullWeightHours.coerceAtLeast(0.1), fameChangeDays = fameChangeDays.coerceAtLeast(1), safeChunkLevel = safeChunkLevel.coerceAtLeast(1),
        repTradeMax = repTradeMax.coerceAtLeast(1), repTradeDays = repTradeDays.coerceAtLeast(1), repTradeMinValue = repTradeMinValue.coerceAtLeast(0), repEmbargoDays = repEmbargoDays.coerceAtLeast(1), repProvinceKeptDays = repProvinceKeptDays.coerceAtLeast(1),
        declarationDays = declarationDays.coerceAtLeast(1), preparationDays = preparationDays.coerceAtLeast(1),
        extendMaxDays = extendMaxDays.coerceAtLeast(0), ceasefireMaxDays = ceasefireMaxDays.coerceAtLeast(0), ceasefireMax = ceasefireMax.coerceAtLeast(0),
        settlementDays = settlementDays.coerceAtLeast(1), cooldownDays = cooldownDays.coerceAtLeast(0), pairCooldownDays = pairCooldownDays.coerceAtLeast(0),
        foundingGraceDays = foundingGraceDays.coerceAtLeast(0), downtimeGapMinutes = downtimeGapMinutes.coerceAtLeast(1), reminderHours = reminderHours.coerceAtLeast(1),
        feePct = feePct.coerceIn(0.0, 1.0), feeMin = feeMin.coerceAtLeast(0), feeDoublingDays = feeDoublingDays.coerceAtLeast(0),
        ownClaimsFactor = ownClaimsFactor.coerceIn(0.0, 1.0), strongExponent = strongExponent.coerceAtLeast(0.0), weakExponent = weakExponent.coerceAtLeast(0.0),
        debtGarnish = debtGarnish.coerceIn(0.0, 1.0), onlineMinutes = onlineMinutes.coerceAtLeast(0), afkMinutes = afkMinutes.coerceAtLeast(1),
        returnMinutes = returnMinutes.coerceAtLeast(0), fameExtremeFactor = fameExtremeFactor.coerceIn(0.0, 1.0), famePriorStars = famePriorStars.coerceIn(0.5, 5.0),
        repMax = repMax.coerceAtLeast(1.0), repStart = repStart.coerceIn(0.0, repMax.coerceAtLeast(1.0)), repScale = repScale.coerceAtLeast(1.0),
        repDecay = repDecay.coerceIn(0.0, 1.0), repTradeRepeat = repTradeRepeat.coerceIn(0.0, 1.0), repCleanWarPct = repCleanWarPct.coerceIn(0.0, 1.0),
        repTradeFee = repTradeFee.coerceIn(0.0, 1.0), repFeeMin = repFeeMin.coerceAtLeast(0.0), repFeeMax = repFeeMax.coerceAtLeast(repFeeMin.coerceAtLeast(0.0)),
        power = power.filterValues { it.weight >= 0 && it.scale > 0 }.takeIf { it.isNotEmpty() } ?: WarSettings().power
    )
}

@Serializable
data class Settings(
    val dayMillis: Long = Time.DAY_MS,
    val dimensions: List<String> = listOf("minecraft:overworld"),
    val defaultType: String = "civic",
    val freeChunks: Int = 9,
    val maxCountries: Int = 2,
    val freeBonusMembers: Int = 5,
    val freeBonusCap: Int = 6,
    val maxDebt: Int = 3,
    val reserveDays: Int = 5,
    val inactiveDays: Int = 31,
    val successionDays: Int = 31,
    val inviteDays: Int = 7,
    val capitalCooldownDays: Int = 7,
    val renameCost: Long = 50_000,
    val capitalMoveCost: Long = 10_000,
    val rentDefaults: Map<Claimant, Int> = mapOf(Claimant.CITIZEN to 5, Claimant.PARENT to 3, Claimant.PROVINCE to 8, Claimant.ALLIED to 8, Claimant.RANDOM to 10),
    val rentXp: Map<Claimant, Int> = mapOf(Claimant.CITIZEN to 100, Claimant.PARENT to 50, Claimant.PROVINCE to 50, Claimant.ALLIED to 50, Claimant.RANDOM to 1),
    val rankPlots: Map<Rank, Int> = mapOf(Rank.CITIZEN to 2, Rank.OFFICER to 4, Rank.CHANCELLOR to 9, Rank.PRESIDENT to 25),
    val guestPlots: Map<Claimant, Int> = mapOf(Claimant.PARENT to 2, Claimant.PROVINCE to 2, Claimant.ALLIED to 2, Claimant.RANDOM to 1),
    val maxRent: Int = 100,
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
    val tempBlockSecondsPerHardness: Double = 3.0,
    val tempBlockMinSeconds: Double = 2.0,
    val tempBlockLimit: Int = 4096,
    val pistonProtection: Boolean = true,
    val skyRule: Boolean = true,
    val skyFreeDimensions: List<String> = listOf("minecraft:the_nether", "minecraft:the_end"),
    val plotAllied: List<Action> = listOf(Action.INTERACT),
    val caps: Map<Cap, Rank> = mapOf(
        Cap.CLAIM to Rank.CHANCELLOR, Cap.CAPITAL to Rank.PRESIDENT, Cap.TAX to Rank.CHANCELLOR, Cap.RULES to Rank.CHANCELLOR,
        Cap.WITHDRAW to Rank.CHANCELLOR, Cap.INVITE to Rank.OFFICER, Cap.MEMBERS to Rank.OFFICER, Cap.RANK to Rank.CHANCELLOR,
        Cap.JOBS to Rank.OFFICER, Cap.PLOT to Rank.CITIZEN, Cap.DETAILS to Rank.CHANCELLOR, Cap.PROVINCE to Rank.CHANCELLOR, Cap.TRADE to Rank.CHANCELLOR, Cap.RESEARCH to Rank.CHANCELLOR, Cap.HOUSING to Rank.OFFICER
    ),
    val provinceTaxRateBounds: List<Double> = listOf(0.0, 0.5),
    val maxProvinceDebt: Int = 3,
    val independenceCooldownDays: Int = 3,
    val ledgerSize: Int = 300,
    val historyDays: Int = 90,
    val notify: Boolean = true,
    val titles: Boolean = true,
    val broadcast: Boolean = true,
    val guiCooldown: Int = 4,
    val mailLimit: Int = 10,
    val announce: Map<String, Announcement> = Announcement.defaults,
    val war: WarSettings = WarSettings(),
    val types: Map<String, TypeDef> = linkedMapOf(
        "civic" to type(1, rule = rule(Access.CITIZEN, Access.CITIZEN, Access.ALLIED, Access.CITIZEN)),
        "mining" to type(3, job = "miner", rule = rule(Access.JOB, Access.JOB, Access.ALLIED, Access.JOB, true)),
        "farming" to type(2, job = "farmer", rule = rule(Access.JOB, Access.JOB, Access.ALLIED, Access.JOB, true)),
        "forestry" to type(2, job = "forester", rule = rule(Access.JOB, Access.JOB, Access.ALLIED, Access.JOB, true)),
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
    fun can(rank: Rank?, cap: Cap) = rank != null && rank >= min(cap)

    val dimensionSet: Set<String> by lazy { dimensions.toHashSet() }
    val freeBlockSet: Set<String> by lazy { freeBlocks.toHashSet() }
    val skyFreeSet: Set<String> by lazy { skyFreeDimensions.toHashSet() }
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
        renameCost = renameCost.coerceAtLeast(0),
        capitalMoveCost = capitalMoveCost.coerceAtLeast(0),
        maxRect = maxRect.coerceIn(1, 4096),
        defaultType = defaultType.takeIf { it in valid } ?: valid.keys.first(),
        provinceTaxRateBounds = provinceTaxRateBounds.takeIf { it.size == 2 && it[0] in 0.0..it[1] && it[1] <= 1.0 } ?: listOf(0.0, 0.5),
        maxProvinceDebt = maxProvinceDebt.coerceAtLeast(1),
        independenceCooldownDays = independenceCooldownDays.coerceAtLeast(0),
        ledgerSize = ledgerSize.coerceIn(20, 5000),
        historyDays = historyDays.coerceIn(7, 730),
        announce = Announcement.defaults + announce,
        war = war.sane()
    )
}

private val sections = listOf(
    Section(
        "general.json", "Countries, upkeep and time. Durations count in real days.",
        mapOf(
            "dayMillis" to "Length of one upkeep day in milliseconds. 86400000 is one real day.",
            "dimensions" to "Dimensions where land can be claimed.",
            "freeChunks" to "Chunks every country gets for free, the capital included.",
            "maxCountries" to "Most independent countries the server allows at once; provinces do not count. 0 means no limit.",
            "freeBonusMembers" to "Members needed for one extra free chunk.",
            "freeBonusCap" to "Most extra free chunks a country can earn from members.",
            "maxDebt" to "Unpaid days before a chunk is lost.",
            "reserveDays" to "Days a lost chunk stays reserved for its old country.",
            "inactiveDays" to "Days without any member online before a country loses its free chunks.",
            "successionDays" to "Days a president can be offline before the chancellor takes over.",
            "inviteDays" to "Days an invite stays open.",
            "capitalCooldownDays" to "Days between two capital moves.",
            "renameCost" to "Spurs from the treasury for renaming a country when it has no rename token.",
            "capitalMoveCost" to "Spurs from the treasury for moving the capital when the country has no capital move token.",
            "maxRect" to "Most chunks one area selection may cover.",
            "maxCatchUp" to "Most missed days worked off at once after downtime.",
            "nameLength" to "Shortest and longest country name.",
            "provinceTaxRateBounds" to "Lowest and highest percent tribute a province can pay, 0 to 1.",
            "maxProvinceDebt" to "Missed tribute payments before the ruling country gets an urgent warning.",
            "independenceCooldownDays" to "Days a province must wait after a declined independence request before asking again.",
            "ledgerSize" to "Treasury transactions kept per country for the ledger.",
            "historyDays" to "Days of statistics kept per country for charts and forecasts.",
            "guiCooldown" to "Ticks between two GUI actions of one player."
        )
    ),
    Section(
        "chunk-types.json", "Chunk types, their daily price and who may do what.",
        mapOf(
            "defaultType" to "Type of a freshly claimed chunk.",
            "types" to "price is paid every period days. Access: NONE, OFFICER, ASSIGNED, JOB, WORKER, CITIZEN, ALLIED or ANY.",
            "types.*.period" to "Days between two payments.",
            "types.*.job" to "Job that works in this chunk type, or null.",
            "types.*.rule" to "Default rules. Countries can change them in game.",
            "types.*.rule.machines" to "Create machines may break blocks here.",
            "types.*.rule.pvp" to "Players may fight here.",
            "types.*.rule.explosions" to "Explosions break blocks here.",
            "types.*.rule.fire" to "Fire may spread into this chunk."
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
            "rentDefaults" to "Default daily rent of a plot per group: CITIZEN, PARENT, PROVINCE, ALLIED, RANDOM.",
            "rentXp" to "Level XP a country earns per rent payment, per group.",
            "rankPlots" to "Default plot limit per rank.",
            "guestPlots" to "Default plot limit per group of non-citizens.",
            "maxRent" to "Highest daily rent a country can set.",
            "plotAllied" to "What allied players may do on a plot."
        )
    ),
    Section(
        "protection.json", "Protection outside of countries and a few global rules.",
        mapOf(
            "freeBlocks" to "Blocks anyone may place anywhere, like train tracks.",
            "nomanslandAllow" to "What players may do in unclaimed land: BREAK, PLACE, INTERACT or CONTAINER. Blocks and entities in the kami_claims:wild_blocked tags (pots, depots, item frames) stay unusable regardless.",
            "nomanslandPvp" to "Players may fight in unclaimed land.",
            "mobGriefing" to "Mobs like creepers and endermen may change blocks in the claim dimensions.",
            "nomanslandExplosions" to "Explosions break blocks in unclaimed land.",
            "nomanslandFire" to "Fire spreads in unclaimed land.",
            "nomanslandFluid" to "Fluids flow in unclaimed land.",
            "tempBlockSecondsPerHardness" to "Blocks in the kami_claims:temp_blocks tag (dirt, stone, deepslate) may be placed in unclaimed land when placing is not allowed there, but crumble after hardness times this many seconds. Pistons cannot move them.",
            "tempBlockMinSeconds" to "The shortest time a temporary block lasts, in seconds.",
            "tempBlockLimit" to "How many temporary blocks may exist at once on the server. Placing more is refused until some crumble.",
            "pistonProtection" to "Stop pistons from pushing blocks across claim borders.",
            "skyRule" to "Crops and saplings only grow with open sky above them. Only blocks with a full top or bottom face block it, like full blocks, slabs, stairs and farmland. Thin blocks like torches, chains and fences, leaves, DynamicTrees branches, any glass block or variant and the kami_claims:sky_transparent tag let light through. Applies everywhere, claimed or not.",
            "skyFreeDimensions" to "Dimensions where the open sky rule is not applied, like the Nether."
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
            "broadcast" to "Send country events (founding, levels, diplomacy, leaders, disbands) to global chat.",
            "mailLimit" to "Most messages kept for an offline member."
        )
    ),
    Section(
        "war.json", "Wars, war bills, reputation and fame.",
        mapOf(
            "war" to "Wars between countries. Durations are real days, hours or minutes as named; Spur values are per event.",
            "war.declarationDays" to "Days the defender has to answer a declaration.",
            "war.preparationDays" to "Days between fighting back and the start of the war.",
            "war.extendMaxDays" to "Most days negotiation can add to one phase.",
            "war.ceasefireMaxDays" to "Longest ceasefire in days.",
            "war.ceasefireMax" to "Most ceasefires per war.",
            "war.settlementDays" to "Days of negotiation in settlement before payment.",
            "war.cooldownDays" to "Days after a war in which neither main country can declare or be declared on.",
            "war.pairCooldownDays" to "Days before the same attacker can declare on the same target again.",
            "war.foundingGraceDays" to "Days after founding in which a country cannot declare or be declared on.",
            "war.downtimeGapMinutes" to "Server downtime in minutes after which all war timers are paused for the gap.",
            "war.reminderHours" to "Hours between reminders to a defender that has not answered.",
            "war.feePct" to "Declaration fee as part of the stake value, 0 to 1.",
            "war.feeMin" to "Lowest declaration fee in Spur.",
            "war.feeDoublingDays" to "The fee doubles for each war the attacker declared within this many days.",
            "war.stakePowerValue" to "Stake value of vassalage or provinces per point of power.",
            "war.billHit" to "Bill per damaging hit on an enemy combatant.",
            "war.billKill" to "Bill per kill of an enemy combatant.",
            "war.billBlock" to "Bill per enemy block broken.",
            "war.billExplosion" to "Bill per explosion.",
            "war.billExplosionBlock" to "Bill per block destroyed by an explosion.",
            "war.billAnimal" to "Bill per passive or tamed animal killed in enemy claims.",
            "war.ownClaimsFactor" to "Factor for damage dealt inside your own claims, 0 to 1.",
            "war.strongExponent" to "Bill multiplier exponent of the stronger side.",
            "war.weakExponent" to "Bill multiplier exponent of the weaker side.",
            "war.exhaustionAttackerDay" to "Exhaustion percent the attacker gains per war day.",
            "war.exhaustionDefenderDay" to "Exhaustion percent the defender gains per war day.",
            "war.exhaustionDeath" to "Exhaustion percent per death caused by the enemy.",
            "war.exhaustionPerThousand" to "Exhaustion percent per 1000 Spur on the enemy's bill.",
            "war.exhaustionLossPerDayCap" to "Cap on exhaustion percent from deaths and the enemy's bill, per war day (at least one day).",
            "war.debtGarnish" to "Part of a debtor's deposits and tax income that goes to the creditor, 0 to 1.",
            "war.safeChunkLevel" to "Country level that unlocks the first war-protected chunk.",
            "war.onlineMinutes" to "Minutes a combatant must be logged in to count as online.",
            "war.afkMinutes" to "Minutes without input after which a combatant counts as AFK.",
            "war.returnMinutes" to "Minutes after the last online combatant leaves before offline protection returns.",
            "war.fameMinPlaytimeHours" to "Playtime in hours a player needs to rate a country.",
            "war.fameChangeDays" to "Days before a player may change a rating.",
            "war.famePriorStars" to "Stars new countries are pulled toward.",
            "war.famePriorWeight" to "Weight of the prior stars.",
            "war.fameFullWeightHours" to "Playtime in hours for a full rating weight.",
            "war.fameExtremeFactor" to "Weight factor for voters whose ratings are at least 80% the lowest or highest, 0 to 1.",
            "war.fameShowWeight" to "Total rating weight needed before fame is shown.",
            "war.repStart" to "Reputation of a new country and the value it decays toward.",
            "war.repMax" to "Highest reputation.",
            "war.repScale" to "Elo scale of the expected result.",
            "war.repDecay" to "Part of the distance to the start value recovered each day, 0 to 1.",
            "war.repWeeklyGainCap" to "Most reputation one source can add per week.",
            "war.repWeeklyLossCap" to "Most reputation lost per week in total.",
            "war.repGuardLevel" to "Agree and withdraw only change reputation if both countries reach this level.",
            "war.repFeeMin" to "Lowest reputation factor on the declaration fee.",
            "war.repFeeMax" to "Highest reputation factor on the declaration fee.",
            "war.repTradeFee" to "Largest change of the cross-country trade fee at the reputation extremes, 0 to 1.",
            "war.repTrade" to "K factor of a trade with another country.",
            "war.repTradeRepeat" to "Factor for repeated trades with the same partner, 0 to 1.",
            "war.repTradeMax" to "Most repeated trades with one partner that count per window.",
            "war.repTradeDays" to "Days of the repeated trade window.",
            "war.repTradeMinValue" to "Smallest order value in Spur that counts as a trade for reputation.",
            "war.repWin" to "K factor of a war win.",
            "war.repLoss" to "K factor of a war loss.",
            "war.repMaterial" to "Reputation lost per use of war material.",
            "war.repMaterialMax" to "Most reputation lost to war material per war.",
            "war.repDealKept" to "Reputation for a kept deal.",
            "war.repDealBroken" to "Reputation lost for a broken deal.",
            "war.repPeaceful" to "Reputation for a declaration settled peacefully.",
            "war.repWithdraw" to "Reputation lost for withdrawing a declaration.",
            "war.repWeaker" to "Reputation lost per doubling of the power ratio when declaring on a weaker country.",
            "war.repWeakerMax" to "Most reputation lost for a single declaration on a weaker country.",
            "war.repDefendAlly" to "Reputation for defending an ally or province.",
            "war.repDropAlly" to "Reputation lost for dropping an ally during its war.",
            "war.repProvinceKept" to "Reputation for keeping a province at low tribute.",
            "war.repProvinceKeptDays" to "Days a province must be kept for that reward.",
            "war.repRebellion" to "Reputation lost when a province rebels.",
            "war.repHighTribute" to "Reputation lost for high province tribute.",
            "war.repInstalment" to "Reputation for a loan instalment paid on time.",
            "war.repPayoff" to "Reputation for paying off a loan.",
            "war.repOverdueLoan" to "Reputation lost per week a loan stays overdue.",
            "war.repUpkeepLoss" to "Reputation lost each upkeep cycle that loses chunks.",
            "war.repCleanWar" to "Reputation for a bill under the clean war share of the stake value.",
            "war.repCleanWarPct" to "Clean war share of the stake value, 0 to 1.",
            "war.repFarDamage" to "Most reputation lost for damage far from the front.",
            "war.repFar" to "Reputation lost per animal killed or enemy block broken far from the own front during a war.",
            "war.repEmbargoSpam" to "Reputation lost per embargo or tariff flip on the same country.",
            "war.repEmbargoDays" to "Days of the embargo flip window.",
            "war.power" to "Power score factors: weight and the scale where the factor reaches about three quarters. Keys: research, treasury, members, chunks, provinces, provinceMembers, allies."
        )
    ),
    Section(
        "discord.json", "Country events sent to global chat and the Discord chat channel. Needs the Discord bot of KamiEssentials for Discord.",
        mapOf(
            "announce" to "Per event: mc sends it to global chat (needs broadcast in messages.json), discord sends the template to Discord. Templates fill {country}, {leader}, {old}, {level}, {other}, {province}, {parent}, {choice}, {phase}, {result} and {side} depending on the event: founded {country} {leader}, levelUp {country} {level}, allianceFormed and allianceEnded {country} {other}, provinceBecame, provinceReleased, provinceGiven and warProvince {province} {parent} {other}, embargoOn and embargoOff {country} {other}, leaderHandover and succession {country} {old} {leader}, disbanded {country}, warDeclared {country} {other}, warAnswered {country} {other} {choice}, warPhase {country} {other} {phase}, warEnded {country} {other} {result}, warJoined {country} {other} {side}.",
            "announce.*.mc" to "Send this event to global chat.",
            "announce.*.discord" to "Send this event to Discord.",
            "announce.*.template" to "Discord text with placeholders in braces."
        )
    )
)

object Config : ConfigModule<Settings>("claims", Settings.serializer(), Settings(), sections, "kami_claims.json", { it.sane() })

