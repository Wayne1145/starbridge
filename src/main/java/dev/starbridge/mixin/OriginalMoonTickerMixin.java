package dev.starbridge.mixin;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Block;
import net.minecraftforge.registries.ForgeRegistries;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * The original Moon ground and dungeon spawner share a block ID. Only original tile states need a
 * ticker.
 */
@Pseudo
@Mixin(targets = "retrofit.forge.world.LegacyBlock$WithTile", remap = false)
abstract class OriginalMoonTickerMixin {
    @Unique
    private static final ResourceLocation starbridge$moonBlock =
            ResourceLocation.parse("galacticraftcore:tile.moonblock");

    /** Retrofit 0.6.2-beta caches 0 = unknown, 1 = tick, 2 = no ticker per block and metadata. */
    @Shadow @Final private byte[] ticking;

    @Inject(method = "ticks(I)Z", at = @At("HEAD"), require = 1, remap = false)
    private void starbridge$checkMoonMetadata(
            int metadata, CallbackInfoReturnable<Boolean> callback) {
        if (ticking[metadata] != 0
                || !starbridge$moonBlock.equals(
                        ForgeRegistries.BLOCKS.getKey((Block) (Object) this))) return;
        try {
            Object original = getClass().getMethod("legacyBlock").invoke(this);
            if (Boolean.FALSE.equals(
                    original.getClass()
                            .getMethod("hasTileEntity", int.class)
                            .invoke(original, metadata))) {
                ticking[metadata] = 2;
            }
        } catch (ReflectiveOperationException ignored) {
            // Leave the decision to Retrofit when the installed original does not expose the
            // predicate.
        }
    }
}
