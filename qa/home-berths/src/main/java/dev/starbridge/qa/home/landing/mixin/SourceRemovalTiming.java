package dev.starbridge.qa.home.landing.mixin;

import dev.starbridge.qa.home.landing.ApproachTiming;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Times the two source loops of the pinned VS assembler in the test fixture. */
@Pseudo
@Mixin(targets = "org.valkyrienskies.mod.common.assembly.ShipAssembler", remap = false)
abstract class SourceRemovalTiming {
    private static final String ITERATOR = "Ljava/util/Set;iterator()Ljava/util/Iterator;";
    private static final String REMOVAL = "vsSourceRemoval";
    private static final String BARRIERS = "vsSourceBarriers";
    private static final String CLEAR = "vsSourceClear";

    @Inject(method = "moveBlocksFromTo", at = @At(value = "INVOKE", target = ITERATOR, ordinal = 0), require = 1)
    private static void starbridge$beforeBarriers(CallbackInfoReturnable<Object> callback) {
        ApproachTiming.startAssemblyPhase(REMOVAL);
        ApproachTiming.startAssemblyPhase(BARRIERS);
    }

    @Inject(method = "moveBlocksFromTo", at = @At(value = "INVOKE", target = ITERATOR, ordinal = 1), require = 1)
    private static void starbridge$beforeClear(CallbackInfoReturnable<Object> callback) {
        ApproachTiming.end(BARRIERS);
        ApproachTiming.startAssemblyPhase(CLEAR);
    }

    @Inject(method = "moveBlocksFromTo", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/levelgen/structure/templatesystem/StructurePlaceSettings;<init>()V"), require = 1)
    private static void starbridge$afterClear(CallbackInfoReturnable<Object> callback) {
        ApproachTiming.end(CLEAR);
        ApproachTiming.end(REMOVAL);
    }
}
