package kami.claims.research

import kami.libs.mc.RegistryTags
import kami.libs.mc.Selectors
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import net.minecraft.core.registries.BuiltInRegistries

@Serializable
sealed interface Task {
    val kind: String
    val target: Long
    val manual: Boolean get() = false
    fun accepts(subject: String): Boolean = true
    fun qualified(tree: String): Task = this
}

@Serializable
@SerialName("deposit")
class DepositTask(val item: String = "", val items: List<String> = emptyList(), val count: Long = 1) : Task {
    val selectors get() = listOf(item).filter { it.isNotBlank() } + items
    override val kind get() = "deposit"
    override val manual get() = true
    override val target get() = count
    override fun accepts(subject: String) = Selectors.matchesAny(selectors, subject) { RegistryTags.contains(BuiltInRegistries.ITEM, it, subject) }
}

@Serializable
@SerialName("mine")
class MineTask(val block: String = "", val blocks: List<String> = emptyList(), val count: Long = 1) : Task {
    val selectors get() = listOf(block).filter { it.isNotBlank() } + blocks
    override val kind get() = "mine"
    override val target get() = count
    override fun accepts(subject: String) = Selectors.matchesAny(selectors, subject) { RegistryTags.contains(BuiltInRegistries.BLOCK, it, subject) }
}

@Serializable
@SerialName("place")
class PlaceTask(val block: String, val count: Long = 1) : Task {
    override val kind get() = "place"
    override val target get() = count
    override fun accepts(subject: String) = Selectors.matches(block, subject) { RegistryTags.contains(BuiltInRegistries.BLOCK, it, subject) }
}

@Serializable
@SerialName("kill")
class KillTask(val entity: String, val count: Long = 1) : Task {
    override val kind get() = "kill"
    override val target get() = count
    override fun accepts(subject: String) = Selectors.matches(entity, subject) { RegistryTags.contains(BuiltInRegistries.ENTITY_TYPE, it, subject) }
}

@Serializable
@SerialName("craft")
class CraftTask(val output: String, val count: Long = 1) : Task {
    override val kind get() = "craft"
    override val target get() = count
    override fun accepts(subject: String) = Selectors.matches(output, subject) { RegistryTags.contains(BuiltInRegistries.ITEM, it, subject) }
}

@Serializable
@SerialName("smelt")
class SmeltTask(val output: String = "", val count: Long = 1) : Task {
    override val kind get() = "smelt"
    override val target get() = count
    override fun accepts(subject: String) = output.isBlank() || Selectors.matches(output, subject) { RegistryTags.contains(BuiltInRegistries.ITEM, it, subject) }
}

@Serializable
@SerialName("enchant")
class EnchantTask(val count: Long = 1) : Task {
    override val kind get() = "enchant"
    override val target get() = count
}

@Serializable
@SerialName("break_item")
class BreakItemTask(val count: Long = 1) : Task {
    override val kind get() = "break_item"
    override val target get() = count
}

@Serializable
@SerialName("visit")
class VisitTask(val dimension: String) : Task {
    override val kind get() = "visit"
    override val target get() = 1L
    override fun accepts(subject: String) = subject == dimension
}

@Serializable
@SerialName("taxes")
class TaxesTask(val spurs: Long) : Task {
    override val kind get() = "taxes"
    override val target get() = spurs
}

@Serializable
@SerialName("trade")
class TradeTask(val count: Long = 1) : Task {
    override val kind get() = "trade"
    override val target get() = count
}

@Serializable
@SerialName("trade_value")
class TradeValueTask(val spurs: Long) : Task {
    override val kind get() = "trade_value"
    override val target get() = spurs
}

@Serializable
@SerialName("event")
class EventTask(override val kind: String, val subject: String = "", val count: Long = 1) : Task {
    override val target get() = count
    override fun accepts(subject: String) = this.subject.isBlank() || Selectors.matches(this.subject, subject) { false }
}

@Serializable
@SerialName("hold")
class HoldTask(val condition: Condition) : Task {
    override val kind get() = "hold"
    override val manual get() = true
    override val target get() = 1L
    override fun qualified(tree: String) = HoldTask(condition.qualified(tree))
}

@Serializable
@SerialName("structure")
class StructureTask(val structure: String, val count: Long = 1) : Task {
    override val kind get() = "structure"
    override val target get() = count
    override fun accepts(subject: String) = subject == structure
}

@Serializable
@SerialName("process")
class ProcessTask(val recipeType: String = "", val output: String = "", val count: Long = 1) : Task {
    override val kind get() = "process"
    override val target get() = count
    override fun accepts(subject: String): Boolean {
        val type = subject.substringBefore('|')
        val result = subject.substringAfter('|', "")
        return (recipeType.isBlank() || Selectors.glob(recipeType, type)) &&
            (output.isBlank() || Selectors.matches(output, result) { RegistryTags.contains(BuiltInRegistries.ITEM, it, result) })
    }
}
