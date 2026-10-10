package kami.essentials.mixin;

import kami.essentials.skills.Ravagers;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.Ravager;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.raid.Raider;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;

@Mixin(Ravager.class)
public abstract class RavagerMixin extends Raider {
    protected RavagerMixin(EntityType<? extends Raider> type, Level level) {
        super(type, level);
    }

    @Override
    public LivingEntity getControllingPassenger() {
        return getFirstPassenger() instanceof Player player && Ravagers.INSTANCE.rideable(this, player) ? player : super.getControllingPassenger();
    }

    @Override
    protected void tickRidden(Player player, Vec3 input) {
        super.tickRidden(player, input);
        setRot(player.getYRot(), player.getXRot() * 0.5F);
        yRotO = yBodyRot = yHeadRot = getYRot();
    }

    @Override
    protected Vec3 getRiddenInput(Player player, Vec3 input) {
        float forward = player.zza;
        return new Vec3(player.xxa * 0.5F, 0.0, forward <= 0.0F ? forward * 0.25F : forward);
    }

    @Override
    protected float getRiddenSpeed(Player player) {
        return 0.25F;
    }
}
