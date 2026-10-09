package kami.claims.world

object FrameRule {
    enum class Kind { USE, ATTACK }

    fun allows(kind: Kind, lootr: Boolean, frameHasItem: Boolean, handEmpty: Boolean): Boolean = when (kind) {
        Kind.USE -> lootr || (!frameHasItem && handEmpty)
        Kind.ATTACK -> !lootr && frameHasItem
    }
}
