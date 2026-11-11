package kami.claims.press

import kami.claims.Job
import kami.claims.gov.CITIZEN
import kami.claims.gov.GovTest
import kami.claims.gov.PRESIDENT
import kami.claims.gov.JobRules
import kami.claims.gov.JobSpec
import kami.claims.gov.Perm
import kami.claims.gov.Permissions
import kami.claims.service.Fail
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class JobPermsTest : GovTest() {
    @Test
    fun onlyTheWritePermCanComeFromAJob() {
        assertEquals(setOf(Perm.NEWS_WRITE), JobRules.perms(" news_write ,NEWS_WRITE"))
        assertEquals(emptySet(), JobRules.perms(""))
        assertFailsWith<Fail> { JobRules.perms("NEWS_PUBLISH") }
        assertFailsWith<Fail> { JobRules.perms("WITHDRAW") }
        assertFailsWith<Fail> { JobRules.perms("nonsense") }
    }

    @Test
    fun holdingAnActiveJobGrantsTheWritePermOnly() {
        val c = country(mapOf("pres" to PRESIDENT, "cit" to CITIZEN))
        val spec = JobSpec("editor", "Editor", perms = setOf(Perm.NEWS_WRITE))
        c.jobDefs["editor"] = spec
        assertFalse(Permissions.holds(c, "cit", Perm.NEWS_WRITE))
        c.members.getValue("cit").jobs["editor"] = Job()
        assertTrue(Permissions.holds(c, "cit", Perm.NEWS_WRITE))
        assertFalse(Permissions.holds(c, "cit", Perm.NEWS_PUBLISH))
        spec.active = false
        assertFalse(Permissions.holds(c, "cit", Perm.NEWS_WRITE))
        spec.active = true
        c.members.getValue("cit").jobs.clear()
        assertFalse(Permissions.holds(c, "cit", Perm.NEWS_WRITE))
    }

    @Test
    fun jobsWithoutPermsKeepOldBehaviour() {
        val c = country(mapOf("pres" to PRESIDENT, "cit" to CITIZEN))
        c.jobDefs["miner"] = JobSpec("miner", "Miner")
        c.members.getValue("cit").jobs["miner"] = Job()
        assertFalse(Permissions.holds(c, "cit", Perm.NEWS_WRITE))
    }

    @Test
    fun grantingTheWritePermNeedsHoldingIt() {
        val c = country(mapOf("pres" to PRESIDENT, "cit" to CITIZEN))
        assertTrue(JobRules.mayGrant(c, "pres", setOf(Perm.NEWS_WRITE)))
        assertFalse(JobRules.mayGrant(c, "cit", setOf(Perm.NEWS_WRITE)))
        assertTrue(JobRules.mayGrant(c, "cit", emptySet()))
    }

    @Test
    fun describeListsPerms() {
        assertEquals("Editor [NEWS_WRITE]", JobRules.describe(JobSpec("e", "Editor", perms = setOf(Perm.NEWS_WRITE))))
        assertEquals("Miner", JobRules.describe(JobSpec("m", "Miner")))
    }
}
