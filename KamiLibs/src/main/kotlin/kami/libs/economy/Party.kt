package kami.libs.economy

import kami.libs.util.toUuidOrNull
import java.util.UUID

sealed interface Party {
    val key: String

    companion object {
        fun parse(raw: String): Party? = when {
            raw == ServerParty.key -> ServerParty
            raw.startsWith("p:") -> raw.substring(2).toUuidOrNull()?.let(::PlayerParty)
            raw.startsWith("c:") && raw.length > 2 -> CountryParty(raw.substring(2))
            else -> raw.toUuidOrNull()?.let(::PlayerParty)
        }
    }
}

data class PlayerParty(val uuid: UUID) : Party {
    override val key get() = "p:$uuid"
    override fun toString() = key
}

data class CountryParty(val id: String) : Party {
    override val key get() = "c:$id"
    override fun toString() = key
}

data object ServerParty : Party {
    override val key = "server"
    override fun toString() = key
}
