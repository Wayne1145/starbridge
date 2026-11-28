package dev.starbridge.qa.integrated.mixin;
import dev.starbridge.qa.integrated.FlightProfile;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.*;
@Mixin(targets="dev.starbridge.orbit.OrbitWire",remap=false)
abstract class WireProfile {
    @Inject(method="encodeHull",at=@At("HEAD"),remap=false) private static void startHullEncode(CallbackInfo c){FlightProfile.enter("hullEncode");}
    @Inject(method="encodeHull",at=@At("RETURN"),remap=false) private static void endHullEncode(CallbackInfo c){FlightProfile.leave("hullEncode");}
    @Inject(method="decodeHull",at=@At("HEAD"),remap=false) private static void startHullDecode(CallbackInfoReturnable<?> c){FlightProfile.enter("hullDecode");}
    @Inject(method="decodeHull",at=@At("RETURN"),remap=false) private static void endHullDecode(CallbackInfoReturnable<?> c){FlightProfile.leave("hullDecode");}
    @Inject(method="encodeFrame",at=@At("HEAD"),remap=false) private static void startFrameEncode(CallbackInfo c){FlightProfile.enter("frameEncode");}
    @Inject(method="encodeFrame",at=@At("RETURN"),remap=false) private static void endFrameEncode(CallbackInfo c){FlightProfile.leave("frameEncode");}
    @Inject(method="decodeFrame",at=@At("HEAD"),remap=false) private static void startFrameDecode(CallbackInfoReturnable<?> c){FlightProfile.enter("frameDecode");}
    @Inject(method="decodeFrame",at=@At("RETURN"),remap=false) private static void endFrameDecode(CallbackInfoReturnable<?> c){FlightProfile.leave("frameDecode");}
}
