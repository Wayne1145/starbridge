package dev.starbridge.qa.home.landing.mixin;

import dev.starbridge.qa.home.landing.ApproachTiming;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.*;

@Mixin(targets="dev.starbridge.compat.vs.VsShipBackend", remap=false)
abstract class NativeStages {
    @Inject(method="assembleCabin", at=@At("HEAD"), require=1)
    private void startNativeAssembly(CallbackInfoReturnable<?> ci) { ApproachTiming.start("nativeAssembly"); }
    @Inject(method="assembleCabin", at=@At("RETURN"), require=1)
    private void endNativeAssembly(CallbackInfoReturnable<?> ci) { ApproachTiming.end("nativeAssembly"); }
    @Inject(method="transfer", at=@At("HEAD"), require=1)
    private void startNativePortal(CallbackInfo ci) { ApproachTiming.start("nativePortal"); }
    @Inject(method="transfer", at=@At("RETURN"), require=1)
    private void endNativePortal(CallbackInfo ci) { ApproachTiming.end("nativePortal"); }
}
