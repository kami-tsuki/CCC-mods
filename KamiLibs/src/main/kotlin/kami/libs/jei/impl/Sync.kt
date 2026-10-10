package kami.libs.jei.impl

import kami.libs.jei.JeiModule
import kami.libs.jei.KamiJei
import mezz.jei.api.recipe.IRecipeManager
import mezz.jei.api.recipe.RecipeType
import mezz.jei.api.runtime.IJeiRuntime
import net.minecraft.world.item.crafting.RecipeHolder
import net.neoforged.bus.api.EventPriority
import net.neoforged.neoforge.client.event.ClientTickEvent
import net.neoforged.neoforge.common.NeoForge
import java.util.function.Consumer

private const val CHUNK = 500

object Sync {
    private class State(val module: JeiModule) {
        val known = HashMap<RecipeType<*>, MutableSet<Any>>()
        val shown = HashMap<RecipeType<*>, Set<Any>>()
        var hidden = emptySet<String>()
    }

    private class Entry(val type: RecipeType<Any>, val holder: Any)

    private var runtime: IJeiRuntime? = null
    private var states = emptyList<State>()
    private var stops = emptyList<() -> Unit>()
    private var index: Map<String, List<Entry>>? = null
    private val pending = ArrayDeque<(IRecipeManager) -> Unit>()

    @Volatile
    private var dirty = false

    private val onTick = Consumer<ClientTickEvent.Post> {
        val rt = runtime ?: return@Consumer
        if (dirty) {
            dirty = false
            states.forEach { diff(it, rt) }
        }
        pending.removeFirstOrNull()?.invoke(rt.recipeManager)
    }

    fun start(rt: IJeiRuntime) {
        stop()
        runtime = rt
        index = null
        states = KamiJei.modules.map(::State)
        stops = states.map { s -> s.module.listen { dirty = true } }
        dirty = true
        NeoForge.EVENT_BUS.addListener(EventPriority.NORMAL, false, ClientTickEvent.Post::class.java, onTick)
    }

    fun stop() {
        if (runtime == null) return
        NeoForge.EVENT_BUS.unregister(onTick)
        stops.forEach { it() }
        stops = emptyList()
        states = emptyList()
        index = null
        pending.clear()
        runtime = null
    }

    private fun diff(s: State, rt: IJeiRuntime) {
        val current = s.module.recipes().mapValues { it.value.toSet() }
        for (type in current.keys + s.shown.keys) {
            val now = current[type].orEmpty()
            val visible = s.shown[type].orEmpty()
            val known = s.known.getOrPut(type) { LinkedHashSet() }
            val added = now - known
            queue(type, added) { m, t, items -> m.addRecipes(t, items) }
            queue(type, (now - visible) - added) { m, t, items -> m.unhideRecipes(t, items) }
            queue(type, visible - now) { m, t, items -> m.hideRecipes(t, items) }
            known.addAll(added)
            s.shown[type] = now
        }
        val hidden = s.module.hidden()
        if (hidden == s.hidden) return
        val lookup = index ?: buildIndex(rt).also { index = it }
        fun entries(ids: Set<String>) = ids.flatMap { lookup[it].orEmpty() }.groupBy({ it.type }, { it.holder })
        entries(hidden - s.hidden).forEach { (type, items) -> queue(type, items.toSet()) { m, t, l -> m.hideRecipes(t, l) } }
        entries(s.hidden - hidden).forEach { (type, items) -> queue(type, items.toSet()) { m, t, l -> m.unhideRecipes(t, l) } }
        s.hidden = hidden
    }

    @Suppress("UNCHECKED_CAST")
    private fun buildIndex(rt: IJeiRuntime): Map<String, List<Entry>> {
        val manager = rt.recipeManager
        return rt.jeiHelpers.allRecipeTypes.toList()
            .filter { RecipeHolder::class.java.isAssignableFrom(it.recipeClass) }
            .flatMap { type ->
                manager.createRecipeLookup(type as RecipeType<Any>).includeHidden().get().map { Entry(type, it) }.toList()
            }
            .groupBy { (it.holder as RecipeHolder<*>).id().toString() }
    }

    @Suppress("UNCHECKED_CAST")
    private fun queue(type: RecipeType<*>, items: Set<Any>, op: (IRecipeManager, RecipeType<Any>, List<Any>) -> Unit) {
        items.chunked(CHUNK).forEach { chunk -> pending.addLast { m -> op(m, type as RecipeType<Any>, chunk) } }
    }
}
