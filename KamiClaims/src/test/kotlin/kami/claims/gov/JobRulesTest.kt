package kami.claims.gov

import kami.claims.Member
import kami.claims.now
import kami.claims.service.Fail
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class JobRulesTest {
    private val tasks = { mutableListOf(TaskSpec(TaskKind.MINE, "stone", 10)) }
    private val base get() = JobSpec("j", "J", tasks = tasks(), pay = 10, quota = 5)

    @Test
    fun newMembersCannotTakePaidJobsButMayTakeUnpaidOnes() {
        val fresh = Member(CITIZEN, since = now())
        assertFailsWith<Fail> { JobRules.seasoned(fresh, 1) }
        JobRules.seasoned(fresh, 0)
        JobRules.seasoned(Member(CITIZEN, since = 0), 1)
    }

    @Test
    fun quotaAndDutyLabourAlwaysCountAsPaying() {
        val unpaid = JobSpec("j", "J", pay = 0)
        assertTrue(JobRules.mayPay(unpaid, Labour.QUOTA))
        assertTrue(JobRules.mayPay(unpaid, Labour.DUTY))
        assertFalse(JobRules.mayPay(unpaid, Labour.WAGE))
        assertTrue(JobRules.mayPay(base, Labour.WAGE))
    }

    @Test
    fun buildCraftAndSmeltTasksNeedAFilterOutsideSectors() {
        listOf("build", "craft", "smelt").forEach { kind ->
            assertFailsWith<Fail> { JobRules.tasks(kind, JobScope.TYPE) }
            assertFailsWith<Fail> { JobRules.tasks("$kind::5", JobScope.COUNTRY) }
            assertEquals(1, JobRules.tasks("$kind:minecraft:stone:5", JobScope.TYPE).size)
            assertEquals(1, JobRules.tasks(kind, JobScope.SECTOR).size)
        }
    }

    @Test
    fun earningsRelevantFieldsAreDetected() {
        assertFalse(JobRules.altersEarnings(base, base))
        assertFalse(JobRules.altersEarnings(base, base.also { it.name = "Other" }))
        assertTrue(JobRules.altersEarnings(base, JobSpec("j", "J", tasks = tasks(), pay = 11, quota = 5)))
        assertTrue(JobRules.altersEarnings(base, JobSpec("j", "J", tasks = mutableListOf(TaskSpec(TaskKind.MINE, "dirt", 10)), pay = 10, quota = 5)))
    }

    @Test
    fun blankOrZeroCapBecomesTheMaximumCap() {
        assertEquals(JobRules.MAX_TASK_CAP, JobRules.tasks("harvest::0", JobScope.TYPE).single().cap)
        assertEquals(JobRules.MAX_TASK_CAP, JobRules.tasks("harvest", JobScope.TYPE).single().cap)
    }

    @Test
    fun tagFiltersAreKept() {
        val task = JobRules.tasks("mine:#c:ores:5", JobScope.TYPE).single()
        assertEquals(Triple(TaskKind.MINE, "#c:ores", 5), Triple(task.kind, task.filter, task.cap))
    }

    @Test
    fun deliverAndMoreThanFourTasksAreRejected() {
        assertFailsWith<Fail> { JobRules.tasks("deliver", JobScope.TYPE) }
        assertFailsWith<Fail> { JobRules.tasks(List(JobRules.MAX_TASKS + 1) { "harvest" }.joinToString(";"), JobScope.TYPE) }
    }

    @Test
    fun uppercaseAndParentPathFiltersAreRejected() {
        assertFailsWith<Fail> { JobRules.tasks("mine:minecraft:Stone:5", JobScope.TYPE) }
        assertFailsWith<Fail> { JobRules.tasks("mine:minecraft:../stone:5", JobScope.TYPE) }
    }

    @Test
    fun labourMatrixIsEnforcedOnTheServer() {
        assertFailsWith<Fail> { JobRules.validate(5, PayModel.FIXED, Assignment.CONTRACT, Labour.WAGE) }
        assertFailsWith<Fail> { JobRules.validate(5, PayModel.FIXED, Assignment.OPEN, Labour.QUOTA) }
        assertFailsWith<Fail> { JobRules.validate(5, PayModel.RATION, Assignment.OPEN, Labour.WAGE) }
        assertFailsWith<Fail> { JobRules.validate(5, PayModel.FIXED, Assignment.OPEN, Labour.MARKET) }
        assertFailsWith<Fail> { JobRules.validate(5, PayModel.FIXED, Assignment.OPEN, Labour.DUTY) }
        JobRules.validate(5, PayModel.FIXED, Assignment.CONTRACT, Labour.MARKET)
        JobRules.validate(5, PayModel.RATION, Assignment.APPOINTED, Labour.QUOTA)
        JobRules.validate(0, PayModel.FIXED, Assignment.APPOINTED, Labour.DUTY)
    }
}
