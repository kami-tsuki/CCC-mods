package kami.claims.gov

import kami.claims.Country

class RankSeed(val name: String, val color: Int, val template: RoleTemplate)

class Preset(val id: PresetId, val levers: Levers, val leader: RankSeed, val member: RankSeed, val ladder: List<RankSeed>)

object Presets {
    private fun seed(name: String, color: Int, template: RoleTemplate = RoleTemplate.MEMBER) = RankSeed(name, color, template)

    val all = listOf(
        Preset(PresetId.MIXED, Levers(Labour.WAGE, Land.LEASE, TaxStyle.BRACKETS, TreasuryControl.COUNCIL, Leadership.ELECTED, RankStructure.LADDER, ProvinceRule.VASSAL, Membership.APPLICATION),
            seed("President", 0x476E91), seed("Citizen", 0xA6A6A6),
            listOf(seed("Minister", 0x2E75B6, RoleTemplate.DEPUTY), seed("Official", 0x5B9BD5, RoleTemplate.FOREMAN), seed("Treasurer", 0x3A7CA5, RoleTemplate.TREASURER), seed("Warden", 0x4A90A4, RoleTemplate.WARDEN))),
        Preset(PresetId.CAPITALISM, Levers(Labour.MARKET, Land.PRIVATE, TaxStyle.FLAT, TreasuryControl.RULER, Leadership.ELECTED, RankStructure.FLAT, ProvinceRule.AUTONOMOUS, Membership.OPEN),
            seed("Chief Executive", 0xB8860B), seed("Employee", 0x8C8C8C),
            listOf(seed("Director", 0xD4A017, RoleTemplate.DEPUTY), seed("Manager", 0xE6C65C, RoleTemplate.FOREMAN), seed("Accountant", 0xC9A227, RoleTemplate.TREASURER), seed("Security", 0xA67C00, RoleTemplate.WARDEN))),
        Preset(PresetId.SOCIALISM, Levers(Labour.WAGE, Land.LEASE, TaxStyle.BRACKETS, TreasuryControl.COUNCIL, Leadership.ELECTED, RankStructure.LADDER, ProvinceRule.AUTONOMOUS, Membership.OPEN),
            seed("Prime Minister", 0xC0392B), seed("Member", 0xA6A6A6),
            listOf(seed("Minister", 0xD9534F, RoleTemplate.DEPUTY), seed("Delegate", 0xE88A84, RoleTemplate.FOREMAN), seed("Steward", 0xB5463B, RoleTemplate.TREASURER), seed("Marshal", 0xA65149, RoleTemplate.WARDEN))),
        Preset(PresetId.COMMUNISM, Levers(Labour.QUOTA, Land.STATE, TaxStyle.TRIBUTE, TreasuryControl.BUDGET, Leadership.APPOINTED, RankStructure.LADDER, ProvinceRule.DIRECT, Membership.APPLICATION),
            seed("Chairperson", 0xAE4D4D), seed("Comrade", 0xA6A6A6),
            listOf(seed("Commissar", 0xBD4141, RoleTemplate.DEPUTY), seed("Party Member", 0xCD5C5C, RoleTemplate.FOREMAN), seed("Quartermaster", 0xAB4F4F, RoleTemplate.TREASURER), seed("Guard", 0x9D5959, RoleTemplate.WARDEN))),
        Preset(PresetId.MONARCHY, Levers(Labour.DUTY, Land.LEASE, TaxStyle.TRIBUTE, TreasuryControl.RULER, Leadership.HEREDITARY, RankStructure.LADDER, ProvinceRule.VASSAL, Membership.INVITE),
            seed("Monarch", 0x914CC2), seed("Subject", 0xA6A6A6),
            listOf(seed("Duke", 0x8E44AD, RoleTemplate.DEPUTY), seed("Steward", 0xB07CC6, RoleTemplate.FOREMAN), seed("Chamberlain", 0x8D53A4, RoleTemplate.TREASURER), seed("Knight", 0x825F92, RoleTemplate.WARDEN))),
        Preset(PresetId.COMMUNE, Levers(Labour.QUOTA, Land.COMMUNAL, TaxStyle.NONE, TreasuryControl.BUDGET, Leadership.ELECTED, RankStructure.FLAT, ProvinceRule.FREE, Membership.OPEN),
            seed("Spokesperson", 0x2E8B57), seed("Member", 0x90C9A5),
            listOf(seed("Delegate", 0x3CB371, RoleTemplate.DEPUTY), seed("Coordinator", 0x58B07A, RoleTemplate.FOREMAN), seed("Keeper", 0x1E7B4A, RoleTemplate.TREASURER), seed("Watch", 0x3E765D, RoleTemplate.WARDEN))),
        Preset(PresetId.STRATOCRACY, Levers(Labour.DUTY, Land.STATE, TaxStyle.TRIBUTE, TreasuryControl.RULER, Leadership.SEIZED, RankStructure.LADDER, ProvinceRule.DIRECT, Membership.INVITE),
            seed("General", 0x556B2F), seed("Soldier", 0xA6A6A6),
            listOf(seed("Colonel", 0x6B8E23, RoleTemplate.DEPUTY), seed("Captain", 0x8FA85A, RoleTemplate.WARDEN), seed("Quartermaster", 0x7A8F3F, RoleTemplate.TREASURER), seed("Sergeant", 0x61723E, RoleTemplate.FOREMAN))),
        Preset(PresetId.THEOCRACY, Levers(Labour.DUTY, Land.STATE, TaxStyle.TRIBUTE, TreasuryControl.RULER, Leadership.APPOINTED, RankStructure.LADDER, ProvinceRule.VASSAL, Membership.INVITE),
            seed("High Priest", 0xC9A227), seed("Believer", 0xA6A6A6),
            listOf(seed("Priest", 0xE0C060, RoleTemplate.DEPUTY), seed("Acolyte", 0xEDDC9E, RoleTemplate.FOREMAN), seed("Tithe Keeper", 0xB8962E, RoleTemplate.TREASURER), seed("Inquisitor", 0x8D6E1F, RoleTemplate.WARDEN))),
        Preset(PresetId.CUSTOM, Levers(Labour.WAGE, Land.LEASE, TaxStyle.BRACKETS, TreasuryControl.COUNCIL, Leadership.ELECTED, RankStructure.LADDER, ProvinceRule.VASSAL, Membership.APPLICATION),
            seed("Leader", 0x7F8FA6), seed("Member", 0xA6A6A6),
            listOf(seed("Deputy", 0x9AA5B8, RoleTemplate.DEPUTY), seed("Treasurer", 0x8E9AAE, RoleTemplate.TREASURER), seed("Warden", 0xB0B8C6, RoleTemplate.WARDEN), seed("Foreman", 0xA3ADBD, RoleTemplate.FOREMAN)))
    ).associateBy { it.id }

    fun of(id: PresetId) = all.getValue(id)

    fun levers(id: PresetId) = of(id).levers

    fun found(c: Country, id: PresetId) {
        c.government = Government(id, levers(id))
        c.ranks.clear()
        c.ranks += starterRanks(id)
        Jobs.seed(c)
    }

    fun starterRanks(id: PresetId): List<RankDef> {
        val preset = of(id)
        return listOf(
            RankDef(PRESIDENT, preset.leader.name, preset.leader.color, plots = RankPlots.PRESIDENT),
            RankDef(CITIZEN, preset.member.name, preset.member.color, RoleTemplate.MEMBER.perms.toMutableSet(), RankPlots.CITIZEN)
        )
    }
}
