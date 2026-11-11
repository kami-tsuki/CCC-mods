package kami.claims.gov

import kami.claims.Config
import kami.claims.Country
import kotlinx.serialization.Serializable

@Serializable enum class TaskKind { HARVEST, MINE, CHOP, BUILD, CRAFT, SMELT, HERD, FISH }
@Serializable enum class JobScope { COUNTRY, TYPE, SECTOR }
@Serializable enum class PayModel { FIXED, PIECE, RATION }
@Serializable enum class Assignment { OPEN, APPOINTED, CONTRACT }

@Serializable
class TaskSpec(val kind: TaskKind, var filter: String = "", var cap: Int = 0)

@Serializable
class JobSpec(
    val id: String,
    var name: String,
    var color: Int = 0,
    var scope: JobScope = JobScope.TYPE,
    var scopeRef: String = "",
    val tasks: MutableList<TaskSpec> = mutableListOf(),
    var pay: Int = 0,
    var quota: Int = 0,
    var period: Int = 1,
    var payModel: PayModel = PayModel.FIXED,
    var assignment: Assignment = Assignment.APPOINTED,
    var active: Boolean = true,
    var definedBy: String = "",
    var changedDay: Long = 0,
    var basePay: Int = 0,
    var perms: Set<Perm> = emptySet()
)

@Serializable
class Contract(
    val id: String,
    val job: String,
    val player: String,
    val offeredBy: String,
    var pay: Int,
    var quota: Int,
    var period: Int,
    val until: Long,
    var accepted: Boolean = false
)

object Jobs {
    private val taskOfType = mapOf("mining" to TaskKind.MINE, "farming" to TaskKind.HARVEST, "forestry" to TaskKind.CHOP, "factory" to TaskKind.BUILD)

    fun seed(c: Country) {
        if (c.jobDefs.isNotEmpty()) return
        Config.s.jobs.forEach { (id, cfg) ->
            val kind = taskOfType[cfg.type] ?: TaskKind.BUILD
            c.jobDefs[id] = JobSpec(id, id.replaceFirstChar { it.uppercase() }, 0, JobScope.TYPE, cfg.type, mutableListOf(TaskSpec(kind, cfg.blocks.joinToString(","))), cfg.pay, cfg.quota, cfg.period)
        }
    }
}
