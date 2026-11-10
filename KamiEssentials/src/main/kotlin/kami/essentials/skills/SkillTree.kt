package kami.essentials.skills

import kami.essentials.skills.Effects as E
import kami.libs.skills.SkillClass
import kami.libs.skills.Skills
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

enum class NodeKind { START, TRAVEL, MINOR, NOTABLE, CORE, KEYSTONE, BONUS }

enum class Denial { MAXED, NO_CLASS, PATH, KEYSTONE, ALT, LEVEL, SECTOR, POINTS }

class Gate(val level: Int, val levelStep: Int, val sector: Int, val sectorStep: Int) {
    fun level(rank: Int) = level + (rank - 1) * levelStep
    fun sector(rank: Int) = sector + (rank - 1) * sectorStep
}

class SkillNode(val id: Int, val kind: NodeKind, val cls: SkillClass, val name: String, val effects: Map<String, Double>, val ranks: Int, val x: Double, val y: Double, val gate: Gate?, val cluster: Int) {
    val links = ArrayList<Int>()
    var alt = -1
}

class SkillEdge(val a: Int, val b: Int, val arc: Boolean, val cx: Double, val cy: Double)

class Constellation(val id: Int, val cls: SkillClass, val name: String, val nodes: IntArray, val bonus: Map<String, Double>, val x: Double, val y: Double, val r: Double)

class Fx(val name: String, val effects: Map<String, Double>)

private fun fx(name: String, vararg e: Pair<String, Double>) = Fx(name, mapOf(*e))

private class Minor(val effect: String, val value: Double, val ranks: Int)

private class Core(val name: String, val effect: String, val value: Double, val ranks: Int)

private class Spec(val minors: List<Minor>, val notables: List<Fx>, val bonuses: List<Fx>, val forks: List<Pair<Core, Core>>, val keystones: List<Fx>)

private fun mn(effect: String, value: Double, ranks: Int = 1) = Minor(effect, value, ranks)

private fun core(name: String, effect: String, value: Double, ranks: Int) = Core(name, effect, value, ranks)

object SkillTree {
    private const val R0 = 120.0
    private val RINGS = listOf(330.0, 600.0, 870.0)
    private const val RIM = 1010.0
    private const val FORK = 24.0
    private val GATES = listOf(Gate(5, 5, 3, 2), Gate(20, 6, 12, 3), Gate(35, 6, 22, 3))
    private val KEY_GATE = Gate(40, 0, 40, 0)
    private const val KEY_LEVEL_STEP = 45
    private val TRAVEL = listOf(fx("vigor", "health" to 0.2), fx("agility", "speed" to 0.004), fx("diligence", E.CLASS_XP to 0.004))

    private val BONUSES = mapOf(
        SkillClass.RESEARCHER to (E.POCKET_CRAFT to E.SLEEPLESS), SkillClass.TRADER to (E.SHOWMAN to E.ENDER_SATCHEL), SkillClass.FARMER to (E.RAVAGER_TAMER to E.BEE_FRIEND),
        SkillClass.FISHER to (E.BUCKET_FISHER to E.DROWNED_TRUCE), SkillClass.LUMBER to (E.LEAF_LANDING to E.TREEHUGGER), SkillClass.MINER to (E.LAVA_WALKER to E.CANARY),
        SkillClass.SOLDIER to (E.HORSE_WHISPERER to E.HEADHUNTER), SkillClass.WORKER to (E.LADDER_RUSH to E.MAGNET)
    )

