package kami.claims.research

import kami.claims.Country
import kami.libs.text.Phrase
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlin.time.Duration

@Serializable
enum class Scope {
    @SerialName("country") COUNTRY,
    @SerialName("province") PROVINCE,
    @SerialName("any") ANY
}

@Serializable
enum class Capacity {
    @SerialName("chunks") CHUNKS,
    @SerialName("provinces") PROVINCES,
    @SerialName("citizens") CITIZENS,
    @SerialName("researchSlots") RESEARCH_SLOTS,
    @SerialName("queueSlots") QUEUE_SLOTS,
    @SerialName("treasury") TREASURY,
    @SerialName("officers") OFFICERS,
    @SerialName("marketSlots") MARKET_SLOTS,
    @SerialName("auctionSlots") AUCTION_SLOTS,
    @SerialName("freeChunks") FREE_CHUNKS,
    @SerialName("plots") PLOTS,
    @SerialName("jobSlots") JOB_SLOTS;

    val id: String get() = Capacity.serializer().descriptor.getElementName(ordinal)
}

object DurationText : KSerializer<Duration> {
    override val descriptor = PrimitiveSerialDescriptor("DurationText", PrimitiveKind.STRING)
    override fun serialize(encoder: Encoder, value: Duration) = encoder.encodeString(value.toString())
    override fun deserialize(decoder: Decoder): Duration = Duration.parse(decoder.decodeString())
}

private val numbered = Regex("""(.+)_(\d+)""")

fun nodeLabel(tree: String, id: String, title: String): Phrase {
    val key = "kami_claims.research.node.$tree.$id"
    val n = numbered.matchEntire(id) ?: return Phrase.or(key, title)
    return Phrase.or(key, Phrase.of("kami_libs.format.join", Phrase.or("kami_claims.research.node.$tree.${n.groupValues[1]}", title), n.groupValues[2]))
}

@Serializable
class Category(val id: String, val title: String = "", val icon: String = "", val order: Int = 0)

@Serializable
data class Node(
    val id: String,
    val category: String,
    val title: String = "",
    val description: String = "",
    val icon: String = "",
    val level: Int = 0,
    val cost: Long = 0,
    @Serializable(DurationText::class) val time: Duration = Duration.ZERO,
    val xp: Long? = null,
    val requires: List<Condition> = emptyList(),
    val tasks: List<Task> = emptyList(),
    val unlocks: List<Unlock> = emptyList(),
    val x: Int? = null,
    val y: Int? = null,
    @Transient val tree: String = ""
) {
    val key get() = "$tree:$id"

    fun conditions(): List<Condition> = if (level > 0) listOf<Condition>(MinLevel(level)) + requires else requires

    fun label() = nodeLabel(tree, id, title)

    fun allConditions(): List<Condition> = requires + tasks.filterIsInstance<HoldTask>().map { it.condition }

    fun dependencies(): List<String> = allConditions().flatMap { it.refs() }

    fun placed(tree: String) = copy(requires = requires.map { it.qualified(tree) }, tasks = tasks.map { it.qualified(tree) }, tree = tree)
}

class Tree(val id: String, val title: String, val scope: Scope, val categories: List<Category>, val nodes: List<Node>) {
    fun serves(country: Country) = when (scope) {
        Scope.ANY -> true
        Scope.COUNTRY -> country.parent == null
        Scope.PROVINCE -> country.parent != null
    }
}
