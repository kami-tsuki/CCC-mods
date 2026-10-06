package kami.libs.ui.text

import kami.libs.economy.CountryParty
import kami.libs.economy.Party
import kami.libs.economy.PlayerParty
import kami.libs.economy.ServerParty
import net.minecraft.client.Minecraft
import java.util.UUID

fun partyLabel(raw: String, player: (UUID) -> String? = { Minecraft.getInstance().connection?.getPlayerInfo(it)?.profile?.name }): String = when (val p = Party.parse(raw)) {
    null -> raw
    ServerParty -> tr("kami_libs.party.server")
    is CountryParty -> tr("kami_libs.party.country", p.id)
    is PlayerParty -> player(p.uuid) ?: tr("kami_libs.party.player")
}
