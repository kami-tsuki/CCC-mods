package kami.libs.discord

import net.dv8tion.jda.api.Permission
import net.dv8tion.jda.api.entities.Guild
import net.dv8tion.jda.api.entities.channel.concrete.Category
import java.util.EnumSet

internal object Rooms {
    private const val CATEGORY_MAX = 50
    private const val NAME_MAX = 100
    private val invalid = Regex("[^\\p{L}\\p{N}_-]+")
    val MEMBER: EnumSet<Permission> = EnumSet.of(
        Permission.VIEW_CHANNEL, Permission.MESSAGE_SEND, Permission.MESSAGE_HISTORY, Permission.MESSAGE_ATTACH_FILES, Permission.MESSAGE_EMBED_LINKS,
        Permission.MESSAGE_ADD_REACTION, Permission.CREATE_PUBLIC_THREADS, Permission.CREATE_PRIVATE_THREADS, Permission.MESSAGE_SEND_IN_THREADS, Permission.MANAGE_THREADS
    )
    private val SELF: EnumSet<Permission> = EnumSet.of(Permission.VIEW_CHANNEL, Permission.MESSAGE_SEND, Permission.MESSAGE_HISTORY, Permission.MESSAGE_EMBED_LINKS, Permission.MANAGE_WEBHOOKS)

    @Volatile var byKey: Map<String, Long> = emptyMap()
        private set
    @Volatile var byChannel: Map<Long, String> = emptyMap()
        private set

    val categoryId get() = Discord.connected.countryCategoryId.toLongOrNull() ?: 0L

    fun routes(map: Map<String, Long>) {
        val clean = map.filterValues { it != 0L }
        if (clean == byKey) return
        byKey = clean
        byChannel = clean.entries.associate { (k, v) -> v to k }
    }

    fun name(raw: String) = raw.lowercase().replace(invalid, "-").trim('-').take(NAME_MAX).ifEmpty { "country" }

    fun exists(channelId: Long) = channelId != 0L && Discord.guild()?.getTextChannelById(channelId) != null

    fun members(channelId: Long): Set<Long>? {
        val guild = Discord.guild() ?: return null
        val self = guild.selfMember.idLong
        return guild.getTextChannelById(channelId)?.memberPermissionOverrides?.map { it.idLong }?.filterTo(HashSet()) { it != self }
    }

    fun orphans(): Map<Long, String> {
        val guild = Discord.guild() ?: return emptyMap()
        val base = guild.getCategoryById(categoryId) ?: return emptyMap()
        val prefix = "${base.name} "
        val known = byChannel.keys
        return guild.categories.filter { it.idLong == base.idLong || it.name.startsWith(prefix) && it.name.removePrefix(prefix).toIntOrNull() != null }
            .flatMap { it.textChannels }.filter { it.idLong !in known }.associate { it.idLong to it.name }
    }

    fun sync(room: Room, channelId: Long, members: Set<Long>, done: (Long) -> Unit) {
        val guild = Discord.guild() ?: return
        val name = name(room.name)
        val channel = guild.getTextChannelById(channelId) ?: return create(guild, name, members, done)
        val self = guild.selfMember.idLong
        if (channel.name != name) channel.manager.setName(name).queue(null) { warn("rename", it) }
        val current = channel.memberPermissionOverrides.filter { it.idLong != self }.associateBy { it.idLong }
        val manager = channel.manager
        var changed = false
        current.keys.filterNot { it in members }.forEach { manager.removePermissionOverride(it); changed = true }
        members.filter { id -> current[id].let { it == null || it.allowed != MEMBER || it.denied.isNotEmpty() } }.forEach { manager.putMemberPermissionOverride(it, MEMBER, emptySet()); changed = true }
        if (!changed) return done(channel.idLong)
        manager.queue({ Discord.post { done(channel.idLong) } }) { warn("members", it) }
    }

    fun delete(channelId: Long) {
        if (channelId == 0L) return
        Webhooks.invalidate(Outbox.Channel.of(channelId))
        Outbox.forget(Outbox.Channel.of(channelId))
        Discord.guild()?.getTextChannelById(channelId)?.delete()?.queue(null) { warn("delete", it) }
    }

    private fun create(guild: Guild, name: String, members: Set<Long>, done: (Long) -> Unit) = category(guild) { category ->
        val action = category.createTextChannel(name).syncPermissionOverrides().addMemberPermissionOverride(guild.selfMember.idLong, SELF, emptySet())
        members.forEach { action.addMemberPermissionOverride(it, MEMBER, emptySet()) }
        action.queue({ Discord.post { done(it.idLong) } }) { warn("create", it) }
    }

    private fun category(guild: Guild, use: (Category) -> Unit) {
        val base = guild.getCategoryById(categoryId) ?: return Discord.warnOnce("rooms-category", "The country category $categoryId was not found, country channels are off")
        val prefix = "${base.name} "
        val overflow = guild.categories.mapNotNull { c -> c.name.takeIf { it.startsWith(prefix) }?.removePrefix(prefix)?.toIntOrNull()?.let { it to c } }.sortedBy { it.first }
        val free = (listOf(base) + overflow.map { it.second }).firstOrNull { it.channels.size < CATEGORY_MAX }
        if (free != null) return use(free)
        base.createCopy().setName("$prefix${(overflow.maxOfOrNull { it.first } ?: 1) + 1}").queue(use) { warn("category", it) }
    }

    private fun warn(kind: String, e: Throwable) = Discord.warnOnce("rooms-$kind-${e.javaClass.simpleName}", "Country channel $kind failed: ${e.message}")
}
