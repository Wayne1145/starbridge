package dev.starbridge.flightqa.mixin;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.*;
import net.minecraft.world.phys.BlockHitResult;

import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.nio.file.*;

/**
 * Records a long unmodified traversal before its first chunk access; never alters the ray or loads
 * a chunk.
 */
@Pseudo
@Mixin(targets = "org.valkyrienskies.mod.common.world.RaycastUtilsKt", remap = false)
abstract class ReturnRayProbe {
    @Unique private static int starbridge$sampled;

    @Inject(
            method =
                    "vanillaClip(Lnet/minecraft/world/level/BlockGetter;Lnet/minecraft/world/level/ClipContext;)Lnet/minecraft/world/phys/BlockHitResult;",
            at = @At("HEAD"),
            require = 1,
            remap = false)
    private static void qa$watch(
            BlockGetter level, ClipContext ray, CallbackInfoReturnable<BlockHitResult> cir) {
        if (!(level instanceof ServerLevel world)
                || ray.getFrom().distanceToSqr(ray.getTo()) < 128 * 128
                || starbridge$sampled > 80
                || !Files.isRegularFile(Path.of("starbridge-joined.fixture"))) return;
        starbridge$sampled++;
        try {
            Files.writeString(
                    Path.of("long-return-rays.log"),
                    world.dimension().location()
                            + " tick="
                            + world.getServer().getTickCount()
                            + " from="
                            + ray.getFrom()
                            + " to="
                            + ray.getTo()
                            + " "
                            + java.util.Arrays.toString(Thread.currentThread().getStackTrace())
                            + "\n",
                    StandardOpenOption.CREATE,
                    StandardOpenOption.APPEND);
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
