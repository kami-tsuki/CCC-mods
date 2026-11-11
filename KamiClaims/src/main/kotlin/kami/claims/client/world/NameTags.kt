package kami.claims.client.world

import kami.claims.mixin.client.EntityRendererAccess
import kami.claims.net.RowKind
import kami.claims.net.TagRow
import kami.claims.net.TagRows
import kami.claims.net.TagView
import net.minecraft.client.Minecraft
import net.minecraft.client.player.AbstractClientPlayer
import net.minecraft.client.gui.Font
import net.minecraft.network.chat.Component
import net.minecraft.world.entity.EntityAttachment
import net.minecraft.world.scores.DisplaySlot
import net.neoforged.neoforge.client.event.RenderNameTagEvent
import net.neoforged.neoforge.common.util.TriState
import java.util.UUID

object NameTags {
    private const val LINE = 9f * 1.15f

    private class Rows(val rows: List<Component>) {
        var widths = IntArray(0)
        var at = 0L
    }

    @Volatile private var tags: Map<UUID, Rows> = emptyMap()

    fun receive(t: Map<UUID, TagView>) {
        tags = t.mapValues { (_, v) -> Rows(TagRows.rows(v).map(::component)) }
    }

    fun clear() {
        tags = emptyMap()
    }

    fun render(e: RenderNameTagEvent) {
        val p = e.entity as? AbstractClientPlayer ?: return
        val entry = tags[p.uuid]?.takeIf { it.rows.isNotEmpty() } ?: return
        val shown = if (e.canRender().isDefault) (e.entityRenderer as? EntityRendererAccess)?.`kami$shouldShowName`(p) == true else e.canRender().isTrue
        if (!shown || p.scoreboard.getDisplayObjective(DisplaySlot.BELOW_NAME) != null) return
        e.setCanRender(TriState.FALSE)
        val mc = Minecraft.getInstance()
        val dispatcher = mc.entityRenderDispatcher
        if (!net.neoforged.neoforge.client.ClientHooks.isNameplateInRenderDistance(p, dispatcher.distanceToSqr(p))) return
        val at = p.attachments.getNullable(EntityAttachment.NAME_TAG, 0, p.getViewYRot(e.partialTick)) ?: return
        val rows = entry.rows
        val font = mc.font
        val now = System.currentTimeMillis()
        if (entry.widths.size != rows.size || now - entry.at > 2000) {
            entry.widths = IntArray(rows.size) { font.width(rows[it]) }
            entry.at = now
        }
        val pose = e.poseStack
        val bg = (mc.options.getBackgroundOpacity(0.25f) * 255f).toInt() shl 24
        val see = !p.isDiscrete
        pose.pushPose()
        pose.translate(at.x, at.y + 0.5, at.z)
        pose.mulPose(dispatcher.cameraOrientation())
        pose.scale(0.025f, -0.025f, 0.025f)
        val matrix = pose.last().pose()
        val mode = if (see) Font.DisplayMode.SEE_THROUGH else Font.DisplayMode.NORMAL
        val head = e.content
        val headX = (-font.width(head) / 2).toFloat()
        val headY = -rows.size * LINE
        font.drawInBatch(head, headX, headY, 0x20FFFFFF, false, matrix, e.multiBufferSource, mode, bg, e.packedLight)
        if (see) font.drawInBatch(head, headX, headY, -1, false, matrix, e.multiBufferSource, Font.DisplayMode.NORMAL, 0, e.packedLight)
        for (i in rows.indices) {
            val text = rows[i]
            val x = (-entry.widths[i] / 2).toFloat()
            val y = -(rows.size - 1 - i) * LINE
            font.drawInBatch(text, x, y, 0x20FFFFFF, false, matrix, e.multiBufferSource, mode, bg, e.packedLight)
            if (see) font.drawInBatch(text, x, y, -1, false, matrix, e.multiBufferSource, Font.DisplayMode.NORMAL, 0, e.packedLight)
        }
        pose.popPose()
    }

    private fun component(r: TagRow): Component = when (r.kind) {
        RowKind.NOMAD -> Component.translatable("kami_claims.nametag.nomad")
        RowKind.RANK, RowKind.COUNTRY -> Component.literal(r.text)
        RowKind.CLASS -> Component.translatable("kami_claims.nametag.skill", Component.translatableWithFallback("kami_libs.skill.class.${r.text}", r.text.replaceFirstChar(Char::titlecase)), r.level)
        RowKind.LEVEL -> Component.translatable("kami_claims.nametag.level", r.level)
    }.withColor(r.color)
}
