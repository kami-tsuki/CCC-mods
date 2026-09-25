package kami.libs.mc

import kami.libs.log.Log
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.resources.ResourceLocation
import net.minecraft.world.level.block.Block
import net.minecraft.world.item.Item

class MissingMod(msg: String) : Exception(msg)

class Registry(private val log: Log, private val loaded: (String) -> Boolean = { true }) {
    fun findBlock(id: String): Block? = ResourceLocation.tryParse(id)?.let { BuiltInRegistries.BLOCK.getOptional(it).orElse(null) }

    fun findItem(id: String): Item? = ResourceLocation.tryParse(id)?.let { BuiltInRegistries.ITEM.getOptional(it).orElse(null) }

    fun requireBlock(id: String, context: String): Block = findBlock(id)
        ?: throw if (loaded(id)) IllegalArgumentException("unknown block '$id' in $context") else MissingMod("'$id' needs a mod that is not installed")

    fun missing(id: String, context: String) {
        if (loaded(id)) log.warn("Unknown '{}' in {}", id, context) else log.debug("Skipping '{}' in {}: mod not installed", id, context)
    }

    fun name(block: Block): String = BuiltInRegistries.BLOCK.getKey(block).toString()
}
