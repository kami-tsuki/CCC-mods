package kami.claims.gov

import kami.claims.war.ChunkRef
import kami.claims.war.StakeRules

object SectorGeometry {
    fun connected(chunks: Collection<ChunkRef>): Boolean = chunks.isEmpty() || StakeRules.connected(chunks.mapTo(HashSet()) { it.key })
}
