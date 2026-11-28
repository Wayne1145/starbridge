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

@Mixin(targets="dev.starbridge.transit.HullTransit", remap=false)
abstract class TransitStages {
    @Inject(method="matchingCargo", at=@At("HEAD"), require=1)
    private static void startMatchTwoSeals(CallbackInfoReturnable<?> ci) { ApproachTiming.start("matchTwoSeals"); }
    @Inject(method="matchingCargo", at=@At("RETURN"), require=1)
    private static void endMatchTwoSeals(CallbackInfoReturnable<?> ci) { ApproachTiming.end("matchTwoSeals"); }
    @Inject(method="holdTickets", at=@At("HEAD"), require=1)
    private static void startHoldOrReleaseTickets(CallbackInfo ci) { ApproachTiming.start("holdOrReleaseTickets"); }
    @Inject(method="holdTickets", at=@At("RETURN"), require=1)
    private static void endHoldOrReleaseTickets(CallbackInfo ci) { ApproachTiming.end("holdOrReleaseTickets"); }
    @Inject(method="begin", at=@At("HEAD"), require=1)
    private static void enter(ShipCoreBlockEntity core,ServerPlayer pilot,ServerLevel target,Vec3 berth,String route,int fare,CallbackInfoReturnable<UUID> ci) { ApproachTiming.enterNative(core,pilot,target); }
    @Inject(method="begin", at=@At("RETURN"), require=1)
    private static void leave(CallbackInfoReturnable<UUID> ci) { ApproachTiming.end("beginPassage"); }
}
