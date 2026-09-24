package kami.geology

import com.mojang.logging.LogUtils
import kami.geology.command.GeologyCommand
import kami.geology.config.ConfigStore
import kami.geology.item.ProspectorItem
import kami.geology.loot.RichnessModifier
import kami.geology.map.Heatmap
import kami.geology.map.Workers
import kami.geology.net.MapServer
import kami.geology.world.GeologyBiomeModifier
import kami.geology.world.GeologyFeature
import kami.geology.world.Worlds
import kami.libs.geology.GeologyApi
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.item.CreativeModeTabs
import net.minecraft.world.item.Item
import net.minecraft.world.item.ItemStack
import net.neoforged.fml.common.Mod
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent
import net.neoforged.neoforge.event.BuildCreativeModeTabContentsEvent
import net.neoforged.neoforge.event.RegisterCommandsEvent
import net.neoforged.neoforge.event.TagsUpdatedEvent
import net.neoforged.neoforge.event.entity.player.PlayerEvent
import net.neoforged.neoforge.event.server.ServerStoppedEvent
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent
import net.neoforged.neoforge.registries.DeferredHolder
import net.neoforged.neoforge.registries.DeferredRegister
import net.neoforged.neoforge.registries.NeoForgeRegistries
import org.slf4j.Logger
import thedarkcolour.kotlinforforge.neoforge.forge.FORGE_BUS
import thedarkcolour.kotlinforforge.neoforge.forge.MOD_BUS

@Mod(KamiGeology.ID)
object KamiGeology {
    const val ID = "kami_geology"
    private const val STARTER_KIT_TAG = "kami_geology_starter_prospector"
    val LOG: Logger = LogUtils.getLogger()

    private val features = DeferredRegister.create(BuiltInRegistries.FEATURE, ID)
    private val biomeModifiers = DeferredRegister.create(NeoForgeRegistries.Keys.BIOME_MODIFIER_SERIALIZERS, ID)
    private val lootModifiers = DeferredRegister.create(NeoForgeRegistries.Keys.GLOBAL_LOOT_MODIFIER_SERIALIZERS, ID)
    private val items = DeferredRegister.create(BuiltInRegistries.ITEM, ID)

    /** Index 0 = tier 1 (free, 1 block) ... index 7 = tier 8 (netherite-diamond, 9x9 chunks). */
    val PROSPECTORS: List<DeferredHolder<Item, ProspectorItem>> = (1..8).map { tier ->
        items.register("prospector_t$tier") { ProspectorItem(tier, Item.Properties().stacksTo(1)) }
    }

    init {
        features.register("geology") { -> GeologyFeature() }
        biomeModifiers.register("geology") { -> GeologyBiomeModifier.CODEC }
        lootModifiers.register("richness") { -> RichnessModifier.CODEC }
        listOf(features, biomeModifiers, lootModifiers, items).forEach { it.register(MOD_BUS) }

        MOD_BUS.addListener<RegisterPayloadHandlersEvent> { MapServer.register(it) }
        MOD_BUS.addListener<FMLCommonSetupEvent> {
            ConfigStore.load()
            GeologyApi.register { level, x, z, y0, y1, callback ->
                val world = Worlds.of(level)
                if (world == null) callback(emptyList()) else Workers.pool.execute {
                    callback(runCatching { Heatmap.probe(world, x, z, y0, y1) }.getOrElse { emptyList() })
                }
            }
        }
        MOD_BUS.addListener<BuildCreativeModeTabContentsEvent> { event ->
            if (event.tabKey == CreativeModeTabs.TOOLS_AND_UTILITIES) PROSPECTORS.forEach { event.accept(it) }
        }
        FORGE_BUS.addListener<RegisterCommandsEvent> { GeologyCommand.register(it.dispatcher) }
        FORGE_BUS.addListener<TagsUpdatedEvent> { if (it.updateCause == TagsUpdatedEvent.UpdateCause.SERVER_DATA_LOAD) ConfigStore.load() }
        FORGE_BUS.addListener<ServerStoppedEvent> { Worlds.clear() }
        FORGE_BUS.addListener<PlayerEvent.PlayerLoggedInEvent> { event ->
            val player = event.entity as? ServerPlayer ?: return@addListener
            val tag = player.persistentData
            if (!tag.getBoolean(STARTER_KIT_TAG)) {
                tag.putBoolean(STARTER_KIT_TAG, true)
                player.addItem(ItemStack(PROSPECTORS[0].get()))
            }
        }
    }
}
