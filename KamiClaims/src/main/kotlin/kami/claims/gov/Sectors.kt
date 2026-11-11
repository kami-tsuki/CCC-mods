package kami.claims.gov

import kami.claims.Country
import kami.claims.Realm
import kami.claims.research.Capacity
import kami.claims.research.Features
import kami.claims.research.Levels
import kami.claims.service.Fail
import kami.claims.service.Words
import kami.claims.service.Work
import kami.claims.social.Mail
import kami.claims.war.ChunkRef
import kami.libs.chat.Tone
import kami.libs.text.Phrase
import kotlinx.serialization.Serializable

@Serializable
class Sector(
    val id: String,
    var name: String,
    var color: Int,
    val chunks: MutableSet<ChunkRef> = mutableSetOf(),
    var manager: String? = null,
    var fragmented: Boolean = false
)

object SectorRules {
    const val MIN_CHUNKS = 2
    const val MAX_CHUNKS = 16
    val workTypes = setOf("mining", "farming", "forestry", "factory")
}

object Sectors {
    fun usable(s: Sector) = !s.fragmented

    fun get(c: Country, id: String): Sector? = c.sectors[id]

    fun save(c: Country, existing: Sector?, name: String, color: Int, refs: String, manager: String): Sector {
        if (existing == null && c.sectors.size >= Levels.capacity(c, Capacity.SECTORS)) throw Fail("kami_claims.error.sector_limit")
        val label = ActionArgs.label(name)
        val chunks = validated(c, refs)
        val managerId = manager.takeIf { it.isNotBlank() && it != "-" }?.let { ActionArgs.uuid(listOf(it), 0).also { id -> ActionArgs.member(c, id) } }
        val saved = existing ?: Sector(ActionArgs.newId(), label, color)
        saved.name = label
        saved.color = color
        saved.manager = managerId
        saved.chunks.clear()
        saved.chunks += chunks
        saved.fragmented = false
        c.sectors[saved.id] = saved
        return saved
    }

    fun validated(c: Country, text: String): Set<ChunkRef> {
        val refs = text.split(',').filter { it.isNotBlank() }.map { ChunkRef.parse(it.trim()) ?: throw Fail("kami_claims.error.sector_chunks") }.toSet()
        if (refs.size !in SectorRules.MIN_CHUNKS..SectorRules.MAX_CHUNKS) throw Fail("kami_claims.error.sector_size", Words.num(SectorRules.MIN_CHUNKS), Words.num(SectorRules.MAX_CHUNKS))
        refs.forEach { ref ->
            val claim = Realm.at(ref.dim, ref.x, ref.z)?.takeIf { it.country == c.id } ?: throw Fail("kami_claims.error.not_your_chunk")
            if (claim.type !in SectorRules.workTypes) throw Fail("kami_claims.error.sector_type")
            Features.require(c, Features.claimType(claim.type))
        }
        if (!SectorGeometry.connected(refs)) throw Fail("kami_claims.error.sector_connected")
        return refs
    }

    fun chunkLeft(c: Country, ref: ChunkRef) {
        c.sectors.values.filter { ref in it.chunks }.forEach { s ->
            s.chunks -= ref
            if (s.chunks.size < SectorRules.MIN_CHUNKS) dissolve(c, s) else refresh(s)
        }
    }

    fun dissolve(c: Country, s: Sector) {
        c.sectors.remove(s.id)
        val bound = boundJobs(c, s)
        bound.forEach { it.active = false }
        val affected = bound.flatMap { Work.holders(c, it.id) } + listOfNotNull(s.manager)
        affected.toSet().forEach { Mail.direct(it, Phrase.of("kami_claims.mail.sector_dissolved", Words.v(s.name)), Tone.BAD) }
    }

    fun boundJobs(c: Country, s: Sector) = c.jobDefs.values.filter { it.scope == JobScope.SECTOR && it.scopeRef == s.id }

    private fun refresh(s: Sector) {
        val broken = !SectorGeometry.connected(s.chunks)
        if (broken && !s.fragmented) s.manager?.let { Mail.direct(it, Phrase.of("kami_claims.mail.sector_fragmented", Words.v(s.name)), Tone.WARN) }
        s.fragmented = broken
    }
}
