package kami.claims.world

import kami.claims.*

import net.minecraft.core.particles.ParticleTypes
import kami.claims.social.Mail
import kami.libs.chat.tell
import net.minecraft.network.chat.Component
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.sounds.SoundEvents
import net.minecraft.sounds.SoundSource
import java.util.UUID

object Effects {
    private val watching = HashSet<UUID>()

    fun chime(p: ServerPlayer, good: Boolean) =
        p.playNotifySound(if (good) SoundEvents.NOTE_BLOCK_CHIME.value() else SoundEvents.ANVIL_LAND, SoundSource.PLAYERS, 0.8f, if (good) 1.2f else 0.7f)

    fun founded(p: ServerPlayer, c: Country) {
        p.playNotifySound(SoundEvents.UI_TOAST_CHALLENGE_COMPLETE, SoundSource.PLAYERS, 1f, 1f)
        (p.level() as? ServerLevel)?.sendParticles(ParticleTypes.FIREWORK, p.x, p.y + 1, p.z, 40, 0.5, 0.8, 0.5, 0.05)
        if (Config.s.broadcast) p.server.playerList.broadcastSystemMessage(Mail.chat.info("{${p.name.string}} founded the country {${c.name}}."), false)
    }

    fun invite(server: MinecraftServer, id: String, c: Country) {
        server.playerList.getPlayer(UUID.fromString(id))?.tell(Mail.chat.msg {
            markup("You are invited to {${c.name}}.  ")
            button("Accept", "/claims accept ${c.id}", "Join ${c.name}")
            text(" ")
            button("Info", "/claims info ${c.id}", "About ${c.name}")
        })
    }

    fun toggleBorders(p: ServerPlayer) = if (watching.add(p.uuid)) true else { watching.remove(p.uuid); false }

    fun forget(p: ServerPlayer) { watching.remove(p.uuid) }

    fun borders(p: ServerPlayer) {
        if (p.uuid !in watching) return
        val level = p.level() as? ServerLevel ?: return
        val dim = level.dimension().location().toString()
        val cx = p.chunkPosition().x
        val cz = p.chunkPosition().z
        fun owner(x: Int, z: Int) = Realm.index[Key(dim, x, z)]?.country
        for (x in cx - 1..cx + 1) for (z in cz - 1..cz + 1) {
            val o = owner(x, z)
            listOf(1 to 0, -1 to 0, 0 to 1, 0 to -1).forEach { (dx, dz) ->
                if (owner(x + dx, z + dz) == o) return@forEach
                val fixed = if (dx != 0) x * 16.0 + (if (dx > 0) 16 else 0) else z * 16.0 + (if (dz > 0) 16 else 0)
                for (i in 0..15 step 2) {
                    val px = if (dx != 0) fixed else x * 16.0 + i
                    val pz = if (dx != 0) z * 16.0 + i else fixed
                    level.sendParticles(p, if (o == null) ParticleTypes.SMOKE else ParticleTypes.END_ROD, false, px, p.y + 1.2, pz, 1, 0.0, 0.4, 0.0, 0.0)
                }
            }
        }
    }
}
