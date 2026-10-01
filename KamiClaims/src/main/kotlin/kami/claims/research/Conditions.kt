package kami.claims.research

import kami.claims.Config
import kami.claims.Country
import kami.claims.Flag
import kami.claims.Realm
import kami.claims.now
import kami.libs.text.Phrase
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
sealed interface Condition {
    fun met(country: Country): Boolean
    fun describe(): Phrase
    fun refs(): List<String> = emptyList()
    fun hardRefs(): List<String> = emptyList()
    fun hiddenRefs(): List<String> = emptyList()
    fun qualified(tree: String): Condition = this
}

private fun key(name: String) = "kami_claims.research.cond.$name"
private fun listing(of: List<Condition>): Phrase = of.map { it.describe() }.reduceOrNull { a, b -> Phrase.of(key("join"), a, b) } ?: Phrase.literal("")

private fun qualify(tree: String, id: String) = if (id.contains(':')) id else "$tree:$id"

@Serializable
@SerialName("node")
class NodeDone(val id: String, val hidden: Boolean = false) : Condition {
    override fun met(country: Country) = id in country.research.done
    override fun describe() = Phrase.of(key("node"), Research.defs.node(id)?.label() ?: Phrase.literal(id))
    override fun refs() = listOf(id)
    override fun hardRefs() = listOf(id)
    override fun hiddenRefs() = if (hidden) listOf(id) else emptyList()
    override fun qualified(tree: String) = NodeDone(qualify(tree, id), hidden)
}

@Serializable
@SerialName("all")
class AllOf(val of: List<Condition>) : Condition {
    override fun met(country: Country) = of.all { it.met(country) }
    override fun describe() = Phrase.of(key("all"), listing(of))
    override fun refs() = of.flatMap { it.refs() }
    override fun hardRefs() = of.flatMap { it.hardRefs() }
    override fun hiddenRefs() = of.flatMap { it.hiddenRefs() }
    override fun qualified(tree: String) = AllOf(of.map { it.qualified(tree) })
}

@Serializable
@SerialName("any")
class AnyOf(val of: List<Condition>) : Condition {
    override fun met(country: Country) = of.any { it.met(country) }
    override fun describe() = Phrase.of(key("any"), listing(of))
    override fun refs() = of.flatMap { it.refs() }
    override fun qualified(tree: String) = AnyOf(of.map { it.qualified(tree) })
}

@Serializable
@SerialName("not")
class Not(val of: Condition) : Condition {
    override fun met(country: Country) = !of.met(country)
    override fun describe() = Phrase.of(key("not"), of.describe())
    override fun refs() = of.refs()
    override fun qualified(tree: String) = Not(of.qualified(tree))
}

@Serializable
@SerialName("level")
class MinLevel(val min: Int) : Condition {
    override fun met(country: Country) = Research.level(country) >= min
    override fun describe() = Phrase.of(key("level"), Phrase.value(min))
}

@Serializable
@SerialName("citizens")
class MinCitizens(val min: Int) : Condition {
    override fun met(country: Country) = country.members.size >= min
    override fun describe() = Phrase.of(key("citizens"), Phrase.value(min))
}

@Serializable
@SerialName("chunks")
class MinChunks(val min: Int, val chunkType: String? = null) : Condition {
    override fun met(country: Country) = Realm.claims(country.id).count { chunkType == null || it.type == chunkType } >= min
    override fun describe() =
        if (chunkType == null) Phrase.of(key("chunks"), Phrase.value(min)) else Phrase.of(key("chunks_type"), Phrase.value(min), Phrase.literal(chunkType))
}

@Serializable
@SerialName("treasury")
class MinTreasury(val min: Long) : Condition {
    override fun met(country: Country) = country.treasury >= min
    override fun describe() = Phrase.of(key("treasury"), Phrase.money(min))
}

@Serializable
@SerialName("province")
object IsProvince : Condition {
    override fun met(country: Country) = country.parent != null
    override fun describe() = Phrase.of(key("province"))
}

@Serializable
@SerialName("not_province")
object NotProvince : Condition {
    override fun met(country: Country) = country.parent == null
    override fun describe() = Phrase.of(key("not_province"))
}

@Serializable
@SerialName("provinces")
class MinProvinces(val min: Int) : Condition {
    override fun met(country: Country) = country.provinces.size >= min
    override fun describe() = Phrase.of(key("provinces"), Phrase.value(min))
}

@Serializable
@SerialName("age")
class MinAge(val days: Int) : Condition {
    override fun met(country: Country) = (now() - country.created) / Config.s.dayMillis >= days
    override fun describe() = Phrase.of(key("age"), Phrase.value(days))
}

@Serializable
@SerialName("allies")
class MinAllies(val min: Int) : Condition {
    override fun met(country: Country) = country.alliances.size >= min
    override fun describe() = Phrase.of(key("allies"), Phrase.value(min))
}

@Serializable
@SerialName("researched")
class MinResearched(val min: Int, val category: String? = null) : Condition {
    override fun met(country: Country) =
        country.research.done.keys.count { category == null || Research.defs.node(it)?.category == category } >= min

    override fun describe() =
        if (category == null) Phrase.of(key("researched"), Phrase.value(min)) else Phrase.of(key("researched_category"), Phrase.value(min), Phrase.literal(category))
}

@Serializable
@SerialName("counter")
class MinCounter(val key: String, val min: Long) : Condition {
    override fun met(country: Country) = (country.counters[key] ?: 0) >= min
    override fun describe() = Phrase.of(key("counter"), Phrase.or("kami_claims.counter.$key", key), Phrase.value(min))
}

@Serializable
@SerialName("plots")
class MinPlots(val min: Int, val chunkType: String = "residential") : Condition {
    override fun met(country: Country) = Realm.claims(country.id).count { it.type == chunkType && it.owner != null } >= min
    override fun describe() = Phrase.of(key("plots"), Phrase.value(min), Phrase.literal(chunkType))
}

@Serializable
@SerialName("flag")
object FlagSet : Condition {
    override fun met(country: Country) = country.flag != Flag()
    override fun describe() = Phrase.of(key("flag"))
}
