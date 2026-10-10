package kami.essentials.skills

import kami.libs.skills.Skills
import kami.libs.ui.style.Format
import net.minecraft.core.Holder
import net.minecraft.world.entity.ai.attributes.Attribute
import net.minecraft.world.entity.ai.attributes.AttributeModifier.Operation
import net.minecraft.world.entity.ai.attributes.Attributes
import kotlin.math.abs

enum class Shown { PCT, FLAT, FLAG, SECONDS }

class Effect(val shown: Shown, val attribute: Holder<Attribute>? = null, val op: Operation = Operation.ADD_VALUE, val sign: Double = 1.0, val cap: Double = Double.MAX_VALUE, val floor: Double = -Double.MAX_VALUE)

object Effects {
    const val CLASS_XP = "class_xp"
    const val ORB_XP = "orb_xp"
    const val EAT_PAPER = "eat_paper"
    const val ENCHANT_REFUND = "enchant_refund"
    const val ANVIL_SAVE = "anvil_save"
    const val BREW_DURATION = "brew_duration"
    const val DAMAGE_DEALT = "damage_dealt"
    const val PICKAXE_DAMAGE = "pickaxe_damage"
    const val HOE_DAMAGE = "hoe_damage"
    const val AXE_DAMAGE = "axe_damage"
    const val SWORD_DAMAGE = "sword_damage"
    const val CRIT_DAMAGE = "crit_damage"
    const val BERSERK = "berserk"
    const val BLOODLUST = "bloodlust"
    const val BULWARK = "bulwark"
    const val SECOND_WIND = "second_wind"
    const val DEEP_SPEED = "deep_speed"
    const val LAND_MINING = "land_mining"
    const val PICKAXE_SPEED = "pickaxe_speed"
    const val DEEPSLATE = "deepslate"
    const val ORE_FORTUNE = "ore_fortune"
    const val VEIN = "vein"
    const val DARK_SIGHT = "dark_sight"
    const val TOOL_PICKAXE = "tool_pickaxe"
    const val TOOL_HOE = "tool_hoe"
    const val TOOL_AXE = "tool_axe"
    const val CROP_FORTUNE = "crop_fortune"
    const val REPLANT = "replant"
    const val BUTCHER = "butcher"
    const val BREED = "breed"
    const val BONEMEAL_SAVE = "bonemeal_save"
    const val CROP_AURA = "crop_aura"
    const val FARMLAND_CALM = "farmland_calm"
    const val VEGGIE_FOOD = "veggie_food"
    const val DOUBLE_CATCH = "double_catch"
    const val FISH_BREATH = "fish_breath"
    const val FISH_FOOD = "fish_food"
    const val ROD_SAVE = "rod_save"
    const val SEA_GRACE = "sea_grace"
    const val SEA_CHILD = "sea_child"
    const val AXE_SPEED = "axe_speed"
    const val LOG_YIELD = "log_yield"
    const val LEAF_CUTTER = "leaf_cutter"
    const val LEAF_DROPS = "leaf_drops"
    const val SAPLING_AURA = "sapling_aura"
    const val MACHINE_SPEED = "machine_speed"
    const val DURABILITY_SAVE = "durability_save"
    const val SILVER_TONGUE = "silver_tongue"
    const val KILL_XP = "kill_xp"
    const val ORE_XP = "ore_xp"
    const val PAPER_PRESS = "paper_press"
    const val SAWMILL = "sawmill"
    const val QUICK_BITE = "quick_bite"
    const val CALF_GROWTH = "calf_growth"
    const val WATER_SIGHT = "water_sight"
    const val FISH_XP = "fish_xp"
    const val POCKET_CRAFT = "pocket_craft"
    const val SLEEPLESS = "sleepless"
    const val SHOWMAN = "showman"
    const val ENDER_SATCHEL = "ender_satchel"
    const val RAVAGER_TAMER = "ravager_tamer"
    const val BEE_FRIEND = "bee_friend"
    const val BUCKET_FISHER = "bucket_fisher"
    const val DROWNED_TRUCE = "drowned_truce"
    const val LEAF_LANDING = "leaf_landing"
    const val TREEHUGGER = "treehugger"
    const val LAVA_WALKER = "lava_walker"
    const val CANARY = "canary"
    const val HORSE_WHISPERER = "horse_whisperer"
    const val HEADHUNTER = "headhunter"
    const val LADDER_RUSH = "ladder_rush"
    const val MAGNET = "magnet"

