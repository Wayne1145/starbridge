package dev.starbridge.mixin;

import dev.starbridge.compat.galacticraft.OriginalChunkSeed;
import dev.starbridge.compat.galacticraft.OriginalTerrainSeeds;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

@Pseudo
@Mixin(targets = "retrofit.forge.world.LegacyDimensions$Generator", remap = false)
abstract class OriginalGeneratorSeedMixin {
    @Shadow @Final private int dimension;

    @ModifyArg(method = {"fillFromNoise", "m_213974_"}, at = @At(value = "INVOKE",
            target = "Lretrofit/forge/world/LegacyBridge;call(Ljava/lang/String;[Ljava/lang/Object;)Ljava/lang/Object;"), index = 1, require = 1, remap = false)
    private Object[] starbridge$supplyTerrainSeed(Object[] arguments) {
        if (arguments[0] instanceof OriginalChunkSeed chunk) chunk.starbridge$setTerrainSeed(OriginalTerrainSeeds.seed(dimension));
        return arguments;
    }
}
