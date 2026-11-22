package dev.starbridge.qa.manual.mixin;

import dev.starbridge.qa.manual.ManualPilot;

import net.minecraft.client.gui.screens.Screen;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(targets = "dev.starbridge.qa.integrated.Pilot", remap = false)
abstract class ManualApproach {
    @Inject(method = "click", at = @At("HEAD"), cancellable = true, remap = false)
    private static void waitForManualArrival(
            Screen screen, String key, CallbackInfoReturnable<Boolean> result) {
        if (key.equals("orbit.starbridge.landing") && !ManualPilot.ready(screen))
            result.setReturnValue(false);
    }
}
