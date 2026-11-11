package kami.claims.gov

import kami.claims.Country
import kami.claims.now
import kotlinx.serialization.Serializable

@Serializable
class AuditEntry(val at: Long, val actor: String, val action: String, val target: String = "", val detail: String = "")

object Audit {
    const val LIMIT = 200

    fun record(c: Country, actor: String, action: String, target: String = "", detail: String = "") {
        c.audit += AuditEntry(now(), actor, action, target, detail)
        while (c.audit.size > LIMIT) c.audit.removeAt(0)
    }
}
