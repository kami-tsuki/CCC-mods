package kami.claims.client.compat.jei

import kami.claims.client.compat.HiddenDiff
import kami.claims.client.store.ClientResearch
import kami.claims.client.store.NodeStatus
import kami.claims.net.NodeView
import mezz.jei.api.IModPlugin
import mezz.jei.api.JeiPlugin
import mezz.jei.api.recipe.IRecipeManager
import mezz.jei.api.recipe.RecipeType
import mezz.jei.api.registration.IRecipeCategoryRegistration
import mezz.jei.api.registration.IRecipeRegistration
import mezz.jei.api.runtime.IJeiRuntime
import net.minecraft.resources.ResourceLocation
import net.minecraft.world.item.crafting.RecipeHolder
import net.neoforged.neoforge.client.event.ClientTickEvent
import net.neoforged.neoforge.common.NeoForge
import java.util.function.Consumer

private typealias HolderType = RecipeType<RecipeHolder<*>>

@JeiPlugin
class ResearchJei : IModPlugin {
    private class Entry(val type: HolderType, val holder: RecipeHolder<*>)

    private var runtime: IJeiRuntime? = null
    private var index: Map<String, List<Entry>>? = null
    private var indexedDefs: Any? = null
    private var applied = emptySet<String>()
    private val registered = LinkedHashSet<NodeView>()
    private var shown = emptySet<NodeView>()
    private var dirty = false
    private var stopListening: (() -> Unit)? = null
    private val onTick = Consumer<ClientTickEvent.Post> { if (dirty) refresh() }

    override fun getPluginUid(): ResourceLocation = ResourceLocation.fromNamespaceAndPath("kami_claims", "research")

    override fun registerCategories(registration: IRecipeCategoryRegistration) {
        registration.addRecipeCategories(ResearchCategory(registration.jeiHelpers.guiHelper))
    }

    override fun registerRecipes(registration: IRecipeRegistration) {
        registered.clear()
        registered += open()
        shown = registered.toSet()
        registration.addRecipes(ResearchCategory.TYPE, registered.toList())
    }

    override fun onRuntimeAvailable(jeiRuntime: IJeiRuntime) {
        runtime = jeiRuntime
        index = null
        applied = emptySet()
        dirty = true
        stopListening = ClientResearch.listen { dirty = true; if (ClientResearch.defs !== indexedDefs) index = null }
        NeoForge.EVENT_BUS.addListener(onTick)
    }

    override fun onRuntimeUnavailable() {
        NeoForge.EVENT_BUS.unregister(onTick)
        stopListening?.invoke()
        stopListening = null
        runtime = null
        index = null
        applied = emptySet()
    }

    private fun open(): List<NodeView> = ClientResearch.trees.ifEmpty { ClientResearch.defs.trees }
        .flatMap { it.nodes }
        .filter { ClientResearch.status(it.key) != NodeStatus.DONE }
        .sortedWith(compareBy({ ClientResearch.status(it.key) }, { it.level }, { it.timeMs }))

    private fun refresh() {
        val manager = runtime?.recipeManager ?: return
        dirty = false
        syncNodes(manager)
        val diff = HiddenDiff.of(applied, ClientResearch.hiddenRecipes())
        if (diff.empty) return
        val entries = index ?: buildIndex(manager).also { index = it; indexedDefs = ClientResearch.defs }
        toggle(manager, entries, diff.hide, hide = true)
        toggle(manager, entries, diff.unhide, hide = false)
        applied = ClientResearch.hiddenRecipes()
    }

    private fun syncNodes(manager: IRecipeManager) {
        val next = open()
        val wanted = next.toSet()
        val fresh = next.filter { it !in registered }
        if (fresh.isNotEmpty()) {
            manager.addRecipes(ResearchCategory.TYPE, fresh)
            registered += fresh
        }
        (shown - wanted).takeIf { it.isNotEmpty() }?.let { manager.hideRecipes(ResearchCategory.TYPE, it) }
        (wanted - shown - fresh.toSet()).takeIf { it.isNotEmpty() }?.let { manager.unhideRecipes(ResearchCategory.TYPE, it) }
        shown = wanted
    }

    private fun toggle(manager: IRecipeManager, entries: Map<String, List<Entry>>, ids: Set<String>, hide: Boolean) {
        ids.flatMap { entries[it].orEmpty() }
            .groupBy({ it.type }, { it.holder })
            .forEach { (type, holders) ->
                if (hide) manager.hideRecipes(type, holders) else manager.unhideRecipes(type, holders)
            }
    }

    @Suppress("UNCHECKED_CAST")
    private fun buildIndex(manager: IRecipeManager): Map<String, List<Entry>> =
        manager.createRecipeCategoryLookup().includeHidden().get().toList()
            .map { it.recipeType }
            .filter { RecipeHolder::class.java.isAssignableFrom(it.recipeClass) }
            .flatMap { type ->
                manager.createRecipeLookup(type as HolderType).includeHidden().get().map { Entry(type, it) }.toList()
            }
            .groupBy { it.holder.id().toString() }
}
