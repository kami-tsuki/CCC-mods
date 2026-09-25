package kami.geology.command

import kami.geology.map.MapColors
import kami.geology.world.Site
import kami.libs.chat.Chat
import kami.libs.chat.Msg
import net.minecraft.core.BlockPos
import kotlin.math.hypot
import kotlin.math.roundToInt

object GeoText {
    val chat = Chat.of("geology")

    fun name(id: String) = id.split('_').joinToString(" ") { it.replaceFirstChar(Char::uppercase) }

    fun Msg.ore(id: String) = text(name(id), MapColors.ore(id))

    fun distance(origin: BlockPos, site: Site) = hypot(site.x - origin.x, site.z - origin.z)

    fun Msg.site(site: Site, origin: BlockPos, dim: String) = apply {
        ore(site.ore.id)
        muted(" ${site.tier.name} · ${site.grade.name}${if (site.hasCore) " · core" else ""} · ${site.length}×${site.width}×${site.thickness}  ")
        pos(site.x.toInt(), site.y.toInt(), site.z.toInt(), dim)
        muted("  ${distance(origin, site).roundToInt()}m away")
    }
}
