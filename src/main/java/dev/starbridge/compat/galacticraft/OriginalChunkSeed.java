package dev.starbridge.compat.galacticraft;

/** Supplies the terrain seed to the short-lived chunk view for this generation pass. */
public interface OriginalChunkSeed {
    void starbridge$setTerrainSeed(long seed);
}
