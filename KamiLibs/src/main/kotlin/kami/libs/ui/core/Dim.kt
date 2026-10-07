package kami.libs.ui.core

import net.minecraft.client.Minecraft
import net.minecraft.resources.ResourceKey
import net.minecraft.world.level.Level

object Dim {
    private var key: ResourceKey<Level>? = null
    private var name = ""

    fun current(): String {
        val k = Minecraft.getInstance().level?.dimension() ?: return ""
        if (k !== key) { key = k; name = k.location().toString() }
        return name
    }
}
