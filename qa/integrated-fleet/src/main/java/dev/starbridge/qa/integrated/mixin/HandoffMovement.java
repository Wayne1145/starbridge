package dev.starbridge.qa.integrated.mixin;

import dev.starbridge.qa.integrated.HandoffTrace;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.Vec3;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Entity.class)
abstract class HandoffMovement {
    @Inject(method = "m_6478_", at = @At("HEAD"), remap = false)
    private void integrated$beforeMove(MoverType type, Vec3 displacement, CallbackInfo callback) {
        if (HandoffTrace.ENABLED && (Object) this instanceof ServerPlayer player)
            HandoffTrace.before(player, displacement);
    }

    @ModifyArg(
            method = "m_6478_",
            at =
                    @At(
                            value = "INVOKE",
                            target =
                                    "Lnet/minecraft/world/level/Level;m_45547_(Lnet/minecraft/world/level/ClipContext;)Lnet/minecraft/world/phys/BlockHitResult;",
                            remap = false),
            index = 0,
            remap = false)
    private ClipContext integrated$beforeFallRay(ClipContext original) {
        if (HandoffTrace.ENABLED && (Object) this instanceof ServerPlayer player)
            HandoffTrace.ray(player, original);
        return original;
    }
}
