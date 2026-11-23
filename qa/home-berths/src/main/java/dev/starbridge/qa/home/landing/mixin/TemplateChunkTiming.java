package dev.starbridge.qa.home.landing.mixin;

import dev.starbridge.qa.home.landing.ApproachTiming;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.world.level.chunk.ChunkAccess;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Group;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(value = ServerChunkCache.class, remap = false)
abstract class TemplateChunkTiming {
    private static final String LOOKUP = "(IILnet/minecraft/world/level/chunk/ChunkStatus;Z)Lnet/minecraft/world/level/chunk/ChunkAccess;";

    private static final String EXECUTOR = "Lnet/minecraft/server/level/ServerChunkCache$MainThreadExecutor;";
    private static final String WAIT_FOR_CHUNK = "(Ljava/util/function/BooleanSupplier;)V";

    @Group(name = "enterManagedChunkTasks", min = 1, max = 1)
    @Inject(method = "m_7587_" + LOOKUP, at = @At(value = "INVOKE", target = EXECUTOR + "m_18701_" + WAIT_FOR_CHUNK), require = 0)
    private void starbridge$enterProductionWait(CallbackInfoReturnable<ChunkAccess> callback) {
        ApproachTiming.enterManagedChunkTasks();
    }

    @Group(name = "leaveManagedChunkTasks", min = 1, max = 1)
    @Inject(method = "m_7587_" + LOOKUP, at = @At(value = "INVOKE", target = EXECUTOR + "m_18701_" + WAIT_FOR_CHUNK,
            shift = At.Shift.AFTER), require = 0)
    private void starbridge$leaveProductionWait(CallbackInfoReturnable<ChunkAccess> callback) {
        ApproachTiming.leaveManagedChunkTasks();
    }

    @Group(name = "enterManagedChunkTasks", min = 1, max = 1)
    @Inject(method = "getChunk" + LOOKUP, at = @At(value = "INVOKE", target = EXECUTOR + "managedBlock" + WAIT_FOR_CHUNK), require = 0)
    private void starbridge$enterDevelopmentWait(CallbackInfoReturnable<ChunkAccess> callback) {
        ApproachTiming.enterManagedChunkTasks();
    }

    @Group(name = "leaveManagedChunkTasks", min = 1, max = 1)
    @Inject(method = "getChunk" + LOOKUP, at = @At(value = "INVOKE", target = EXECUTOR + "managedBlock" + WAIT_FOR_CHUNK,
            shift = At.Shift.AFTER), require = 0)
    private void starbridge$leaveDevelopmentWait(CallbackInfoReturnable<ChunkAccess> callback) {
        ApproachTiming.leaveManagedChunkTasks();
    }

    @Inject(method = {"m_7587_" + LOOKUP, "getChunk" + LOOKUP}, at = @At("HEAD"), require = 1)
    private void starbridge$enterLookup(CallbackInfoReturnable<ChunkAccess> callback) {
        ApproachTiming.enterChunkLookup();
    }

    @Inject(method = {"m_7587_" + LOOKUP, "getChunk" + LOOKUP}, at = @At("RETURN"), require = 1)
    private void starbridge$leaveLookup(CallbackInfoReturnable<ChunkAccess> callback) {
        ApproachTiming.leaveChunkLookup();
    }
}
