package kami.claims.research

import kami.libs.mc.RegistryTags
import kami.libs.mc.Selectors
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import net.minecraft.core.registries.BuiltInRegistries

object Kinds {
    const val DEPOSIT = "deposit"
    const val MINE = "mine"
    const val FELL = "fell"
    const val PLACE = "place"
    const val KILL = "kill"
    const val CRAFT = "craft"
    const val SMELT = "smelt"
    const val ENCHANT = "enchant"
    const val BREAK_ITEM = "break_item"
    const val VISIT = "visit"
    const val TAXES = "taxes"
    const val TRADE = "trade"
    const val TRADE_VALUE = "trade_value"
    const val HOLD = "hold"
    const val STRUCTURE = "structure"
    const val PROCESS = "process"
}

@Serializable
sealed interface Task {
    val kind: String
    val target: Long
    val subject: String get() = ""
    val manual: Boolean get() = false
    fun accepts(subject: String): Boolean = true
    fun qualified(tree: String): Task = this
}

private fun selectorList(single: String, many: List<String>) = listOf(single).filter { it.isNotBlank() } + many

@Serializable
@SerialName(Kinds.DEPOSIT)
class DepositTask(val item: String = "", val items: List<String> = emptyList(), val count: Long = 1) : Task {
    val selectors get() = selectorList(item, items)
    override val kind get() = Kinds.DEPOSIT
    override val manual get() = true
    override val target get() = count
    override val subject get() = selectors.joinToString(",")
    override fun accepts(subject: String) = selectors.any { itemMatches(it, subject) }
}

@Serializable
@SerialName(Kinds.MINE)
class MineTask(val block: String = "", val blocks: List<String> = emptyList(), val count: Long = 1) : Task {
    val selectors get() = selectorList(block, blocks)
    override val kind get() = Kinds.MINE
    override val target get() = count
    override val subject get() = selectors.joinToString(",")
    override fun accepts(subject: String) = selectors.any { blockMatches(it, subject) }
}

@Serializable
@SerialName(Kinds.FELL)
class FellTask(val count: Long = 1) : Task {
    override val kind get() = Kinds.FELL
    override val target get() = count
}

@Serializable
@SerialName(Kinds.PLACE)
class PlaceTask(val block: String, val count: Long = 1) : Task {
    override val kind get() = Kinds.PLACE
    override val target get() = count
    override val subject get() = block
    override fun accepts(subject: String) = blockMatches(block, subject)
}

@Serializable
@SerialName(Kinds.KILL)
class KillTask(val entity: String, val count: Long = 1) : Task {
    override val kind get() = Kinds.KILL
    override val target get() = count
    override val subject get() = entity
    override fun accepts(subject: String) = entityMatches(entity, subject)
}

@Serializable
@SerialName(Kinds.CRAFT)
class CraftTask(val output: String, val count: Long = 1) : Task {
    override val kind get() = Kinds.CRAFT
    override val target get() = count
    override val subject get() = output
    override fun accepts(subject: String) = itemMatches(output, subject)
}

@Serializable
@SerialName(Kinds.SMELT)
class SmeltTask(val output: String = "", val count: Long = 1) : Task {
    override val kind get() = Kinds.SMELT
    override val target get() = count
    override val subject get() = output
    override fun accepts(subject: String) = output.isBlank() || itemMatches(output, subject)
}

@Serializable
@SerialName(Kinds.ENCHANT)
class EnchantTask(val count: Long = 1) : Task {
    override val kind get() = Kinds.ENCHANT
    override val target get() = count
}

@Serializable
@SerialName(Kinds.BREAK_ITEM)
class BreakItemTask(val count: Long = 1) : Task {
    override val kind get() = Kinds.BREAK_ITEM
    override val target get() = count
}

@Serializable
@SerialName(Kinds.VISIT)
class VisitTask(val dimension: String) : Task {
    override val kind get() = Kinds.VISIT
    override val target get() = 1L
    override val subject get() = dimension
    override fun accepts(subject: String) = subject == dimension
}

@Serializable
@SerialName(Kinds.TAXES)
class TaxesTask(val spurs: Long) : Task {
    override val kind get() = Kinds.TAXES
    override val target get() = spurs
}

@Serializable
@SerialName(Kinds.TRADE)
class TradeTask(val count: Long = 1) : Task {
    override val kind get() = Kinds.TRADE
    override val target get() = count
}

@Serializable
@SerialName(Kinds.TRADE_VALUE)
class TradeValueTask(val spurs: Long) : Task {
    override val kind get() = Kinds.TRADE_VALUE
    override val target get() = spurs
}

@Serializable
@SerialName("event")
class EventTask(override val kind: String, override val subject: String = "", val count: Long = 1) : Task {
    override val target get() = count
    override fun accepts(subject: String) = this.subject.isBlank() || Selectors.matches(this.subject, subject) { false }
}

@Serializable
@SerialName(Kinds.HOLD)
class HoldTask(val condition: Condition) : Task {
    override val kind get() = Kinds.HOLD
    override val manual get() = true
    override val target get() = 1L
    override val subject get() = condition.describe().json()
    override fun qualified(tree: String) = HoldTask(condition.qualified(tree))
}

@Serializable
@SerialName(Kinds.STRUCTURE)
class StructureTask(val structure: String, val count: Long = 1) : Task {
    override val kind get() = Kinds.STRUCTURE
    override val target get() = count
    override val subject get() = structure
    override fun accepts(subject: String) = subject == structure
}

@Serializable
@SerialName(Kinds.PROCESS)
class ProcessTask(val recipeType: String = "", val output: String = "", val count: Long = 1) : Task {
    override val kind get() = Kinds.PROCESS
    override val target get() = count
    override val subject get() = output.ifBlank { recipeType }
    override fun accepts(subject: String): Boolean {
        val type = subject.substringBefore('|')
        val result = subject.substringAfter('|', "")
        return (recipeType.isBlank() || Selectors.glob(recipeType, type)) &&
            (output.isBlank() || itemMatches(output, result))
    }
}

private fun itemMatches(spec: String, id: String) = Selectors.matches(spec, id) { RegistryTags.contains(BuiltInRegistries.ITEM, it, id) }

private fun blockMatches(spec: String, id: String) = Selectors.matches(spec, id) { RegistryTags.contains(BuiltInRegistries.BLOCK, it, id) }

private fun entityMatches(spec: String, id: String) = Selectors.matches(spec, id) { RegistryTags.contains(BuiltInRegistries.ENTITY_TYPE, it, id) }
