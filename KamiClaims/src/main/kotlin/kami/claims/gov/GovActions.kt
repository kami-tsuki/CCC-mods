package kami.claims.gov

import kami.libs.text.Phrase
import net.minecraft.server.level.ServerPlayer

object GovActions {
    fun handle(p: ServerPlayer, name: String, args: List<String>): Phrase? =
        RankActions.act(p, name, args) ?: GovernmentActions.act(p, name, args) ?: JobActions.act(p, name, args) ?: SectorActions.act(p, name, args)
}
