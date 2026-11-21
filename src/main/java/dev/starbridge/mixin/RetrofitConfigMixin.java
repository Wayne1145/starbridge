package dev.starbridge.mixin;

import dev.starbridge.compat.retrofit.SharedRetrofitSettings;
import net.minecraftforge.fml.loading.FMLPaths;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Map;

/** Retrofit 0.6.2 compares the raw client-generated HUD and splash files when a new player joins. */
@Pseudo
@Mixin(targets = "retrofit.forge.world.LegacyConfigSync", remap = false)
abstract class RetrofitConfigMixin {
    @Inject(method = "fingerprints()Ljava/util/Map;", at = @At("RETURN"), cancellable = true, require = 0, remap = false)
    private static void starbridge$clientLayout(CallbackInfoReturnable<Map<String,String>> cir) {
        cir.setReturnValue(SharedRetrofitSettings.compare(cir.getReturnValue(), FMLPaths.GAMEDIR.get().resolve("retrofit")));
    }
}
