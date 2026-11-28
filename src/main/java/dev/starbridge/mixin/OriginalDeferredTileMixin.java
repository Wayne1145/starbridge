package dev.starbridge.mixin;

import java.util.Map;
import net.minecraft.core.BlockPos;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Keep the tile selected by the original generator when its block is queued for a neighbouring chunk. */
@Pseudo
@Mixin(targets = "retrofit.forge.world.RegionHostWorld", remap = false)
abstract class OriginalDeferredTileMixin {
    @Shadow @Final private boolean deep;
    @Shadow @Final private int shift;
    @Shadow @Final private Map<BlockPos, Object> tiles;
    @Shadow protected abstract boolean writable(int x, int y, int z);

    @Inject(method = "setLegacyTileEntity", at = @At("HEAD"), require = 1, remap = false)
    private void starbridge$replaceQueuedTile(int x, int y, int z, Object replacement, CallbackInfo callback) {
        if (!deep && replacement != null && !writable(x, y - shift, z)) {
            // A deferred setBlock recorded an initial tile here. flushTiles will attach its replacement to the same entry.
            tiles.replace(new BlockPos(x, y - shift, z), replacement);
        }
    }
}
