package kami.claims.war

import kami.claims.Config
import kami.claims.Country
import kami.claims.Key
import kami.claims.Realm
import kami.claims.research.Research
import kami.claims.research.SafeChunksUnlock
import kami.claims.research.unlocks
import kami.claims.service.Fail
import kami.claims.service.Words.num
import kami.libs.text.Phrase

object SafeChunks {
    fun limit(c: Country) = (if (c.level >= Config.s.war.safeChunkLevel) 1 else 0) + Research.unlocks<SafeChunksUnlock>(c).sumOf { it.add }

    fun valid(c: Country) = c.safeChunks.filter { Realm.index[it.key]?.country == c.id }

    fun active(c: Country) = valid(c).take(limit(c))

    private fun prune(c: Country, valid: List<ChunkRef>) {
        if (c.safeChunks.retainAll(valid.toSet())) Realm.changed()
    }

    private fun locked(c: Country) {
        if (Wars.fighting(c)) throw Fail("kami_claims.error.war.locked.safe")
    }

    fun set(c: Country, key: Key): Phrase {
        locked(c)
        val claim = Realm.index[key]?.takeIf { it.country == c.id } ?: throw Fail("kami_claims.error.war.safe.foreign")
        if (claim.capital) throw Fail("kami_claims.error.war.safe.capital")
        val ref = ChunkRef(key.dim, key.x, key.z)
        val have = valid(c)
        prune(c, have)
        val max = limit(c)
        if (ref in have) throw Fail("kami_claims.error.war.safe.already")
        if (have.size >= max) throw Fail("kami_claims.error.war.safe.limit", num(max))
        c.safeChunks += ref
        Realm.changed()
        return Phrase.of("kami_claims.done.war.safe_add", num(c.safeChunks.size), num(max))
    }

    fun unset(c: Country, key: Key): Phrase {
        locked(c)
        prune(c, valid(c))
        if (!c.safeChunks.remove(ChunkRef(key.dim, key.x, key.z))) throw Fail("kami_claims.error.war.safe.not")
        Realm.changed()
        return Phrase.of("kami_claims.done.war.safe_remove", num(c.safeChunks.size), num(limit(c)))
    }

    fun status(c: Country): Phrase {
        val have = valid(c)
        return Phrase.of("kami_claims.war.safe.list", num(have.size), num(limit(c)), have.joinToString(", ") { "${it.x},${it.z}" }.ifEmpty { "-" })
    }
}
