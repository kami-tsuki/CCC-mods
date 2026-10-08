package kami.libs.db

import kami.libs.admin.AdminPerms
import kami.libs.chat.Chat
import kami.libs.chat.Tone
import kami.libs.text.Phrase
import net.minecraft.server.MinecraftServer

object DbWatch {
    private const val CHECK_MS = 5_000L
    private const val MAX_REASON = 160
    private var checked = 0L
    private var seen = Db.dropCount

    fun tick(server: MinecraftServer) {
        val now = System.currentTimeMillis()
        if (now - checked < CHECK_MS) return
        checked = now
        val drops = Db.dropCount
        if (drops == seen) return
        seen = drops
        AdminPerms.notifyManagers(server, Chat.of("library").say(Tone.BAD, Phrase.of("kami_libs.db.unhealthy", Db.failure.orEmpty().take(MAX_REASON))))
    }
}
