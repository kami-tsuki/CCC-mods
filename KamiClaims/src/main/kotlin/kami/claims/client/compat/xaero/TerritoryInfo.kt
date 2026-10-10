package kami.claims.client.compat.xaero

import kami.claims.service.View
import kami.libs.xaero.InfoLine
import net.minecraft.network.chat.Component

class TerritoryInfo(private val data: ClaimData, private val style: ClaimStyle, private val allowed: (String, Int, Int) -> Boolean) : InfoLine {
    override fun lines(dim: String, x: Int, y: Int, z: Int): List<Component> {
        if (!data.enabled) return emptyList()
        val raw = data.at(dim, x shr 4, z shr 4) ?: return emptyList()
        if (raw.flags and View.ENEMY != 0) return listOf(Component.translatable("kami_claims.xaero.war.enemy").withColor(WAR_RED))
        val e = raw.takeIf { shown(it) && !foreign(it) } ?: return emptyList()
        val name = (data.country(e)?.name ?: "?") + (data.typeName(e)?.let { " - " + style.typeLabel(it) } ?: "")
        val out = arrayListOf<Component>(Component.translatable("kami_claims.xaero.territory", name))
        out += if (allowed(dim, x, z)) Component.translatable("kami_claims.xaero.build.yes").withColor(0x55FF55) else Component.translatable("kami_claims.xaero.build.no").withColor(0xFF5555)
        when {
            e.flags and View.STAKED != 0 -> out += Component.translatable("kami_claims.xaero.war.staked").withColor(WAR_ORANGE)
            e.flags and View.SAFE != 0 -> out += Component.translatable("kami_claims.xaero.war.safe").withColor(SAFE_GREEN)
        }
        return out
    }
}
