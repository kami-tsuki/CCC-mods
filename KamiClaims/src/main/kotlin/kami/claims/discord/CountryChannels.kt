package kami.claims.discord

import kami.claims.Config
import kami.claims.Country
import kami.claims.NameRules
import kami.claims.Realm
import kami.claims.gov.Permissions
import kami.claims.service.Announce
import kami.claims.press.NewsDelivery
import kami.claims.service.Words
import kami.libs.chat.Theme
import kami.libs.chat.Tone
import kami.libs.discord.DiscordApi
import kami.libs.discord.DiscordBans
import kami.libs.discord.DiscordFeature
import kami.libs.discord.DiscordText
import kami.libs.discord.Embed
import kami.libs.discord.Links
import kami.libs.discord.Room
import kami.libs.text.Phrase
import kami.libs.util.toUuidOrNull
import java.util.TreeSet

object CountryChannels : DiscordFeature {
    private const val RETRY_MS = 60_000L
    private val synced = HashMap<String, String>()
    private val pending = HashMap<String, Long>()

    val on get() = Config.s.countryChannels && DiscordApi.roomsOn

    override fun onReady() {
        synced.clear()
        NewsDelivery.flush()
    }

    override fun onJoin(userId: Long) = synced.clear()

    fun tick() {
        NewsDelivery.flush()
        if (!on) return
        val now = System.currentTimeMillis()
        Realm.data.countries.values.filter { !it.active && it.discordChannel != 0L }.forEach(::drop)
        val live = Realm.data.countries.values.filter { it.active }
        val names = names(live)
        live.forEach { c ->
            if (c.discordChannel != 0L && !DiscordApi.roomExists(c.discordChannel)) {
                c.discordChannel = 0L
                Realm.changed()
            }
            val name = names.getValue(c.id)
            val members = c.members.keys.mapNotNullTo(TreeSet()) { id -> id.toUuidOrNull()?.let(Links::get)?.discordId?.takeIf { DiscordBans.banned(it) == null } }
            val sig = "$name|${c.discordChannel}|$members"
            if (synced[c.id] == sig || (pending[c.id] ?: 0L) > now) return@forEach
            pending[c.id] = now + RETRY_MS
            DiscordApi.syncRoom(Room(c.id, name, members), c.discordChannel) { id -> created(c.id, id, name, members) }
        }
        DiscordApi.rooms(live.associate { it.id to it.discordChannel })
    }

    private fun created(key: String, id: Long, name: String, members: Set<Long>) {
        pending.remove(key)
        val c = Realm.country(key)?.takeIf { it.active } ?: return DiscordApi.deleteRoom(id)
        val fresh = c.discordChannel != id
        if (fresh) c.discordChannel = id
        synced[key] = "$name|$id|$members"
        DiscordApi.rooms(Realm.data.countries.values.filter { it.active }.associate { it.id to it.discordChannel })
        welcome(c, fresh, members)
    }

    private fun welcome(c: Country, fresh: Boolean, members: Set<Long>) {
        val added = if (fresh) members else members - c.discordSeen
        if (fresh) event(c, Phrase.of("kami_claims.discord.room.ready", Words.v(c.name)), Tone.OK)
        added.forEach { d -> Links.byDiscord(d)?.let { l -> event(c, Phrase.of("kami_claims.discord.room.joined", Words.v(l.name), rank(c, l.uuid)), Tone.OK) } }
        if (fresh) c.members.keys.filter { id -> id.toUuidOrNull()?.let(Links::get) == null }
            .forEach { id -> event(c, Phrase.of("kami_claims.discord.room.unlinked", Words.v(Announce.name(id)), rank(c, id)), Tone.INFO) }
        if (!fresh && c.discordSeen == members) return
        c.discordSeen.clear()
        c.discordSeen += members
        Realm.changed()
    }

    private fun rank(c: Country, id: String) = Words.v(Permissions.rankOf(c, id)?.name.orEmpty())

    fun resync(c: Country?) {
        if (c == null) {
            synced.clear()
            pending.clear()
        } else {
            synced.remove(c.id)
            pending.remove(c.id)
        }
        tick()
    }

    fun recreate(c: Country) {
        drop(c)
        pending.remove(c.id)
        tick()
    }

    fun clean(): Int {
        if (!on) return 0
        tick()
        val orphans = DiscordApi.roomOrphans()
        orphans.keys.forEach(DiscordApi::deleteRoom)
        return orphans.size
    }

    fun drop(c: Country) {
        if (c.discordChannel == 0L) return
        DiscordApi.deleteRoom(c.discordChannel)
        c.discordChannel = 0L
        synced.remove(c.id)
        Realm.changed()
    }

    fun event(c: Country, text: Phrase, tone: Tone) = post(c, DiscordText.phrase(text), if (tone == Tone.INFO) Theme.LINK else tone.mark)

    fun post(c: Country, text: String, color: Int, card: Boolean = false) {
        if (on && c.discordChannel != 0L) DiscordApi.roomEvent(c.id, Embed(text, color), card)
    }

    private fun names(list: List<Country>): Map<String, String> {
        val seen = HashMap<String, Int>()
        return list.sortedBy { it.created }.associate { c ->
            val n = seen.merge(NameRules.key(c.name), 1, Int::plus) ?: 1
            c.id to if (n == 1) c.name else "${c.name}-$n"
        }
    }
}
