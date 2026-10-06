package kami.libs.admin

import net.minecraft.server.level.ServerPlayer
import java.util.UUID

object Subject {
    fun resolve(p: ServerPlayer, subject: String?): UUID = resolve(p.uuid, AdminPerms.level(p), subject)

    fun resolve(self: UUID, level: AdminPerms.Level, subject: String?): UUID {
        val id = parse(subject) ?: return self
        return if (level >= AdminPerms.Level.VIEW) id else self
    }

    fun requireSelf(p: ServerPlayer, subject: String?) = requireSelf(p.uuid, subject)

    fun requireSelf(self: UUID, subject: String?) {
        if (!subject.isNullOrBlank() && parse(subject) != self) throw AdminFailure("kami_libs.admin.read_only")
    }

    private fun parse(subject: String?) = subject?.let { runCatching { UUID.fromString(it.trim()) }.getOrNull() }
}