    private val specs = mapOf(
        SkillClass.RESEARCHER to Spec(
            listOf(mn(E.ORB_XP, 0.03, 3), mn(Skills.RESEARCH_INSIGHT, 0.005, 3), mn(E.BREW_DURATION, 0.02), mn(E.ENCHANT_REFUND, 0.02, 3), mn(E.ANVIL_SAVE, 0.05), mn(E.PAPER_PRESS, 0.04, 3)),
            listOf(
                fx("librarian", E.ORB_XP to 0.15, E.ENCHANT_REFUND to 0.05), fx("paper_diet", E.EAT_PAPER to 1.0, E.CLASS_XP to 0.03), fx("anvil_master", E.ANVIL_SAVE to 0.3),
                fx("prospectors_eye", Skills.SCAN_RANGE to 0.25), fx("alchemist_notes", E.BREW_DURATION to 0.12), fx("methodical", Skills.RESEARCH_INSIGHT to 0.03), fx("burst_of_insight", Skills.RESEARCH_TIME to 0.02)
            ),
            listOf(fx("the_archive", Skills.RESEARCH_INSIGHT to 0.02), fx("the_quill", E.ORB_XP to 0.1), fx("the_anvil", E.ANVIL_SAVE to 0.15), fx("the_compass", Skills.SCAN_RANGE to 0.15), fx("the_alembic", E.BREW_DURATION to 0.1)),
            listOf(
                core("swift_study", Skills.RESEARCH_TIME, 0.012, 5) to core("frugal_science", Skills.RESEARCH_COST, 0.012, 5),
                core("deep_insight", Skills.RESEARCH_INSIGHT, 0.02, 5) to core("master_alchemist", E.BREW_DURATION, 0.05, 5),
                core("arcane_thrift", E.ENCHANT_REFUND, 0.08, 5) to core("paper_baron", E.PAPER_PRESS, 0.08, 5)
            ),
            listOf(
                fx("scholar", Skills.RESEARCH_TIME to 0.06, Skills.RESEARCH_COST to 0.06, E.DAMAGE_DEALT to -0.25, "health" to -4.0),
                fx("loremaster", E.ORB_XP to 0.4, E.ANVIL_SAVE to 0.4, E.ENCHANT_REFUND to 0.15, "armor" to -4.0)
            )
        ),
        SkillClass.TRADER to Spec(
            listOf(mn(Skills.AUCTION_FEE, 0.01), mn(E.CLASS_XP, 0.01, 3), mn("sneak_speed", 0.02), mn(Skills.ORDER_SLOTS, 1.0), mn(Skills.AUCTION_SLOTS, 1.0)),
            listOf(
                fx("smooth_talker", E.TRADE_XP to 0.1), fx("stallholder", Skills.ORDER_SLOTS to 2.0), fx("light_fingers", "sneak_speed" to 0.08), fx("haggler", Skills.AUCTION_FEE to 0.04),
                fx("networker", E.CLASS_XP to 0.04), fx("courier", "speed" to 0.015), fx("deal_maker", Skills.AUCTION_FEE to 0.02, Skills.AUCTION_SLOTS to 1.0)
            ),
            listOf(fx("the_scales", Skills.AUCTION_FEE to 0.03), fx("the_coin", Skills.INFINITE_LOTS to 1.0), fx("the_road", "speed" to 0.01), fx("the_caravan", "sneak_speed" to 0.05), fx("the_handshake", E.CLASS_XP to 0.03)),
            listOf(
                core("auctioneer", Skills.AUCTION_FEE, 0.03, 5) to core("market_sense", E.CLASS_XP, 0.03, 5),
                core("silver_tongue", E.TRADE_XP, 0.1, 3) to core("wholesaler", Skills.INFINITE_LOTS, 1.0, 3),
                core("trade_winds", "speed", 0.008, 5) to core("shadow_step", "sneak_speed", 0.04, 5)
            ),
            listOf(
                fx("tycoon", Skills.AUCTION_FEE to 0.3, E.TRADE_XP to 0.4, "health" to -6.0),
                fx("monopolist", Skills.ORDER_SLOTS to 4.0, Skills.AUCTION_SLOTS to 3.0, Skills.INFINITE_LOTS to 2.0, "health" to -4.0, "armor" to -3.0)
            )
        ),
        SkillClass.FARMER to Spec(
            listOf(mn(E.CROP_FORTUNE, 0.02, 3), mn(E.HOE_DAMAGE, 0.05), mn(E.BUTCHER, 0.03, 3), mn(E.BREED, 0.03, 3), mn(E.BONEMEAL_SAVE, 0.03), mn(E.CALF_GROWTH, 0.05), mn(E.QUICK_BITE, 0.04)),
            listOf(
                fx("scarecrow", E.FARMLAND_CALM to 1.0), fx("green_hands", E.REPLANT to 1.0), fx("butchery", E.BUTCHER to 0.25), fx("fertilizer", E.BONEMEAL_SAVE to 0.25),
                fx("hearty_meals", E.QUICK_BITE to 0.1), fx("ranch_hand", E.CALF_GROWTH to 0.15), fx("hearty", E.VEGGIE_FOOD to 1.0)
            ),
            listOf(fx("the_sheaf", E.CROP_FORTUNE to 0.05), fx("the_scythe", E.HOE_DAMAGE to 0.3), fx("the_herd", E.BREED to 0.15), fx("the_plow", E.CALF_GROWTH to 0.1), fx("the_hearth", E.VEGGIE_FOOD to 1.0)),
            listOf(
                core("reapers_edge", E.HOE_DAMAGE, 0.25, 4) to core("bountiful_harvest", E.CROP_FORTUNE, 0.05, 5),
                core("harvest_scythe", E.TOOL_HOE, 1.0, 1) to core("growth_aura", E.CROP_AURA, 1.0, 5),
                core("rancher", E.BREED, 0.1, 5) to core("greens", E.VEGGIE_FOOD, 1.0, 3)
            ),
            listOf(
                fx("harvest_lord", E.CROP_FORTUNE to 0.4, E.CROP_AURA to 2.0, E.DAMAGE_DEALT to -0.15, "health" to -4.0),
                fx("shepherd", E.BREED to 0.5, E.CALF_GROWTH to 0.5, E.BUTCHER to 0.3, "speed" to -0.06, "mining_speed" to -0.15)
            )
        ),
        SkillClass.FISHER to Spec(
            listOf(mn("luck", 0.1), mn("swim", 0.02), mn(E.DOUBLE_CATCH, 0.01, 3), mn(E.ROD_SAVE, 0.02, 3), mn("submerged", 0.05), mn(E.FISH_XP, 0.03, 3), mn("oxygen", 0.2)),
            listOf(
                fx("gills", E.FISH_BREATH to 20.0), fx("dolphin_kin", E.SEA_GRACE to 1.0), fx("fishmonger", E.FISH_FOOD to 2.0), fx("deep_lungs", "oxygen" to 2.0),
                fx("net_caster", E.DOUBLE_CATCH to 0.08), fx("diver", "submerged" to 0.4), fx("clear_waters", E.WATER_SIGHT to 1.0)
            ),
            listOf(fx("the_hook", E.DOUBLE_CATCH to 0.04), fx("the_tide", "swim" to 0.08), fx("the_pearl", "luck" to 0.3), fx("the_net", E.ROD_SAVE to 0.15), fx("the_reef", "oxygen" to 1.5)),
            listOf(
                core("double_catch", E.DOUBLE_CATCH, 0.025, 5) to core("tide_rider", "swim", 0.08, 5),
                core("sea_luck", "luck", 0.25, 5) to core("water_breath", E.FISH_BREATH, 40.0, 5),
                core("line_care", E.ROD_SAVE, 0.08, 5) to core("abyss_diver", "oxygen", 0.4, 5)
            ),
            listOf(
                fx("child_of_the_sea", E.SEA_CHILD to 1.0, E.SEA_GRACE to 1.0, E.LAND_MINING to -0.25, "health" to -4.0),
                fx("leviathan", E.DOUBLE_CATCH to 0.2, E.ROD_SAVE to 0.4, "luck" to 1.0, "speed" to -0.05, "armor" to -2.0)
            )
        ),
        SkillClass.LUMBER to Spec(
            listOf(mn(E.AXE_SPEED, 0.03, 3), mn(E.LOG_YIELD, 0.02, 3), mn(E.LEAF_DROPS, 0.03), mn(E.SAWMILL, 0.04, 3), mn("jump", 0.02), mn(E.AXE_DAMAGE, 0.05)),
            listOf(
                fx("leaf_cutter", E.LEAF_CUTTER to 1.0), fx("feller", E.AXE_SPEED to 0.12), fx("forager", E.LEAF_DROPS to 0.2), fx("timber", E.LOG_YIELD to 0.1),
                fx("sawyer", E.SAWMILL to 0.12), fx("climber", "jump" to 0.06), fx("lumber_camp", E.CLASS_XP to 0.03)
            ),
            listOf(fx("the_oak", E.LOG_YIELD to 0.05), fx("the_axe", E.AXE_DAMAGE to 0.25), fx("the_canopy", E.LEAF_DROPS to 0.1), fx("the_root", E.SAWMILL to 0.08), fx("the_grove", E.SAPLING_AURA to 1.0)),
            listOf(
                core("chopper", E.AXE_SPEED, 0.05, 5) to core("splitting_blow", E.AXE_DAMAGE, 0.4, 4),
                core("lumber_yield", E.LOG_YIELD, 0.05, 5) to core("woodcleaver", E.TOOL_AXE, 1.0, 1),
                core("gatherer", E.LEAF_DROPS, 0.1, 5) to core("grove_keeper", E.SAPLING_AURA, 1.0, 5)
            ),
            listOf(
                fx("timberlord", E.AXE_SPEED to 0.8, E.LOG_YIELD to 0.3, E.PICKAXE_SPEED to -0.4, "health" to -4.0),
                fx("grove_warden", E.SAPLING_AURA to 4.0, E.LEAF_DROPS to 0.4, E.SAWMILL to 0.3, E.DAMAGE_DEALT to -0.2, "health" to -4.0)
            )
        ),
        SkillClass.MINER to Spec(
            listOf(mn("mining_speed", 0.02, 3), mn(E.ORE_FORTUNE, 0.02, 3), mn(E.ORE_XP, 0.04, 3), mn(E.PICKAXE_DAMAGE, 0.05), mn(E.DEEP_SPEED, 0.03), mn("submerged", 0.05)),
            listOf(
                fx("deepslate", E.DEEPSLATE to 1.0), fx("dark_sight", E.DARK_SIGHT to 1.0), fx("quarryman", "mining_speed" to 0.08), fx("vein_sense", E.VEIN to 0.05),
                fx("prospector", Skills.SCAN_RANGE to 0.25), fx("gemcutter", E.ORE_XP to 0.12), fx("hard_hat", "armor" to 0.5)
            ),
            listOf(fx("the_pick", E.PICKAXE_DAMAGE to 0.25), fx("the_lantern", E.DEEP_SPEED to 0.1), fx("the_vein", E.ORE_FORTUNE to 0.05), fx("the_quarry", "mining_speed" to 0.05), fx("the_depths", "submerged" to 0.25)),
            listOf(
                core("efficiency", "mining_speed", 0.04, 5) to core("pick_fighter", E.PICKAXE_DAMAGE, 0.5, 4),
                core("fortune", E.ORE_FORTUNE, 0.06, 5) to core("war_pick", E.TOOL_PICKAXE, 1.0, 1),
                core("deep_delver", E.DEEP_SPEED, 0.05, 5) to core("vein_breaker", E.VEIN, 0.1, 5)
            ),
            listOf(
                fx("tunnel_rat", E.DEEP_SPEED to 0.4, E.DARK_SIGHT to 1.0, "health" to -5.0),
                fx("motherlode", E.ORE_FORTUNE to 0.3, E.VEIN to 0.2, E.ORE_XP to 0.4, E.DAMAGE_DEALT to -0.25, "speed" to -0.05)
            )
        ),
        SkillClass.SOLDIER to Spec(
            listOf(mn("health", 0.25), mn(E.SWORD_DAMAGE, 0.1), mn("knockback", 0.01), mn("armor", 0.05), mn(E.CRIT_DAMAGE, 0.01), mn(E.KILL_XP, 0.05, 3)),
            listOf(
                fx("bloodlust", E.BLOODLUST to 1.0), fx("bulwark", E.BULWARK to 0.15), fx("steady", "knockback" to 0.05), fx("plate", "armor" to 0.25),
                fx("swift_strikes", "attack_speed" to 0.03), fx("veteran", "toughness" to 1.0), fx("duelist", E.CRIT_DAMAGE to 0.05)
            ),
            listOf(fx("the_shield", "armor" to 0.15), fx("the_blade", E.SWORD_DAMAGE to 0.25), fx("the_banner", "health" to 0.5), fx("the_spear", E.CRIT_DAMAGE to 0.03), fx("the_wall", "toughness" to 0.5)),
            listOf(
                core("vitality", "health", 0.75, 4) to core("hardened", "toughness", 0.25, 4),
                core("blade_master", E.SWORD_DAMAGE, 0.3, 4) to core("precision", E.CRIT_DAMAGE, 0.04, 4),
                core("second_wind", E.SECOND_WIND, 2.0, 3) to core("trophy_hunter", E.KILL_XP, 0.1, 5)
            ),
            listOf(
                fx("berserker", E.BERSERK to 0.25, "health" to -6.0),
                fx("juggernaut", "armor" to 1.0, "toughness" to 2.0, "knockback" to 0.1, "attack_speed" to -0.2, "speed" to -0.08)
            )
        ),
        SkillClass.WORKER to Spec(
            listOf(mn(E.MACHINE_SPEED, 0.03, 3), mn(E.DURABILITY_SAVE, 0.02, 3), mn("reach", 0.05), mn("fire_guard", 0.02), mn("step", 0.05), mn("blast_guard", 0.04)),
            listOf(
                fx("stairwalker", "step" to 0.3), fx("long_arms", "reach" to 0.3), fx("mechanic", E.MACHINE_SPEED to 0.15), fx("fireproof", "fire_guard" to 0.15),
                fx("tinker", E.DURABILITY_SAVE to 0.1), fx("blast_shield", "blast_guard" to 0.2), fx("foreman", E.CLASS_XP to 0.03)
            ),
            listOf(fx("the_gear", E.MACHINE_SPEED to 0.05), fx("the_wrench", E.DURABILITY_SAVE to 0.05), fx("the_furnace", "fire_guard" to 0.1), fx("the_crane", "reach" to 0.15), fx("the_bulkhead", "blast_guard" to 0.1)),
            listOf(
                core("engineer", E.MACHINE_SPEED, 0.05, 5) to core("tinkerer", E.DURABILITY_SAVE, 0.04, 5),
                core("extended_reach", "reach", 0.1, 5) to core("heat_shield", "fire_guard", 0.06, 5),
                core("bomb_squad", "blast_guard", 0.1, 5) to core("overseer", E.CLASS_XP, 0.03, 5)
            ),
            listOf(
                fx("automaton", E.MACHINE_SPEED to 0.5, E.DURABILITY_SAVE to 0.15, E.DAMAGE_DEALT to -0.15, "health" to -4.0),
                fx("ironclad", "fire_guard" to 0.3, "blast_guard" to 0.3, "step" to 0.4, "speed" to -0.06, "mining_speed" to -0.15)
            )
        )
    )

