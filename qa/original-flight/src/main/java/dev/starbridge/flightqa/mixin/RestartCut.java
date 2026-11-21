package dev.starbridge.flightqa.mixin;

import dev.starbridge.flightqa.RestartCheck;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Pseudo
@Mixin(targets = "dev.starbridge.transit.TransitJournal", remap = false)
abstract class RestartCut {
    @Inject(
            method =
                    "commit(Ldev/starbridge/transit/TransitJournal$Leg;)Ldev/starbridge/transit/TransitJournal$Leg;",
            at = @At("HEAD"),
            remap = false,
            require = 1)
    private void observePersistedFare(@Coerce Object passage, CallbackInfoReturnable<?> outcome)
            throws Exception {
        RestartCheck.beforeJournalCommit();
    }

    @Inject(method="preparePayment(Ldev/starbridge/transit/TransitJournal$Leg;Ldev/starbridge/transit/TransitJournal$FareProof;)Ldev/starbridge/transit/TransitJournal$Leg;",at=@At("HEAD"),remap=false,require=1)
    private void recordCertifiedMachines(@Coerce Object passage,@Coerce Object proof,CallbackInfoReturnable<?> result) throws Exception {
        var server=net.minecraftforge.server.ServerLifecycleHooks.getCurrentServer();
        if (server!=null)dev.starbridge.flightqa.MachineWitness.capture(server,"proof");
    }
}
