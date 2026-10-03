package kami.essentials.op

import kami.essentials.chat.Talk
import kami.libs.chat.tell
import kami.libs.log.Log
import kami.libs.text.Phrase
import net.minecraft.commands.CommandSourceStack
import net.minecraft.server.level.ServerPlayer
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

object FakeDeop {
    private val active: MutableSet<UUID> = ConcurrentHashMap.newKeySet()

    @JvmStatic
    fun active(id: UUID?) = id != null && id in active

    fun allowed(source: CommandSourceStack) = source.player?.let { active(it.uuid) } == true || source.hasPermission(2)

    fun toggle(p: ServerPlayer) {
        val on = active.add(p.uuid) || !active.remove(p.uuid)
        p.server.playerList.sendPlayerPermissionLevel(p)
        Log.of("essentials").info("{} turned fake deop {}", p.gameProfile.name, if (on) "on" else "off")
        p.tell(Talk.chat.ok(Phrase.of(if (on) "kami_essentials.deopfake.on" else "kami_essentials.deopfake.off", Phrase.value("/deopfake"))))
    }

    fun forget(p: ServerPlayer) = active.remove(p.uuid)

    fun reset() = active.clear()
}
