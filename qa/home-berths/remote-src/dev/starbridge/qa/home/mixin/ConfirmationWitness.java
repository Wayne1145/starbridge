package dev.starbridge.qa.home.mixin;

import dev.starbridge.orbit.OrbitWire;
import dev.starbridge.qa.home.RemoteHomeWorld;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import java.util.UUID;

@Mixin(targets = "dev.starbridge.orbit.LandingSurvey", remap = false)
abstract class ConfirmationWitness {
    @Shadow @Final UUID id;
    @Shadow @Final int x, z;
    @Shadow boolean confirm;

    @Inject(method = "end", at = @At("RETURN"), require = 1)
    private void afterClearance(OrbitWire.Clearance result, CallbackInfo callback) {
        if (confirm) RemoteHomeWorld.observeConfirm(id, x, z, result.name());
    }
}
