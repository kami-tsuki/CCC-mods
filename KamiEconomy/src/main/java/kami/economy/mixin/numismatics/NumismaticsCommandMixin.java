package kami.economy.mixin.numismatics;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.mojang.brigadier.context.CommandContext;
import dev.ithundxr.createnumismatics.content.backend.BankAccount;
import dev.ithundxr.createnumismatics.content.backend.Coin;
import dev.ithundxr.createnumismatics.registry.commands.DeductCommand;
import dev.ithundxr.createnumismatics.registry.commands.PayAllCommand;
import dev.ithundxr.createnumismatics.registry.commands.PayCommand;
import java.util.UUID;
import java.util.function.Supplier;
import kami.economy.economy.CoinHooks;
import kami.libs.economy.MoneyGuard;
import net.minecraft.commands.CommandSourceStack;
import org.spongepowered.asm.mixin.Mixin;

@Mixin({DeductCommand.class, PayCommand.class, PayAllCommand.class})
public abstract class NumismaticsCommandMixin {
    private static final String CTX = "Lcom/mojang/brigadier/context/CommandContext;";
    private static final String TYPE = "Ldev/ithundxr/createnumismatics/content/backend/BankAccount$Type;";
    private static final String COIN = "Ldev/ithundxr/createnumismatics/content/backend/Coin;";

    private static int kami$run(CommandContext<CommandSourceStack> ctx, Supplier<Integer> call) {
        boolean open = CoinHooks.command(ctx);
        try {
            return call.get();
        } finally {
            if (open) MoneyGuard.exit();
        }
    }

    @WrapMethod(method = "execute(" + CTX + "Ljava/util/UUID;" + TYPE + "ZLjava/lang/String;IZ" + COIN + ")I")
    private static int kami$deduct(CommandContext<CommandSourceStack> ctx, UUID id, BankAccount.Type type, boolean b, String name, int amount, boolean force, Coin coin, Operation<Integer> original) {
        return kami$run(ctx, () -> original.call(ctx, id, type, b, name, amount, force, coin));
    }

    @WrapMethod(method = "execute(" + CTX + "Ljava/util/UUID;" + TYPE + "ZLjava/lang/String;I" + COIN + ")I")
    private static int kami$pay(CommandContext<CommandSourceStack> ctx, UUID id, BankAccount.Type type, boolean b, String name, int amount, Coin coin, Operation<Integer> original) {
        return kami$run(ctx, () -> original.call(ctx, id, type, b, name, amount, coin));
    }

    @WrapMethod(method = "execute(" + CTX + "I" + COIN + ")I")
    private static int kami$payAll(CommandContext<CommandSourceStack> ctx, int amount, Coin coin, Operation<Integer> original) {
        return kami$run(ctx, () -> original.call(ctx, amount, coin));
    }
}
