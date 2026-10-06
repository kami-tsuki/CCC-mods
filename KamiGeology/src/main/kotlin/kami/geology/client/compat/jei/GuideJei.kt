package kami.geology.client.compat.jei

import kami.geology.client.Guide
import kami.geology.net.GuideOre
import mezz.jei.api.IModPlugin
import mezz.jei.api.JeiPlugin
import mezz.jei.api.registration.IRecipeCategoryRegistration
import mezz.jei.api.runtime.IJeiRuntime
import net.minecraft.resources.ResourceLocation
import net.neoforged.neoforge.client.event.ClientTickEvent
import net.neoforged.neoforge.common.NeoForge
import java.util.function.Consumer

@JeiPlugin
class GuideJei : IModPlugin {
    private var runtime: IJeiRuntime? = null
    private var shown = emptyList<GuideOre>()
    private var dirty = false
    private var stopListening: (() -> Unit)? = null
    private val onTick = Consumer<ClientTickEvent.Post> { if (dirty) refresh() }

    override fun getPluginUid(): ResourceLocation = ResourceLocation.fromNamespaceAndPath("kami_geology", "guide")

    override fun registerCategories(registration: IRecipeCategoryRegistration) {
        registration.addRecipeCategories(GuideCategory(registration.jeiHelpers.guiHelper))
    }

    override fun onRuntimeAvailable(jeiRuntime: IJeiRuntime) {
        runtime = jeiRuntime
        shown = emptyList()
        dirty = true
        stopListening = Guide.listen { dirty = true }
        NeoForge.EVENT_BUS.addListener(onTick)
    }

    override fun onRuntimeUnavailable() {
        NeoForge.EVENT_BUS.unregister(onTick)
        stopListening?.invoke()
        stopListening = null
        runtime = null
        shown = emptyList()
    }

    private fun refresh() {
        val manager = runtime?.recipeManager ?: return
        dirty = false
        if (shown.isNotEmpty()) manager.hideRecipes(GuideCategory.TYPE, shown)
        shown = Guide.data.ores
        if (shown.isNotEmpty()) manager.addRecipes(GuideCategory.TYPE, shown)
    }
}
