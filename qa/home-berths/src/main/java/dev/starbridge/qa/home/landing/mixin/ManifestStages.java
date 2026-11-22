package dev.starbridge.qa.home.landing.mixin;

import dev.starbridge.qa.home.landing.ApproachTiming;
import net.minecraft.server.level.ServerLevel;
import dev.starbridge.compat.vs.VsShipBackend.StorageBounds;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.*;

@Mixin(targets="dev.starbridge.transit.HullManifest", remap=false)
abstract class ManifestStages {
    @Inject(method="capture", at=@At("HEAD"), require=1)
    private static void startCaptureCargo(CallbackInfoReturnable<?> ci) { ApproachTiming.start("captureCargo"); }
    @Inject(method="capture", at=@At("RETURN"), require=1)
    private static void endCaptureCargo(CallbackInfoReturnable<?> ci) { ApproachTiming.end("captureCargo"); }
    @Inject(method="restoreMovedMachines", at=@At("HEAD"), require=1)
    private static void startRestoreMachines(CallbackInfo ci) { ApproachTiming.start("restoreMachines"); }
    @Inject(method="restoreMovedMachines", at=@At("RETURN"), require=1)
    private static void endRestoreMachines(CallbackInfo ci) { ApproachTiming.end("restoreMachines"); }
    @Inject(method="inspect(Lnet/minecraft/server/level/ServerLevel;Ldev/starbridge/compat/vs/VsShipBackend$StorageBounds;)Ldev/starbridge/transit/HullManifest$Seal;",at=@At("HEAD"),require=1)
    private static void inspectStart(ServerLevel level,StorageBounds bounds,CallbackInfoReturnable<?> ci) {ApproachTiming.start("inspect:"+level.dimension().location());}
    @Inject(method="inspect(Lnet/minecraft/server/level/ServerLevel;Ldev/starbridge/compat/vs/VsShipBackend$StorageBounds;)Ldev/starbridge/transit/HullManifest$Seal;",at=@At("RETURN"),require=1)
    private static void inspectEnd(ServerLevel level,StorageBounds bounds,CallbackInfoReturnable<?> ci) {ApproachTiming.end("inspect:"+level.dimension().location());}
}
