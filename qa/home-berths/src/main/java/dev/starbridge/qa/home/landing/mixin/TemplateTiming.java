package dev.starbridge.qa.home.landing.mixin;

import dev.starbridge.qa.home.landing.ApproachTiming;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(value = StructureTemplate.class, remap = false)
abstract class TemplateTiming {
    @Inject(method = {"m_230328_", "placeInWorld"}, at = @At("HEAD"), require = 1)
    private void starbridge$enterTemplate(CallbackInfoReturnable<Boolean> callback) {
        ApproachTiming.enterTemplate();
    }

    @Inject(method = {"m_230328_", "placeInWorld"}, at = @At("RETURN"), require = 1)
    private void starbridge$leaveTemplate(CallbackInfoReturnable<Boolean> callback) {
        ApproachTiming.leaveTemplate();
    }
}
