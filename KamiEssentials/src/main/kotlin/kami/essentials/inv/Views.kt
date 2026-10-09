package kami.essentials.inv

import com.mojang.authlib.GameProfile
import kami.essentials.chat.Talk
import kami.essentials.vanish.Vanish
import kami.libs.admin.AdminPerms
import kami.libs.chat.tell
import kami.libs.command.fail
import kami.libs.text.Phrase
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.Container
import net.minecraft.world.SimpleMenuProvider

object Views {
    private val INVENTORY = ((9..35) + (0..8) + listOf(39, 38, 37, 36, -1, 40, -1, -1, -1)).toIntArray()
    private val ENDERCHEST = (0..26).toList().toIntArray()

    fun inventory(viewer: ServerPlayer, target: GameProfile, edit: Boolean) =
        open(viewer, target, edit, "inventory", INVENTORY, { it.inventory }, { it.inventory })

    fun enderchest(viewer: ServerPlayer, target: GameProfile, edit: Boolean) =
        open(viewer, target, edit, "ender_chest", ENDERCHEST, { it.enderChestInventory }, { it.enderchest })

    private fun open(
        viewer: ServerPlayer, target: GameProfile, edit: Boolean, what: String, layout: IntArray,
        online: (ServerPlayer) -> Container, offline: (Offline) -> Container,
    ) {
        val live = viewer.server.playerList.getPlayer(target.id)
        if (live != null && Vanish.hides(live, viewer)) fail(Phrase.of("kami_essentials.views.never_played", Phrase.value(target.name)))
        if (edit && target.id != viewer.uuid && !AdminPerms.outranks(viewer, target)) fail(Phrase.of("kami_essentials.admin.outranked"))
        val data = if (live == null) Offline.of(viewer.server, target.id) ?: fail(Phrase.of("kami_essentials.views.never_played", Phrase.value(target.name))) else null
        val source = if (live != null) online(live) else offline(data!!)
        val valid: () -> Boolean = if (live != null) { { !live.hasDisconnected() && !live.isRemoved } } else { { data!!.live } }
        val notes = listOfNotNull("kami_essentials.views.offline".takeIf { data != null }, "kami_essentials.views.read_only".takeUnless { edit }).map { Phrase.of(it) }
        val base = if (live === viewer) Phrase.of("kami_essentials.views.$what.own") else Phrase.of("kami_essentials.views.$what.other", target.name)
        val label = notes.fold(base) { out, note -> Phrase.of("kami_libs.format.paren", out, note) }
        val title = label.component()
        data?.viewers?.add(viewer)
        val opened = viewer.openMenu(SimpleMenuProvider({ id, inv, _ -> ViewMenu(id, inv, source, layout, edit, valid) { data?.let { Offline.release(it, viewer) } } }, title))
        if (opened.isEmpty) return data?.let { Offline.release(it, viewer) } ?: Unit
        if (live !== viewer) viewer.tell(Talk.chat.info(Phrase.of("kami_essentials.views.opened", label.asValue())))
    }
}
