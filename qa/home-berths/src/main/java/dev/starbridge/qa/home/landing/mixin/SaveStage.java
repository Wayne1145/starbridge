package dev.starbridge.qa.home.landing.mixin;

import dev.starbridge.qa.home.landing.ApproachTiming;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.*;

@Mixin(targets="dev.starbridge.transit.PassageStorage", remap=false)
abstract class SaveStage {
    @Inject(method="advance", at=@At("HEAD"), require=1)
    private void startSaveChunkBatch(CallbackInfoReturnable<?> ci) { ApproachTiming.start("saveChunkBatch"); }
    @Inject(method="advance", at=@At("RETURN"), require=1)
    private void endSaveChunkBatch(CallbackInfoReturnable<?> ci) { ApproachTiming.end("saveChunkBatch"); }
}
