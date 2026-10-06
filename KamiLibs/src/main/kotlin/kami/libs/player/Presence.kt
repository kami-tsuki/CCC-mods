package kami.libs.player

import java.util.UUID

object Presence {
    @Volatile var hidden: (UUID) -> Boolean = { false }
}
