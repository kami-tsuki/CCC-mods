package kami.essentials.inv

import com.mojang.authlib.GameProfile
import kami.essentials.chat.Talk
import kami.libs.chat.tell
import kami.libs.command.fail
import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.Container
import net.minecraft.world.SimpleMenuProvider

object Views {
    private val INVENTORY = ((9..35) + (0..8) + listOf(39, 38, 37, 36, -1, 40, -1, -1, -1)).toIntArray()
    private val ENDERCHEST = (0..26).toList().toIntArray()

    fun inventory(viewer: ServerPlayer, target: GameProfile, edit: Boolean) =
        open(viewer, target, edit, "inventory", INVENTORY, { it.inventory }, { it.inventory })

    fun enderchest(viewer: ServerPlayer, target: GameProfile, edit: Boolean) =
        open(viewer, target, edit, "ender chest", ENDERCHEST, { it.enderChestInventory }, { it.enderchest })

    private fun open(
        viewer: ServerPlayer, target: GameProfile, edit: Boolean, what: String, layout: IntArray,
        online: (ServerPlayer) -> Container, offline: (Offline) -> Container,
    ) {
        val live = viewer.server.playerList.getPlayer(target.id)
        val data = if (live == null) Offline.of(viewer.server, target.id) ?: fail("{${target.name}} has never played here.") else null
        val source = if (live != null) online(live) else offline(data!!)
        val valid: () -> Boolean = if (live != null) { { !live.hasDisconnected() } } else { { data!!.live } }
        val notes = listOfNotNull("offline".takeIf { data != null }, "read only".takeUnless { edit })
        val title = Component.literal((if (live === viewer) "Your $what" else "${target.name}'s $what") + if (notes.isEmpty()) "" else " (${notes.joinToString()})")
        data?.viewers?.add(viewer)
        viewer.openMenu(SimpleMenuProvider({ id, inv, _ -> ViewMenu(id, inv, source, layout, edit, valid) { data?.let { Offline.release(it, viewer) } } }, title))
        if (live !== viewer) viewer.tell(Talk.chat.info("Opened {${target.name}}'s $what${if (notes.isEmpty()) "" else ", ${notes.joinToString()}"}."))
    }
}
