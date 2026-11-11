package kami.claims.gov

import kami.claims.Config
import kami.claims.Country
import kami.claims.NameRules
import kami.libs.ui.widget.contrastRatio
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private const val DARK_PANEL = 0x15171B

class PresetsTest : GovTest() {
    @Test
    fun everyPresetIdHasAPresetWithItsLevers() {
        assertEquals(PresetId.entries.toSet(), Presets.all.keys)
        PresetId.entries.forEach { assertEquals(Presets.of(it).levers, Presets.levers(it), it.name) }
        assertEquals(PresetId.entries.size - 1, (Presets.all - PresetId.CUSTOM).values.map { it.levers }.toSet().size)
    }

    @Test
    fun customStartsWithMixedLeversGenericRanksAndNoWageFloor() {
        assertEquals(Presets.levers(PresetId.MIXED), Presets.levers(PresetId.CUSTOM))
        assertEquals(listOf("Leader", "Member"), Presets.starterRanks(PresetId.CUSTOM).map { it.name })
        assertEquals(listOf("Deputy", "Treasurer", "Warden", "Foreman"), Presets.of(PresetId.CUSTOM).ladder.map { it.name })
        assertEquals(0.0, Government(PresetId.CUSTOM, Presets.levers(PresetId.CUSTOM)).wageFloor)
    }

    @Test
    fun everyStarterColourStaysReadableOnADarkPanel() {
        Presets.all.values.flatMap { listOf(it.leader, it.member) + it.ladder }.forEach {
            assertTrue(contrastRatio(it.color, DARK_PANEL) >= 3.0, "${it.name} ${Integer.toHexString(it.color)}")
        }
    }

    @Test
    fun starterRanksPutThePresidentFirstAndCitizenLast() {
        PresetId.entries.forEach { id ->
            val ranks = Presets.starterRanks(id)
            assertEquals(listOf(PRESIDENT, CITIZEN), ranks.map { it.id }, id.name)
            assertEquals(Presets.of(id).leader.name, ranks.first().name)
            assertEquals(Presets.of(id).member.name, ranks.last().name)
            assertEquals(RoleTemplate.MEMBER.perms, ranks.last().perms)
        }
    }

    @Test
    fun foundingSeedsGovernmentRanksAndJobs() {
        val c = Country("Fresh")
        Presets.found(c, PresetId.COMMUNISM)
        assertEquals(PresetId.COMMUNISM, c.government.preset)
        assertEquals(Labour.QUOTA, c.government.levers.labour)
        assertEquals(listOf(PRESIDENT, CITIZEN), c.ranks.map { it.id })
        assertEquals(Config.s.jobs.keys, c.jobDefs.keys)
    }

    @Test
    fun presetSeedNamesAreValidAndUniqueAndCoverEveryTemplateButMember() {
        val c = Country("Names")
        Presets.all.values.forEach { p ->
            val seeds = listOf(p.leader, p.member) + p.ladder
            seeds.forEach { RankRules.name(c, it.name) }
            assertEquals(seeds.size, seeds.map { NameRules.key(it.name) }.toSet().size, p.id.name)
            assertEquals(RoleTemplate.entries.toSet() - RoleTemplate.MEMBER - RoleTemplate.EDITOR, p.ladder.map { it.template }.toSet(), p.id.name)
        }
    }

    @Test
    fun memberTemplateHoldsNoHighRiskPowerAndOnlyTheDeputyLacksTheKeys() {
        assertTrue(RoleTemplate.MEMBER.perms.none { it.risk >= Risk.HIGH })
        assertTrue(RoleTemplate.MEMBER.perms.none { it.group == PermGroup.RANKS || it.group == PermGroup.PEOPLE && it.risk != Risk.LOW })
        val deputy = RoleTemplate.DEPUTY.perms
        listOf(Perm.WITHDRAW, Perm.EDIT_RANKS, Perm.CREATE_RANKS, Perm.WAR, Perm.CAPITAL, Perm.BYPASS_RULES).forEach { assertTrue(it !in deputy, it.name) }
        assertTrue(Perm.CLAIM in deputy && Perm.KICK in deputy && Perm.TAXES in deputy)
    }

    @Test
    fun outsiderAndDefaultGroupsContainNoRiskyPerm() {
        assertTrue(Perm.outsiderAllowed.all { it.risk == Risk.LOW || it.group == PermGroup.USE })
        assertTrue(Perm.outsiderAllowed.none { it.risk >= Risk.HIGH || it == Perm.WITHDRAW })
    }

    @Test
    fun everyTemplateKeepsToolUsePermsSoHoldersCanStillWork() {
        RoleTemplate.entries.filter { it != RoleTemplate.DEPUTY }.forEach { t ->
            assertTrue(Perm.BREAK in t.perms && Perm.PLACE in t.perms, t.name)
        }
    }
}
