package kami.economy.mixin.coins;

import kami.libs.menu.GridMenu;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(targets = "kami.libs.menu.GridMenu$Gate")
public interface GridGateAccessor {
    @Accessor("this$0")
    GridMenu kamiMenu();
}
