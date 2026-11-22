package dev.starbridge.qa.home.landing.mixin;

import dev.starbridge.qa.home.landing.ApproachTiming;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.*;

@Mixin(targets="dev.starbridge.transit.BerthPlanner", remap=false)
abstract class BerthStage {
    @Inject(method="find", at=@At("HEAD"), require=1)
    private static void startSelectBerth(CallbackInfoReturnable<?> ci) { ApproachTiming.start("selectBerth"); }
    @Inject(method="find", at=@At("RETURN"), require=1)
    private static void endSelectBerth(CallbackInfoReturnable<?> ci) { ApproachTiming.end("selectBerth"); }
}