    val nodes: List<SkillNode>
    val edges: List<SkillEdge>
    val constellations: List<Constellation>
    val starts: Map<SkillClass, Int>
    val signature: String
    val radius = RIM + 40.0
    val listed = setOf(NodeKind.NOTABLE, NodeKind.CORE, NodeKind.KEYSTONE, NodeKind.BONUS)

    init {
        val b = Builder()
        b.build()
        nodes = b.nodes
        edges = b.edges
        constellations = b.clusters
        starts = nodes.filter { it.kind == NodeKind.START }.associate { it.cls to it.id }
        signature = Integer.toHexString(nodes.joinToString(";") { "${it.kind}:${it.cls.id}:${it.name}:${it.ranks}:${it.alt}:${it.links.sorted()}" }.hashCode())
    }

    private class Builder {
        val nodes = ArrayList<SkillNode>()
        val edges = ArrayList<SkillEdge>()
        val clusters = ArrayList<Constellation>()
        private var travel = 0

        fun polar(deg: Double, r: Double): Pair<Double, Double> {
            val a = Math.toRadians(deg - 90.0)
            return cos(a) * r to sin(a) * r
        }

        fun node(kind: NodeKind, cls: SkillClass, f: Fx, at: Pair<Double, Double>, ranks: Int = 1, gate: Gate? = null, cluster: Int = -1): Int {
            nodes += SkillNode(nodes.size, kind, cls, f.name, f.effects, ranks, at.first, at.second, gate, cluster)
            return nodes.size - 1
        }

