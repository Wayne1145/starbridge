package dev.starbridge.qa.wide.mixin;

import net.minecraft.client.gui.screens.Screen;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.nio.file.*;

@Mixin(targets = "dev.starbridge.qa.integrated.Pilot", remap = false)
abstract class WaitForEngineer {
    @Inject(method = "click", at = @At("HEAD"), cancellable = true, remap = false)
    private static void wide$awaitWork(
            Screen screen, String key, CallbackInfoReturnable<Boolean> callback) {
        if (key.equals("orbit.starbridge.landing")
                && Files.isRegularFile(Path.of("wide-fleet-host.fixture"))
                && !Files.isRegularFile(Path.of("wide-job-verified.json")))
            callback.setReturnValue(false);
    }
}
