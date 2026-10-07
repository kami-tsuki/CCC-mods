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
import net.neoforged.bus.api.EventPriority
import net.neoforged.neoforge.client.event.ClientTickEvent
import net.neoforged.neoforge.common.NeoForge
import java.util.function.Consumer

private typealias HolderType = RecipeType<RecipeHolder<*>>

private const val CHUNK = 500

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
    private var seenRev = -1
    private val pending = ArrayDeque<Pair<Entry, Boolean>>()
    private var stopListening: (() -> Unit)? = null
    private val onTick = Consumer<ClientTickEvent.Post> {
        if (dirty) refresh()
        drain()
    }

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
        pending.clear()
        dirty = true
        stopListening = ClientResearch.listen {
            if (ClientResearch.rev != seenRev) dirty = true
            if (ClientResearch.defs !== indexedDefs) index = null
        }
        NeoForge.EVENT_BUS.addListener(EventPriority.NORMAL, false, ClientTickEvent.Post::class.java, onTick)
    }

    override fun onRuntimeUnavailable() {
        NeoForge.EVENT_BUS.unregister(onTick)
        stopListening?.invoke()
        stopListening = null
        runtime = null
        index = null
        applied = emptySet()
        pending.clear()
    }

    private fun open(): List<NodeView> = ClientResearch.trees.ifEmpty { ClientResearch.defs.trees }
        .flatMap { it.nodes }
        .filter { ClientResearch.status(it.key) != NodeStatus.DONE }
        .sortedWith(compareBy({ ClientResearch.status(it.key) }, { it.level }, { it.timeMs }))

    private fun refresh() {
        val manager = runtime?.recipeManager ?: return
        dirty = false
        seenRev = ClientResearch.rev
        syncNodes(manager)
        val target = ClientResearch.hiddenRecipes()
        if (target === applied) return
        val diff = HiddenDiff.of(applied, target)
        applied = target
        if (diff.empty) return
        val entries = index ?: buildIndex(manager).also { index = it; indexedDefs = ClientResearch.defs }
        diff.hide.forEach { id -> entries[id]?.forEach { pending.addLast(it to true) } }
        diff.unhide.forEach { id -> entries[id]?.forEach { pending.addLast(it to false) } }
    }

    private fun drain() {
        val manager = runtime?.recipeManager ?: return
        if (pending.isEmpty()) return
        val batch = ArrayList<Pair<Entry, Boolean>>(CHUNK)
        while (batch.size < CHUNK && pending.isNotEmpty()) batch += pending.removeFirst()
        batch.groupBy { it.second to it.first.type }.forEach { (key, items) ->
            val holders = items.map { it.first.holder }
            if (key.first) manager.hideRecipes(key.second, holders) else manager.unhideRecipes(key.second, holders)
        }
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
