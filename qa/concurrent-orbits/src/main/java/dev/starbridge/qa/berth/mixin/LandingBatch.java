package dev.starbridge.qa.berth.mixin;

import dev.starbridge.orbit.OrbitModule;
import dev.starbridge.orbit.OrbitWire;
import dev.starbridge.qa.berth.ConcurrentBerths;

import net.minecraft.server.level.ServerPlayer;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Hold the two actual mouse-generated requests until both can enter the normal handler in one tick.
 */
@Mixin(value = OrbitModule.class, remap = false)
abstract class LandingBatch {
    @Inject(method = "control", at = @At("HEAD"), cancellable = true, remap = false)
    private static void simultaneous(
            ServerPlayer player, OrbitWire.Control packet, CallbackInfo event) {
        if (ConcurrentBerths.queue(player, packet)) event.cancel();
    }
}