        fun link(a: Int, b: Int, arc: Boolean = false, cx: Double = 0.0, cy: Double = 0.0) {
            if (b in nodes[a].links) return
            nodes[a].links += b
            nodes[b].links += a
            edges += SkillEdge(a, b, arc, cx, cy)
        }

        fun chain(from: List<Int>, vararg next: Int): Int {
            from.forEach { link(it, next[0]) }
            for (i in 1 until next.size) link(next[i - 1], next[i])
            return next.last()
        }

        fun travel(cls: SkillClass, at: Pair<Double, Double>) = node(NodeKind.TRAVEL, cls, TRAVEL[travel++ % TRAVEL.size], at)

        fun build() {
            val step = 360.0 / SkillClass.entries.size
            val inner = HashMap<SkillClass, IntArray>()
            val rings = HashMap<SkillClass, List<Map<Int, Int>>>()
            val spurs = ArrayList<Triple<Int, String, Pair<Double, Double>>>()
            SkillClass.entries.forEachIndexed { i, cls ->
                val spec = specs.getValue(cls)
                val t = i * step
                val s = if (i % 2 == 0) 1.0 else -1.0
                var minor = 0
                fun p(a: Double, r: Double) = polar(t + s * a, r)
                fun m(a: Double, r: Double): Int { val x = spec.minors[minor++ % spec.minors.size]; return node(NodeKind.MINOR, cls, Fx(x.effect, mapOf(x.effect to x.value)), p(a, r), x.ranks) }
                fun fork(slot: Int, r: Double): Pair<Int, Int> {
                    val da = Math.toDegrees(FORK / r)
                    val (l, o) = spec.forks[slot]
                    val a = node(NodeKind.CORE, cls, Fx(l.name, mapOf(l.effect to l.value)), p(-da, r), l.ranks, GATES[slot])
                    val c = node(NodeKind.CORE, cls, Fx(o.name, mapOf(o.effect to o.value)), p(da, r), o.ranks, GATES[slot])
                    nodes[a].alt = c
                    nodes[c].alt = a
                    return a to c
                }
                val start = node(NodeKind.START, cls, Fx("start", mapOf(E.CLASS_XP to 0.02)), polar(t, R0))
                val hub = intArrayOf(start, travel(cls, polar(t + 15, R0)), travel(cls, polar(t + 30, R0)))
                link(hub[0], hub[1], true); link(hub[1], hub[2], true)
                inner[cls] = hub
                val ring = RINGS.map { r -> listOf(-15, -5, 5, 15).associateWith { a -> travel(cls, polar(t + a, r)) } }
                ring.forEach { n -> link(n.getValue(-15), n.getValue(-5), true); link(n.getValue(-5), n.getValue(5), true); link(n.getValue(5), n.getValue(15), true) }
                rings[cls] = ring
                fun at(r: Int, a: Int) = ring[r].getValue((a * s).toInt())
                fun wheel(w: Int, a: Double, r: Double, wr: Double, from: Int, to: Int) {
                    val (cx, cy) = p(a, r)
                    val face = atan2(cy, cx) + Math.PI
                    val id = clusters.size
                    val ring6 = IntArray(6) { j ->
                        val ang = face + j * Math.PI / 3
                        val x = spec.minors[minor++ % spec.minors.size]
                        node(NodeKind.MINOR, cls, Fx(x.effect, mapOf(x.effect to x.value)), cos(ang) * wr + cx to sin(ang) * wr + cy, x.ranks, cluster = id)
                    }
                    for (j in 0 until 6) link(ring6[j], ring6[(j + 1) % 6], true, cx, cy)
                    val notable = node(NodeKind.NOTABLE, cls, spec.notables[w], cx to cy, cluster = id)
                    link(notable, ring6[3])
                    link(from, ring6[0])
                    link(ring6[3], to)
                    clusters += Constellation(id, cls, spec.bonuses[w].name, ring6 + notable, spec.bonuses[w].effects, cx, cy, wr)
                }
                fun join(last: Int, pair: Pair<Int, Int>, r: Int) {
                    link(last, pair.first); link(last, pair.second)
                    link(pair.first, at(r, -5)); link(pair.second, at(r, 5))
                }
                join(chain(listOf(start), m(0.0, 175.0), m(0.0, 225.0)), fork(0, 280.0), 0)
                wheel(0, -13.0, 215.0, 28.0, start, at(0, -15))
                wheel(1, 13.0, 215.0, 28.0, start, at(0, 15))
                val spur1 = m(0.0, 385.0)
                spurs += Triple(spur1, BONUSES.getValue(cls).first, p(7.4, 388.2))
                join(chain(listOf(at(0, -5), at(0, 5)), spur1, m(0.0, 435.0), node(NodeKind.NOTABLE, cls, spec.notables[5], p(0.0, 485.0))), fork(1, 540.0), 1)
                wheel(2, -15.0, 470.0, 36.0, at(0, -15), at(1, -15))
                wheel(3, 15.0, 470.0, 36.0, at(0, 15), at(1, 15))
                val top = fork(2, 760.0)
                val gate = chain(listOf(at(1, -5), at(1, 5)), m(0.0, 655.0), m(0.0, 705.0))
                link(gate, top.first); link(gate, top.second)
                chain(listOf(top.first, top.second), m(0.0, 815.0)).let { link(it, at(2, -5)); link(it, at(2, 5)) }
                val spur2 = node(NodeKind.NOTABLE, cls, spec.notables[6], p(-15.0, 760.0))
                spurs += Triple(spur2, BONUSES.getValue(cls).second, p(-11.84, 786.2))
                chain(listOf(at(1, -15)), m(-15.0, 660.0), m(-15.0, 710.0), spur2, m(-15.0, 815.0)).let { link(it, at(2, -15)) }
                wheel(4, 15.0, 740.0, 40.0, at(1, 15), at(2, 15))
                val k1 = chain(listOf(at(2, -5)), m(-8.0, 935.0), node(NodeKind.KEYSTONE, cls, spec.keystones[0], p(-8.0, RIM), gate = KEY_GATE))
                val k2 = chain(listOf(at(2, 5)), m(8.0, 935.0), node(NodeKind.KEYSTONE, cls, spec.keystones[1], p(8.0, RIM), gate = KEY_GATE))
                nodes[k1].alt = k2
                nodes[k2].alt = k1
            }
            SkillClass.entries.forEachIndexed { i, cls ->
                val next = SkillClass.entries[(i + 1) % SkillClass.entries.size]
                link(inner.getValue(cls)[2], inner.getValue(next)[0], true)
                rings.getValue(cls).indices.forEach { r -> link(rings.getValue(cls)[r].getValue(15), rings.getValue(next)[r].getValue(-15), true) }
            }
            spurs.forEach { (parent, key, at) -> link(parent, node(NodeKind.BONUS, nodes[parent].cls, Fx(key, mapOf(key to 1.0)), at)) }
        }
    }

