package dev.starbridge.qa.lunar;

import net.minecraft.server.MinecraftServer;
import net.minecraftforge.server.ServerLifecycleHooks;
import org.spongepowered.asm.mixin.Mixin;

/** Use the same seed as ServerLevel#getSeed when a legacy generator reads the chunk in its terrain phase. */
@Mixin(targets = "retrofit.forge.world.LegacyDimensions$ChunkView", remap = false)
abstract class OriginalSeed {
    public long getSeed() {
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server == null) {
            throw new IllegalStateException("No host server while generating a legacy chunk");
        }
        return server.getWorldData().worldGenOptions().seed();
    }
}
