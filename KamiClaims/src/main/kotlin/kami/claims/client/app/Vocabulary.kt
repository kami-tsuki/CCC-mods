package kami.claims.client.app

import kami.libs.ui.style.Icon
import kami.libs.ui.style.Icons
import kami.libs.ui.style.Palette
import kami.libs.ui.widget.Option
import kotlin.math.abs

class Look(val label: String, val color: Int, val icon: Icon, val description: String)

object Vocabulary {
    private val types = mapOf(
        "civic" to Look("Civic", 0xFFC9CED8.toInt(), Icons.TOWN, "Town centre land. Citizens build here."),
        "mining" to Look("Mining", 0xFFE0A050.toInt(), Icons.PICKAXE, "Mines and quarries. Miners are paid for the blocks they dig here."),
        "farming" to Look("Farming", 0xFF8BCB6B.toInt(), Icons.WHEAT, "Fields and farms. Farmers are paid for the crops they harvest here."),
        "forestry" to Look("Forestry", 0xFF3E9B63.toInt(), Icons.TREE, "Woods for timber. Foresters are paid for the logs they cut here."),
        "factory" to Look("Factory", 0xFF6AA9FF.toInt(), Icons.GEAR, "Workshops and machines. Anyone with a job may work here."),
        "market" to Look("Market", 0xFFE07B9B.toInt(), Icons.SCALES, "Shops and stalls. Visitors may use them."),
        "residential" to Look("Residential", 0xFFC9A0F0.toInt(), Icons.HOUSE, "Housing. Citizens rent these as plots and pay tax into the treasury."),
        "infrastructure" to Look("Infrastructure", 0xFF8A8FA3.toInt(), Icons.RAIL, "Roads and rails. Tracks may cross into other countries."),
        "wilderness" to Look("Wilderness", 0xFF6FA36B.toInt(), Icons.PINE, "Cheap land kept wild. Nobody may build, allies may pass.")
    )

    fun type(name: String): Look = types[name] ?: Look(name.replaceFirstChar { it.uppercase() }, Palette.chart[abs(name.hashCode()) % Palette.chart.size], Icons.GENERIC, "A chunk type from the server config.")

    private val ranks = mapOf(
        "president" to Look("President", 0xFFF2C94C.toInt(), Icons.CROWN, "Leads the country and may do everything."),
        "chancellor" to Look("Chancellor", 0xFFC9A0F0.toInt(), Icons.SCROLL, "Second in command. Takes over when the president is gone for too long."),
        "officer" to Look("Officer", 0xFF7FB2FF.toInt(), Icons.SHIELD, "Handles citizens and jobs."),
        "citizen" to Look("Citizen", 0xFF4CC38A.toInt(), Icons.PERSON, "A member of the country."),
        "allied" to Look("Ally", 0xFF38BDF8.toInt(), Icons.HANDSHAKE, "Not a member, but may use what allies may use."),
        "banished" to Look("Banished", 0xFFF2555A.toInt(), Icons.BAN, "Locked out of the whole country.")
    )

    fun rank(name: String): Look = ranks[name.lowercase()] ?: Look(name, Palette.textMuted, Icons.PERSON, "")

    val rankOrder = listOf("citizen", "officer", "chancellor", "president")

    val access = listOf(
        "none" to Look("Nobody", 0xFFF2555A.toInt(), Icons.BAN, "Nobody may do this, not even the president."),
        "officer" to Look("Officers", 0xFFF08A4B.toInt(), Icons.SHIELD, "Officers, the chancellor and the president."),
        "job" to Look("Job workers", 0xFFF5A524.toInt(), Icons.PICKAXE, "Members whose job belongs to this land type, e.g. miners in mining land. Officers too."),
        "worker" to Look("Any worker", 0xFFD9C36A.toInt(), Icons.TOOL, "Members with any job, and officers."),
        "citizen" to Look("Citizens", 0xFF8BCB6B.toInt(), Icons.PEOPLE, "Every member of the country."),
        "allied" to Look("Allies", 0xFF38BDF8.toInt(), Icons.HANDSHAKE, "Members and allied players, including provinces of the same family."),
        "any" to Look("Everyone", 0xFF4CC38A.toInt(), Icons.GLOBE, "Anyone, even strangers.")
    )

