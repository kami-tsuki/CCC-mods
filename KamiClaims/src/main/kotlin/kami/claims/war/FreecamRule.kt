package kami.claims.war

import kami.claims.Country
import kami.claims.Key
import kami.claims.Realm
import kami.claims.service.Diplomacy
import kami.claims.service.View
import net.minecraft.server.level.ServerPlayer

object FreecamRule {
    const val ALLOWED = 1
    const val WARRING = 2

    fun friendly(owner: Country, home: Country?, viewer: String) = when {
        home != null && Wars.enemies(home.id, owner.id) -> false
        View.relation(owner, viewer) != 0 -> true
        else -> home != null && (home.id == owner.id || Diplomacy.sameFamily(home, owner) || owner.id in home.alliances)
    }

    fun permits(owner: Country?, home: Country?, viewer: String, warring: Boolean) = if (owner == null) !warring else friendly(owner, home, viewer)

    fun state(p: ServerPlayer): Int {
        val id = p.stringUUID
        val home = Realm.of(id)
        val warring = home != null && Wars.involved(home)
        val owner = Realm.index[Key(p.level().dimension().location().toString(), p.blockX shr 4, p.blockZ shr 4)]?.let { Realm.country(it.country) }
        return (if (permits(owner, home, id, warring)) ALLOWED else 0) or (if (warring) WARRING else 0)
    }
}