    fun values(owned: Map<Int, Int>): Map<String, Double> {
        val out = HashMap<String, Double>()
        owned.forEach { (id, rank) -> nodes.getOrNull(id)?.effects?.forEach { (k, v) -> out.merge(k, v * rank, Double::plus) } }
        constellations.forEach { c -> if (c.nodes.all { it in owned }) c.bonus.forEach { (k, v) -> out.merge(k, v, Double::plus) } }
        return out
    }

    fun sector(owned: Map<Int, Int>, cls: SkillClass) = owned.entries.sumOf { (id, rank) ->
        nodes.getOrNull(id)?.takeIf { it.cls == cls && (it.kind == NodeKind.MINOR || it.kind == NodeKind.NOTABLE || it.kind == NodeKind.CORE) }?.let { rank } ?: 0
    }

    fun keystones(owned: Map<Int, Int>) = owned.keys.count { nodes.getOrNull(it)?.kind == NodeKind.KEYSTONE }

    fun levelNeed(owned: Map<Int, Int>, n: SkillNode, rank: Int) = (n.gate?.level(rank) ?: 0) + if (n.kind == NodeKind.KEYSTONE) KEY_LEVEL_STEP * keystones(owned) else 0

    fun blocked(owned: Map<Int, Int>, n: SkillNode) = n.alt >= 0 && n.alt in owned

