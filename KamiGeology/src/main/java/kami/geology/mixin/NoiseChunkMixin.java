package kami.geology.mixin;

import kami.geology.world.OreVeins;
import net.minecraft.world.level.levelgen.NoiseChunk;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(NoiseChunk.class)
public abstract class NoiseChunkMixin {
    @Redirect(
        method = "<init>",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/levelgen/NoiseGeneratorSettings;oreVeinsEnabled()Z")
    )
    private boolean kamiGeology$oreVeins(NoiseGeneratorSettings settings) {
        return OreVeins.allow(settings.oreVeinsEnabled());
    }
}