    fun access(name: String) = access.firstOrNull { it.first == name }?.second ?: access.first().second

    fun accessOptions() = access.map { (value, look) -> Option(value, look.label, look.icon, look.description, look.color) }

    val actions = listOf(
        Look("Break", 0, Icons.PICKAXE, "Mining, chopping and breaking any block."),
        Look("Place", 0, Icons.BLOCK, "Placing blocks and pouring buckets."),
        Look("Use", 0, Icons.HAND, "Doors, buttons, levers, beds and similar."),
        Look("Open", 0, Icons.CHEST, "Chests, barrels, furnaces and other containers.")
    )

    val flags = listOf(
        "machines" to Look("Machines", 0, Icons.GEAR, "Machines such as drills and saws may break blocks here."),
        "fire" to Look("Fire", 0, Icons.FIRE, "Fire may spread into this land."),
        "fluid" to Look("Fluids", 0, Icons.WATER, "Water and lava may flow into this land.")
    )

    val caps = linkedMapOf(
        "claim" to Look("Claim land", 0, Icons.AREA, "Claim, release and change the type of chunks."),
        "capital" to Look("Move capital", 0, Icons.CROWN, "Choose which chunk is the capital."),
        "tax" to Look("Taxes", 0, Icons.TAX, "Set plot tax and lapse timers."),
        "rules" to Look("Laws", 0, Icons.SHIELD, "Change protection rules, colour and flag."),
        "withdraw" to Look("Withdraw", 0, Icons.WITHDRAW, "Take coins out of the treasury."),
        "invite" to Look("Invite", 0, Icons.INVITE, "Invite players and answer join requests."),
        "members" to Look("Members", 0, Icons.PEOPLE, "Kick, banish and ally players."),
        "rank" to Look("Ranks", 0, Icons.STAR, "Promote and demote members."),
        "jobs" to Look("Jobs", 0, Icons.TOOL, "Change jobs, wages and workers."),
        "plot" to Look("Rent plots", 0, Icons.HOUSE, "Claim a residential plot for yourself."),
        "details" to Look("See finances", 0, Icons.LEDGER, "See debt, free chunks, the ledger and the budget."),
        "province" to Look("Provinces", 0, Icons.CHAIN, "Invite, accept, release and tax provinces.")
    )

    val ledger = mapOf(
        "deposit" to Look("Deposit", 0, Icons.DEPOSIT, "Coins put into the treasury."),
        "withdraw" to Look("Withdrawal", 0, Icons.WITHDRAW, "Coins taken out of the treasury."),
        "claim" to Look("Land claimed", 0, Icons.AREA, "First day of a newly claimed chunk."),
        "upkeep" to Look("Upkeep", 0, Icons.EXPENSE, "Daily cost of your land."),
        "plot_tax" to Look("Plot tax", 0, Icons.HOUSE, "Tax paid by plot owners."),
        "job_pay" to Look("Wages", 0, Icons.TOOL, "Pay for workers who met their quota."),
        "tribute_in" to Look("Tribute received", 0, Icons.CHAIN, "Paid by your provinces."),
        "tribute_out" to Look("Tribute paid", 0, Icons.CHAIN, "Paid to your overlord."),
        "adjust" to Look("Adjustment", 0, Icons.EDIT, "Changed by an admin.")
    )

    fun ledger(kind: String) = ledger[kind] ?: Look(kind, 0, Icons.GENERIC, "")

    fun relationColor(relation: Int) = when (relation) {
        1 -> Palette.success
        2 -> Palette.geoAlly
        3 -> Palette.geoProvince
        4 -> Palette.geoHostile
        else -> Palette.geoNeutral
    }

    fun relationLabel(relation: Int) = when (relation) {
        1 -> "Your country"
        2 -> "Allied"
        3 -> "Same family"
        4 -> "You are banished"
        else -> "Foreign"
    }

    fun tribute(mode: String, amount: Double) = if (mode == "percent") "${(amount * 100).toInt()}% of plot tax" else "${amount.toLong()} ◎ per day"
}
