package dev.starbridge.qa.home.landing.mixin;

import dev.starbridge.qa.home.landing.ApproachTiming;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.*;

@Mixin(targets="dev.starbridge.transit.TransitJournal", remap=false)
abstract class JournalStages {
    @Inject(method="update", at=@At("HEAD"), require=1)
    private void startDurableJournal(CallbackInfo ci) { ApproachTiming.start("durableJournal"); }
    @Inject(method="update", at=@At("RETURN"), require=1)
    private void endDurableJournal(CallbackInfo ci) { ApproachTiming.end("durableJournal"); }
}
