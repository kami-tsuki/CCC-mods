package kami.claims.client.compat

import kami.claims.client.ClientClaims
import kami.claims.client.world.AccessGuess
import kami.claims.compat.JadeIds
import kami.claims.compat.JadeServer
import kami.claims.client.store.ClientResearch
import kami.claims.service.View
import net.minecraft.core.registries.BuiltInRegistries
import kami.libs.jade.KamiJade
import net.minecraft.network.chat.Component
import net.minecraft.world.entity.decoration.ItemFrame

object JadeClient {
    fun register() {
        JadeServer.unclaimed = { dim, x, z -> ClientClaims.at(dim, x, z) == null }
        JadeServer.offered = { dim, x, z -> ClientClaims.at(dim, x, z)?.let { it.flags and View.OFFER != 0 } == true }
        KamiJade.block(JadeIds.CLAIM) { look ->
            val dim = look.level.dimension().location().toString()
            val e = ClientClaims.at(dim, look.pos.x shr 4, look.pos.z shr 4)
            val country = e?.let(ClientClaims::country)
            if (e == null || country == null) emptyList() else {
                val line = Component.literal(country.name).withColor(country.color)
                if (e.flags and View.ENEMY != 0) listOf(line)
                else {
                    line.append(Component.literal(" · ")).append(
                        if (AccessGuess.allowed(dim, look.pos.x, look.pos.z, "place")) Component.translatable("kami_claims.jade.build.yes").withColor(0x55FF55)
                        else Component.translatable("kami_claims.jade.build.no").withColor(0xFF5555)
                    )
                    if (e.flags and View.STAKED != 0) line.append(Component.literal(" · ")).append(Component.translatable("kami_claims.jade.staked").withColor(0xFFAA00))
                    else if (e.flags and View.SAFE != 0) line.append(Component.literal(" · ")).append(Component.translatable("kami_claims.jade.safe").withColor(0x55FF55))
                    listOf(line)
                }
            }
        }
        KamiJade.block(JadeIds.OFFER) { look ->
            val price = look.data.getCompound(JadeIds.OFFER.toString()).getInt("price")
            if (price > 0) listOf(Component.translatable("kami_claims.jade.offer", price)) else emptyList()
        }
        KamiJade.block(JadeIds.RESEARCH) { look ->
            val id = BuiltInRegistries.BLOCK.getKey(look.state.block).toString()
            if (id !in ClientResearch.lockedBlocks()) emptyList()
            else ClientResearch.unlockedBy(id).firstNotNullOfOrNull(ClientResearch::node)?.let {
                listOf(Component.translatable("kami_claims.jade.research", it.label().resolve()).withColor(0xFF5555))
            } ?: emptyList()
        }
        KamiJade.block(JadeIds.TEMP) { look ->
            val due = look.data.getCompound(JadeIds.TEMP.toString()).takeIf { it.contains("due") }?.getLong("due")
            val left = ((due ?: return@block emptyList()) - look.level.gameTime).coerceAtLeast(0) / 20
            listOf(Component.translatable("kami_claims.jade.temp", "%d:%02d".format(left / 60, left % 60)))
        }
        KamiJade.entity(JadeIds.FRAME, ItemFrame::class.java) { look ->
            if (look.data.getCompound(JadeIds.FRAME.toString()).getBoolean("take")) listOf(Component.translatable("kami_claims.jade.frame")) else emptyList()
        }
    }
}
