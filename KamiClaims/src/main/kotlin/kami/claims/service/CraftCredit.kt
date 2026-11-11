package kami.claims.service

import kami.claims.gov.TaskKind
import kami.libs.mc.ItemSpec
import net.minecraft.server.level.ServerPlayer
import net.neoforged.bus.api.EventPriority
import net.neoforged.neoforge.event.entity.living.BabyEntitySpawnEvent
import net.neoforged.neoforge.event.entity.player.ItemFishedEvent
import net.neoforged.neoforge.event.entity.player.PlayerEvent
import thedarkcolour.kotlinforforge.neoforge.forge.FORGE_BUS

object CraftCredit {
    fun register() {
        FORGE_BUS.addListener<PlayerEvent.ItemCraftedEvent> { (it.entity as? ServerPlayer)?.let { p -> Credit.made(p, TaskKind.CRAFT, ItemSpec.id(it.crafting), it.crafting.count) } }
        FORGE_BUS.addListener<PlayerEvent.ItemSmeltedEvent> { (it.entity as? ServerPlayer)?.let { p -> Credit.made(p, TaskKind.SMELT, ItemSpec.id(it.smelting), it.smelting.count) } }
        FORGE_BUS.addListener(EventPriority.LOWEST, false, BabyEntitySpawnEvent::class.java) { (it.causedByPlayer as? ServerPlayer)?.let { p -> Credit.acted(p, TaskKind.HERD, 1) } }
        FORGE_BUS.addListener<ItemFishedEvent> { (it.entity as? ServerPlayer)?.let { p -> Credit.acted(p, TaskKind.FISH, it.drops.size) } }
    }
}
