package kami.libs.mixin;

import kami.libs.xaero.impl.WorldMapAdapter;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import xaero.map.gui.IRightClickableElement;
import xaero.map.gui.dropdown.rightclick.RightClickOption;

import java.util.ArrayList;

@Pseudo
@Mixin(targets = "xaero.map.gui.GuiMap", remap = false)
public abstract class XaeroGuiMapMixin {
    @Shadow(remap = false)
    private int rightClickX;
    @Shadow(remap = false)
    private int rightClickZ;
    @Shadow(remap = false)
    private ResourceKey<Level> rightClickDim;

    @Inject(method = "getRightClickOptions", at = @At("RETURN"), require = 0, remap = false)
    private void kami$actions(CallbackInfoReturnable<ArrayList<RightClickOption>> cir) {
        ArrayList<RightClickOption> options = cir.getReturnValue();
        if (options == null || rightClickDim == null) return;
        options.addAll(WorldMapAdapter.rightClick(rightClickDim, rightClickX, rightClickZ, (IRightClickableElement) (Object) this, options.size()));
    }
}
