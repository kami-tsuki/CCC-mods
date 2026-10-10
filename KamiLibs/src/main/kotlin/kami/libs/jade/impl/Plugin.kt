package kami.libs.jade.impl

import kami.libs.jade.EntityLook
import kami.libs.jade.KamiJade
import kami.libs.jade.Look
import net.minecraft.nbt.CompoundTag
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.level.block.Block
import snownee.jade.api.BlockAccessor
import snownee.jade.api.EntityAccessor
import snownee.jade.api.IBlockComponentProvider
import snownee.jade.api.IEntityComponentProvider
import snownee.jade.api.IServerDataProvider
import snownee.jade.api.ITooltip
import snownee.jade.api.IWailaClientRegistration
import snownee.jade.api.IWailaCommonRegistration
import snownee.jade.api.IWailaPlugin
import snownee.jade.api.TooltipPosition
import snownee.jade.api.WailaPlugin
import snownee.jade.api.config.IPluginConfig

@WailaPlugin
class KamiJadePlugin : IWailaPlugin {
    override fun register(registration: IWailaCommonRegistration) {
        KamiJade.blockData.forEach { d ->
            registration.registerBlockDataProvider(object : IServerDataProvider<BlockAccessor> {
                override fun getUid() = d.id
                override fun shouldRequestData(accessor: BlockAccessor) =
                    d.wants(Look(accessor.level, accessor.position, accessor.blockState, accessor.player, CompoundTag(), false))
                override fun appendServerData(tag: CompoundTag, accessor: BlockAccessor) {
                    val player = accessor.player as? ServerPlayer ?: return
                    d.data(player, accessor.position)?.let { tag.put(d.id.toString(), it) }
                }
            }, Block::class.java)
        }
        KamiJade.entityData.forEach { d ->
            registration.registerEntityDataProvider(object : IServerDataProvider<EntityAccessor> {
                override fun getUid() = d.id
                override fun shouldRequestData(accessor: EntityAccessor): Boolean {
                    val entity = accessor.entity ?: return false
                    return d.wants(EntityLook(entity, accessor.player, CompoundTag(), false))
                }
                override fun appendServerData(tag: CompoundTag, accessor: EntityAccessor) {
                    val player = accessor.player as? ServerPlayer ?: return
                    d.data(player, accessor.entity ?: return)?.let { tag.put(d.id.toString(), it) }
                }
            }, d.type!!)
        }
    }

    override fun registerClient(registration: IWailaClientRegistration) {
        KamiJade.blocks.forEach { b ->
            registration.registerBlockComponent(object : IBlockComponentProvider {
                override fun getUid(): ResourceLocation = b.id
                override fun getDefaultPriority() = TooltipPosition.TAIL
                override fun appendTooltip(tooltip: ITooltip, accessor: BlockAccessor, config: IPluginConfig) {
                    val look = Look(accessor.level, accessor.position, accessor.blockState, accessor.player, accessor.serverData, accessor.showDetails())
                    b.lines(look).forEach { tooltip.add(it) }
                }
            }, Block::class.java)
        }
        KamiJade.entities.forEach { e ->
            registration.registerEntityComponent(object : IEntityComponentProvider {
                override fun getUid(): ResourceLocation = e.id
                override fun getDefaultPriority() = TooltipPosition.TAIL
                override fun appendTooltip(tooltip: ITooltip, accessor: EntityAccessor, config: IPluginConfig) {
                    val look = EntityLook(accessor.entity ?: return, accessor.player, accessor.serverData, accessor.showDetails())
                    e.lines(look).forEach { tooltip.add(it) }
                }
            }, e.type!!)
        }
    }
}
