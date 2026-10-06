package kami.economy.mixin.coins;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import dev.ithundxr.createnumismatics.content.depositor.AbstractDepositorBlock;
import dev.ithundxr.createnumismatics.content.salepoint.SalepointBlock;
import dev.ithundxr.createnumismatics.content.vendor.VendorBlock;
import java.util.UUID;
import kami.economy.world.CoinLoss;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;

@Mixin({VendorBlock.class, AbstractDepositorBlock.class, SalepointBlock.class})
public abstract class CoinDropMixin {
    @WrapMethod(method = "onRemove")
    private void kami$dropOwner(BlockState state, Level level, BlockPos pos, BlockState next, boolean moving, Operation<Void> original) {
        UUID prev = CoinLoss.dropping(level.getBlockEntity(pos));
        try {
            original.call(state, level, pos, next, moving);
        } finally {
            CoinLoss.dropped(prev);
        }
    }
}
