package kami.geology.map

import java.awt.Color

object MapColors {
    private val ores = mapOf(
        "coal" to 0x8A8F98, "iron" to 0xE0A070, "copper" to 0xF07F3C, "gold" to 0xFFD23F, "redstone" to 0xF03030,
        "lapis" to 0x3A63F0, "emerald" to 0x2EE06B, "tin" to 0xC5E3F0, "lithium" to 0xC58BFF, "lead" to 0x7B7FC4,
        "uranium" to 0x8CFF1A, "nickel" to 0x4FD1B5, "diamond" to 0x4DF0FF, "zinc" to 0xB9D96C, "bauxite" to 0xD96C6C,
        "galena" to 0x5F6B8C, "lignite" to 0x8B5A2B, "fireclay" to 0xE3B778
    )
    private val provinces = mapOf(
        "frozen" to 0x8FD3F4, "arid" to 0xE8C170, "wetland" to 0x4FA3A5, "highlands" to 0x9C8F86,
        "boreal" to 0x3F7D4E, "plains" to 0xB5D66B
    )

    fun ore(id: String): Int = ores[id] ?: hashed(id)

    fun province(name: String): Int = provinces[name] ?: hashed(name)

    private fun hashed(id: String): Int {
        val hue = (id.hashCode() and 0x7FFFFFFF) % 360 / 360f
        return Color.HSBtoRGB(hue, 0.65f, 0.95f) and 0xFFFFFF
    }
}
