package kami.libs.create

import com.simibubi.create.api.behaviour.display.DisplaySource
import com.simibubi.create.api.registry.CreateRegistries
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.resources.ResourceLocation
import net.neoforged.bus.api.IEventBus
import net.neoforged.neoforge.registries.DeferredHolder
import net.neoforged.neoforge.registries.DeferredRegister
import java.util.function.Supplier

class KamiDisplay(modId: String) {
    private val sources = DeferredRegister.create(CreateRegistries.DISPLAY_SOURCE, modId)

    fun <S : DisplaySource> source(id: String, factory: () -> S): DeferredHolder<DisplaySource, S> = sources.register(id, Supplier { factory() })

    fun register(bus: IEventBus) = sources.register(bus)

    fun bind(source: Supplier<out DisplaySource>, vararg blockEntities: ResourceLocation) {
        blockEntities.forEach { id -> BuiltInRegistries.BLOCK_ENTITY_TYPE.getOptional(id).ifPresent { DisplaySource.BY_BLOCK_ENTITY.add(it, source.get()) } }
    }
}
