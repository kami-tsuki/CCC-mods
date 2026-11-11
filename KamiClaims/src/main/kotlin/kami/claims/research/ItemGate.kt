package kami.claims.research

import kami.claims.Realm
import kami.libs.chat.Chat
import kami.libs.chat.Tone
import kami.libs.chat.bar
import kami.libs.text.Phrase
import kami.libs.mc.ItemSpec
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.item.ItemStack
import net.neoforged.neoforge.common.util.FakePlayer

object ItemGate {
    @JvmStatic
    fun allows(player: ServerPlayer, stack: ItemStack): Boolean {
        if (stack.isEmpty || player is FakePlayer || RecipeFilter.bypassed(player)) return true
        return Gate.item(Realm.of(player.stringUUID), ItemSpec.id(stack))
    }

    @JvmStatic
    fun deny(player: ServerPlayer) {
        player.bar(Chat.bar(Tone.BAD, Phrase.of("kami_claims.research.item_locked").component()))
    }
}
