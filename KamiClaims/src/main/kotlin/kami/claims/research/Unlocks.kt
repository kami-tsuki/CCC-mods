package kami.claims.research

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
sealed interface Unlock

@Serializable
@SerialName("group")
class GroupRef(val id: String) : Unlock

@Serializable
@SerialName("recipe")
class RecipeUnlock(val id: String) : Unlock

@Serializable
@SerialName("recipe_type")
class RecipeTypeUnlock(val id: String) : Unlock

@Serializable
@SerialName("recipes")
class RecipesUnlock(val recipeType: String? = null, val input: String? = null, val output: String? = null) : Unlock

@Serializable
@SerialName("output")
class OutputUnlock(val id: String) : Unlock

@Serializable
@SerialName("mod")
class ModUnlock(val id: String) : Unlock

@Serializable
@SerialName("block")
class BlockUnlock(val id: String) : Unlock

@Serializable
@SerialName("capacity")
class CapacityUnlock(val key: Capacity, val add: Int) : Unlock

@Serializable
@SerialName("feature")
class FeatureUnlock(val id: String) : Unlock

@Serializable
enum class BuffMode {
    @SerialName("aura") AURA,
    @SerialName("pulse") PULSE
}

@Serializable
@SerialName("buff")
class BuffUnlock(val effect: String, val amplifier: Int = 0, val mode: BuffMode = BuffMode.AURA, val cooldownSeconds: Int = 300) : Unlock

@Serializable
@SerialName("buff_points")
class BuffPointsUnlock(val add: Int) : Unlock

@Serializable
@SerialName("loan")
class LoanUnlock(val id: String, val amount: Long, val interestPct: Int, val termDays: Int) : Unlock

@Serializable
@SerialName("loan_slots")
class LoanSlotsUnlock(val add: Int) : Unlock

@Serializable
@SerialName("token")
class TokenUnlock(val id: String, val count: Int = 1) : Unlock

@Serializable
@SerialName("money")
class MoneyReward(val amount: Long) : Unlock

@Serializable
class Group(val title: String = "", val unlocks: List<Unlock> = emptyList())
