package kami.essentials.skills

object SkillClient {
    @Volatile private var values: Map<String, Double> = emptyMap()

    fun update(view: SkillView?) {
        values = view?.let { SkillTree.values(it.nodes) } ?: emptyMap()
    }

    fun value(key: String) = Effects.capped(key, values[key] ?: 0.0)
}