    fun denial(owned: Map<Int, Int>, cls: SkillClass?, level: Int, free: Int, n: SkillNode): Denial? {
        val rank = owned[n.id] ?: 0
        return when {
            rank >= n.ranks -> Denial.MAXED
            cls == null -> Denial.NO_CLASS
            rank == 0 && n.links.none { it in owned } -> Denial.PATH
            rank == 0 && blocked(owned, n) -> if (n.kind == NodeKind.KEYSTONE) Denial.KEYSTONE else Denial.ALT
            n.gate != null && level < levelNeed(owned, n, rank + 1) -> Denial.LEVEL
            n.gate != null && sector(owned, n.cls) < n.gate.sector(rank + 1) -> Denial.SECTOR
            free <= 0 -> Denial.POINTS
            else -> null
        }
    }

    fun spent(owned: Map<Int, Int>, cls: SkillClass?) = owned.entries.sumOf { (id, rank) -> if (cls != null && id == starts[cls]) 0 else rank }

    fun connected(owned: Set<Int>, root: Int): Boolean {
        if (root !in owned) return owned.isEmpty()
        val seen = hashSetOf(root)
        val queue = ArrayDeque(listOf(root))
        while (queue.isNotEmpty()) nodes[queue.removeFirst()].links.forEach { if (it in owned && seen.add(it)) queue += it }
        return seen.size == owned.size
    }
}
