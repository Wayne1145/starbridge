package dev.starbridge.mixin;

import com.mojang.datafixers.util.Either;
import dev.starbridge.compat.vs.AssemblyChunkFutures;
import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkStatus;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.concurrent.CompletableFuture;

@Mixin(value = ServerChunkCache.class, remap = false)
abstract class VsAssemblyChunkMixin {
    private static final String FUTURE = "(IILnet/minecraft/world/level/chunk/ChunkStatus;Z)Ljava/util/concurrent/CompletableFuture;";

    @Inject(method = {"m_8456_" + FUTURE, "getChunkFutureMainThread" + FUTURE}, at = @At("HEAD"),
            cancellable = true, require = 1)
    private void starbridge$reuseAssemblyChunk(int x, int z, ChunkStatus status, boolean create,
            CallbackInfoReturnable<CompletableFuture<Either<ChunkAccess, ChunkHolder.ChunkLoadingFailure>>> callback) {
        if (!create || status != ChunkStatus.FULL) return;
        var chunk = AssemblyChunkFutures.find((ServerChunkCache) (Object) this, x, z);
        if (chunk != null) callback.setReturnValue(chunk);
    }

    @Inject(method = {"m_8456_" + FUTURE, "getChunkFutureMainThread" + FUTURE}, at = @At("RETURN"), require = 1)
    private void starbridge$rememberAssemblyChunk(int x, int z, ChunkStatus status, boolean create,
            CallbackInfoReturnable<CompletableFuture<Either<ChunkAccess, ChunkHolder.ChunkLoadingFailure>>> callback) {
        if (create && status == ChunkStatus.FULL) {
            AssemblyChunkFutures.remember((ServerChunkCache) (Object) this, x, z, callback.getReturnValue());
        }
    }

    @Inject(method = {"m_8488_()V", "clearCache()V"}, at = @At("HEAD"), require = 1)
    private void starbridge$clearAssemblyChunks(CallbackInfo callback) {
        AssemblyChunkFutures.invalidate((ServerChunkCache) (Object) this);
    }
}
