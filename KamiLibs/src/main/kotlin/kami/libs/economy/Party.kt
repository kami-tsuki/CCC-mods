package kami.libs.economy

import java.util.UUID

sealed interface Party {
    val key: String

    companion object {
        fun parse(raw: String): Party? = when {
            raw == ServerParty.key -> ServerParty
            raw.startsWith("p:") -> uuid(raw.substring(2))?.let(::PlayerParty)
            raw.startsWith("c:") && raw.length > 2 -> CountryParty(raw.substring(2))
            else -> uuid(raw)?.let(::PlayerParty)
        }

        private fun uuid(raw: String) = runCatching { UUID.fromString(raw) }.getOrNull()
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
