package kami.claims.gov

import kami.claims.service.Fail
import kami.claims.service.Words.v

internal object PermCodec {
    fun parse(text: String): Set<Perm> = text.split(',').filter { it.isNotBlank() }.map { token ->
        Perm.entries.firstOrNull { it.name.equals(token.trim(), true) } ?: throw Fail("kami_claims.error.rank_perm", v(token))
    }.toSet()

    fun diff(added: Set<Perm>, removed: Set<Perm>) = (added.map { "+${it.node}" } + removed.map { "-${it.node}" }).joinToString(" ")
}
