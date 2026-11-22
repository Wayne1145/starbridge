package dev.starbridge.qa.home.landing.mixin;

import dev.starbridge.qa.home.landing.ApproachTiming;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.*;
import dev.starbridge.block.ShipCoreBlockEntity;
import dev.starbridge.compat.vs.VsShipBackend.StorageBounds;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;
import java.util.UUID;

@Mixin(targets="dev.starbridge.transit.CabinApproach", remap=false)
abstract class CabinStages {
    @Inject(method="cabinBlocks", at=@At("HEAD"), require=1)
    private static void startEnumerateCabin(CallbackInfoReturnable<?> ci) { ApproachTiming.start("enumerateCabin"); }
    @Inject(method="cabinBlocks", at=@At("RETURN"), require=1)
    private static void endEnumerateCabin(CallbackInfoReturnable<?> ci) { ApproachTiming.end("enumerateCabin"); }
    @Inject(method="put", at=@At("HEAD"), require=1)
    private void startStoreApproach(CallbackInfo ci) { ApproachTiming.start("storeApproach"); }
    @Inject(method="put", at=@At("RETURN"), require=1)
    private void endStoreApproach(CallbackInfo ci) { ApproachTiming.end("storeApproach"); }
    @Inject(method="begin", at=@At("HEAD"), require=1)
    private static void enter(ServerPlayer pilot,ShipCoreBlockEntity core,StorageBounds bounds,ServerLevel target,Vec3 berth,String route,int fare,CallbackInfo ci) { ApproachTiming.enterFixed(pilot.serverLevel(),bounds); }
    @Inject(method="begin", at=@At("RETURN"), require=1)
    private static void leave(CallbackInfo ci) { ApproachTiming.end("assembleAndRecordApproach"); }
}
