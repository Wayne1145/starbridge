package dev.starbridge.qa.berth.mixin;

import dev.starbridge.orbit.OrbitWire;
import dev.starbridge.qa.berth.FirstVisit;

import net.minecraft.server.level.ServerPlayer;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = OrbitWire.class, remap = false)
abstract class SurveyReplies {
    @Inject(
            method = "send(Lnet/minecraft/server/level/ServerPlayer;Ljava/lang/Object;)V",
            at = @At("HEAD"),
            remap = false)
    private static void observe(ServerPlayer pilot, Object response, CallbackInfo event) {
        FirstVisit.response(pilot, response);
    }
}
