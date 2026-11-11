package kami.claims.gov

import kami.claims.gov.ActionArgs.NEW
import kami.claims.service.Fail
import kami.claims.service.Oversight
import kami.claims.service.Service
import kami.claims.service.Words.v
import kami.libs.text.Phrase
import net.minecraft.server.level.ServerPlayer

object SectorActions {
    fun act(p: ServerPlayer, name: String, args: List<String>): Phrase? = when (name) {
        "sector_save" -> save(p, args)
        "sector_delete" -> delete(p, args)
        else -> null
    }

    private fun save(p: ServerPlayer, a: List<String>): Phrase {
        val c = Service.need(p, Perm.SECTORS)
        val existing = ActionArgs.arg(a, 0).takeIf { it != NEW }?.let { ActionArgs.sector(c, it) }
        val refs = ActionArgs.arg(a, 3)
        if (existing != null && Sectors.boundJobs(c, existing).isNotEmpty() && !Oversight.may(c, p, Perm.SCOPE_JOBS) && Sectors.validated(c, refs) != existing.chunks) throw Fail("kami_claims.error.sector_bound")
        val saved = Sectors.save(c, existing, ActionArgs.arg(a, 1), ActionArgs.color(a, 2), refs, a.getOrNull(4).orEmpty())
        Audit.record(c, p.stringUUID, "sector_save", saved.id, saved.name)
        return Phrase.of("kami_claims.done.sector_saved", v(saved.name))
    }

    private fun delete(p: ServerPlayer, a: List<String>): Phrase {
        val c = Service.need(p, Perm.SECTORS)
        val s = ActionArgs.sector(c, ActionArgs.arg(a, 0))
        if (Sectors.boundJobs(c, s).isNotEmpty() && !Oversight.may(c, p, Perm.SCOPE_JOBS)) throw Fail("kami_claims.error.sector_bound")
        Sectors.dissolve(c, s)
        Audit.record(c, p.stringUUID, "sector_delete", s.id, s.name)
        return Phrase.of("kami_claims.done.sector_deleted", v(s.name))
    }
}