    val all: Map<String, Effect> = mapOf(
        CLASS_XP to Effect(Shown.PCT, cap = 0.15),
        ORB_XP to Effect(Shown.PCT),
        EAT_PAPER to Effect(Shown.FLAG),
        ENCHANT_REFUND to Effect(Shown.PCT, cap = 0.75),
        ANVIL_SAVE to Effect(Shown.PCT),
        BREW_DURATION to Effect(Shown.PCT, cap = 0.6),
        Skills.RESEARCH_TIME to Effect(Shown.PCT, cap = Skills.cap(Skills.RESEARCH_TIME)),
        Skills.RESEARCH_COST to Effect(Shown.PCT, cap = Skills.cap(Skills.RESEARCH_COST)),
        Skills.RESEARCH_INSIGHT to Effect(Shown.PCT, cap = Skills.cap(Skills.RESEARCH_INSIGHT)),
        Skills.SCAN_RANGE to Effect(Shown.PCT, cap = Skills.cap(Skills.SCAN_RANGE)),
        Skills.AUCTION_FEE to Effect(Shown.PCT, cap = Skills.cap(Skills.AUCTION_FEE)),
        Skills.ORDER_SLOTS to Effect(Shown.FLAT, cap = Skills.cap(Skills.ORDER_SLOTS)),
        Skills.AUCTION_SLOTS to Effect(Shown.FLAT, cap = Skills.cap(Skills.AUCTION_SLOTS)),
        Skills.INFINITE_LOTS to Effect(Shown.FLAT, cap = Skills.cap(Skills.INFINITE_LOTS)),
        DAMAGE_DEALT to Effect(Shown.PCT, cap = 0.0),
        PICKAXE_DAMAGE to Effect(Shown.FLAT, cap = 3.0),
        HOE_DAMAGE to Effect(Shown.FLAT, cap = 2.5),
        AXE_DAMAGE to Effect(Shown.FLAT, cap = 2.5),
        SWORD_DAMAGE to Effect(Shown.FLAT, cap = 2.0),
        CRIT_DAMAGE to Effect(Shown.PCT, cap = 0.4),
        BERSERK to Effect(Shown.PCT, cap = 0.25),
        BLOODLUST to Effect(Shown.FLAT, cap = 2.0),
        BULWARK to Effect(Shown.PCT, cap = 0.25),
        SECOND_WIND to Effect(Shown.SECONDS, cap = 6.0),
        DEEP_SPEED to Effect(Shown.PCT, cap = 0.75),
        LAND_MINING to Effect(Shown.PCT, cap = 0.0, floor = -0.5),
        PICKAXE_SPEED to Effect(Shown.PCT, cap = 0.6),
        DEEPSLATE to Effect(Shown.FLAG),
        ORE_FORTUNE to Effect(Shown.PCT, cap = 0.75),
        VEIN to Effect(Shown.PCT, cap = 0.5),
        DARK_SIGHT to Effect(Shown.FLAG),
        TOOL_PICKAXE to Effect(Shown.FLAG),
        TOOL_HOE to Effect(Shown.FLAG),
        TOOL_AXE to Effect(Shown.FLAG),
        CROP_FORTUNE to Effect(Shown.PCT, cap = 0.8),
        REPLANT to Effect(Shown.FLAG),
        BUTCHER to Effect(Shown.PCT, cap = 0.8),
        BREED to Effect(Shown.PCT, cap = 0.9),
        BONEMEAL_SAVE to Effect(Shown.PCT, cap = 0.5),
        CROP_AURA to Effect(Shown.FLAT),
        FARMLAND_CALM to Effect(Shown.FLAG),
        VEGGIE_FOOD to Effect(Shown.FLAT, cap = 3.0),
        DOUBLE_CATCH to Effect(Shown.PCT, cap = 0.6),
        FISH_BREATH to Effect(Shown.SECONDS, cap = 300.0),
        FISH_FOOD to Effect(Shown.FLAT, cap = 3.0),
        ROD_SAVE to Effect(Shown.PCT, cap = 0.5),
        SEA_GRACE to Effect(Shown.FLAG),
        SEA_CHILD to Effect(Shown.FLAG),
        AXE_SPEED to Effect(Shown.PCT, cap = 1.0),
        LOG_YIELD to Effect(Shown.PCT, cap = 0.8),
        LEAF_CUTTER to Effect(Shown.FLAG),
        LEAF_DROPS to Effect(Shown.PCT, cap = 0.8),
        SAPLING_AURA to Effect(Shown.FLAT),
        MACHINE_SPEED to Effect(Shown.PCT, cap = 1.0),
        DURABILITY_SAVE to Effect(Shown.PCT, cap = 0.5),
        SILVER_TONGUE to Effect(Shown.FLAT, cap = 5.0),
        KILL_XP to Effect(Shown.PCT),
        ORE_XP to Effect(Shown.PCT, cap = 1.0),
        PAPER_PRESS to Effect(Shown.PCT, cap = 0.5),
        SAWMILL to Effect(Shown.PCT, cap = 0.6),
        QUICK_BITE to Effect(Shown.PCT, cap = 0.5),
        CALF_GROWTH to Effect(Shown.PCT, cap = 0.8),
        WATER_SIGHT to Effect(Shown.FLAG),
        FISH_XP to Effect(Shown.PCT, cap = 1.0),
        POCKET_CRAFT to Effect(Shown.FLAG),
        SLEEPLESS to Effect(Shown.FLAG),
        SHOWMAN to Effect(Shown.FLAG),
        ENDER_SATCHEL to Effect(Shown.FLAG),
        RAVAGER_TAMER to Effect(Shown.FLAG),
        BEE_FRIEND to Effect(Shown.FLAG),
        BUCKET_FISHER to Effect(Shown.FLAG),
        DROWNED_TRUCE to Effect(Shown.FLAG),
        LEAF_LANDING to Effect(Shown.FLAG),
        TREEHUGGER to Effect(Shown.FLAG),
        LAVA_WALKER to Effect(Shown.FLAG),
        CANARY to Effect(Shown.FLAG),
        HORSE_WHISPERER to Effect(Shown.FLAG),
        HEADHUNTER to Effect(Shown.FLAG),
        LADDER_RUSH to Effect(Shown.FLAG),
        MAGNET to Effect(Shown.FLAG),
        "mining_speed" to Effect(Shown.PCT, Attributes.BLOCK_BREAK_SPEED, Operation.ADD_MULTIPLIED_BASE, cap = 0.5),
        "health" to Effect(Shown.FLAT, Attributes.MAX_HEALTH, cap = 8.0),
        "armor" to Effect(Shown.FLAT, Attributes.ARMOR, cap = 4.0),
        "toughness" to Effect(Shown.FLAT, Attributes.ARMOR_TOUGHNESS, cap = 4.0),
        "knockback" to Effect(Shown.PCT, Attributes.KNOCKBACK_RESISTANCE, cap = 0.25),
        "attack_speed" to Effect(Shown.PCT, Attributes.ATTACK_SPEED, Operation.ADD_MULTIPLIED_BASE, cap = 0.15),
        "speed" to Effect(Shown.PCT, Attributes.MOVEMENT_SPEED, Operation.ADD_MULTIPLIED_BASE, cap = 0.1),
        "oxygen" to Effect(Shown.FLAT, Attributes.OXYGEN_BONUS, cap = 4.0),
        "swim" to Effect(Shown.PCT, Attributes.WATER_MOVEMENT_EFFICIENCY, cap = 0.5),
        "submerged" to Effect(Shown.PCT, Attributes.SUBMERGED_MINING_SPEED, cap = 0.8),
        "luck" to Effect(Shown.FLAT, Attributes.LUCK, cap = 3.0),
        "reach" to Effect(Shown.FLAT, Attributes.BLOCK_INTERACTION_RANGE, cap = 1.5),
        "step" to Effect(Shown.FLAT, Attributes.STEP_HEIGHT, cap = 0.5),
        "fire_guard" to Effect(Shown.PCT, Attributes.BURNING_TIME, Operation.ADD_MULTIPLIED_BASE, -1.0, 0.7),
        "sneak_speed" to Effect(Shown.PCT, Attributes.SNEAKING_SPEED, cap = 0.3),
        "jump" to Effect(Shown.PCT, Attributes.JUMP_STRENGTH, Operation.ADD_MULTIPLIED_BASE, cap = 0.15),
        "blast_guard" to Effect(Shown.PCT, Attributes.EXPLOSION_KNOCKBACK_RESISTANCE, cap = 0.8)
    )

    fun capped(key: String, v: Double) = all[key]?.let { v.coerceIn(it.floor, it.cap) } ?: v

    fun value(key: String, v: Double): String {
        val sign = if (v < 0) "-" else "+"
        val a = abs(v)
        return when (all[key]?.shown) {
            Shown.PCT -> sign + Format.decimal(a * 100, if (a * 100 % 1.0 < 1e-6) 0 else 1) + "%"
            Shown.SECONDS -> Format.duration((a * 1000).toLong())
            Shown.FLAG -> ""
            else -> sign + Format.decimal(a, if (a % 1.0 < 1e-6) 0 else 2)
        }
    }
}
