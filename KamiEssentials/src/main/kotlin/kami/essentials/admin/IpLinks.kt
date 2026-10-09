package kami.essentials.admin

import java.util.UUID

internal object IpLinks {
    fun linked(ips: Collection<String>, added: Collection<String>, links: Map<UUID, Set<String>>, self: UUID, banned: (UUID) -> Boolean): List<String> =
        ips.filter { ip -> ip in added || links.any { (other, set) -> other != self && ip in set && banned(other) } }
}
