package kami.economy.world;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ClickType;

public final class CoinClick {
    private CoinClick() {
    }

    public static void run(int slot, int button, ClickType type, Player player, Operation<Void> original) {
        CoinRules.clickStart();
        try {
            original.call(slot, button, type, player);
        } finally {
            CoinRules.clickEnd(player);
        }
    }
}
