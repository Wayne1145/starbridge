package dev.starbridge.qa.home.landing.mixin;

import dev.starbridge.qa.home.landing.ApproachTiming;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.world.level.chunk.ChunkAccess;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(value = ServerChunkCache.class, remap = false)
abstract class TemplateChunkTiming {
    private static final String LOOKUP = "(IILnet/minecraft/world/level/chunk/ChunkStatus;Z)Lnet/minecraft/world/level/chunk/ChunkAccess;";

    @Inject(method = {"m_7587_" + LOOKUP, "getChunk" + LOOKUP}, at = @At("HEAD"), require = 1)
    private void starbridge$enterLookup(CallbackInfoReturnable<ChunkAccess> callback) {
        ApproachTiming.enterChunkLookup();
    }

    @Inject(method = {"m_7587_" + LOOKUP, "getChunk" + LOOKUP}, at = @At("RETURN"), require = 1)
    private void starbridge$leaveLookup(CallbackInfoReturnable<ChunkAccess> callback) {
        ApproachTiming.leaveChunkLookup();
    }
}
