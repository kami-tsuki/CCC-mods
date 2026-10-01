package kami.geology.command

import kami.libs.ui.text.trOr
import kami.libs.chat.Theme
import kami.libs.text.Phrase
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

    fun orePhrase(id: String): Phrase = Phrase.or("kami_geology.ore.$id", name(id))
    fun tier(id: String) = Phrase.or("kami_geology.tier.$id", name(id))
    fun grade(id: String) = Phrase.or("kami_geology.grade.$id", name(id))
    fun oreLabel(id: String) = trOr("kami_geology.ore.$id", name(id))
    fun provinceLabel(id: String) = trOr("kami_geology.province.$id", name(id))

    fun Msg.ore(id: String): Msg = add(orePhrase(id), MapColors.ore(id))

    fun distance(origin: BlockPos, site: Site) = hypot(site.x - origin.x, site.z - origin.z)

    fun Msg.site(site: Site, origin: BlockPos, dim: String) = apply {
        ore(site.ore.id)
        muted(" ")
        add(tier(site.tier.name), Theme.MUTED)
        muted(" · ")
        add(grade(site.grade.name), Theme.MUTED)
        if (site.hasCore) { muted(" · "); add(Phrase.of("kami_geology.site.core"), Theme.MUTED) }
        muted(" · ${site.length}×${site.width}×${site.thickness}  ")
        pos(site.x.toInt(), site.y.toInt(), site.z.toInt(), dim)
        muted("  ")
        add(Phrase.of("kami_libs.common.distance", distance(origin, site).roundToInt()), Theme.MUTED)
    }
}
