package dev.starbridge.mixin;

import dev.starbridge.compat.galacticraft.OriginalChunkSeed;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Unique;

@Pseudo
@Mixin(targets = "retrofit.forge.world.LegacyDimensions$ChunkView", remap = false)
abstract class OriginalChunkSeedMixin implements OriginalChunkSeed {
    @Unique private long starbridge$terrainSeed;
    @Override public void starbridge$setTerrainSeed(long seed) { starbridge$terrainSeed = seed; }
    public long getSeed() { return starbridge$terrainSeed; }
}
