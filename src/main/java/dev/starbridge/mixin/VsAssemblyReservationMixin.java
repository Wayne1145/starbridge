package dev.starbridge.mixin;

import dev.starbridge.compat.vs.ReservedShipAllocation;
import org.joml.Vector3ic;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Redirect;

@Pseudo
@Mixin(targets = "org.valkyrienskies.mod.common.assembly.ShipAssembler", remap = false)
abstract class VsAssemblyReservationMixin {
    @Coerce
    @Redirect(method = "assembleToShipFull", at = @At(value = "INVOKE",
            target = "Lorg/valkyrienskies/core/internal/world/VsiServerShipWorld;createNewShipAtBlock(Lorg/joml/Vector3ic;ZDLjava/lang/String;)Lorg/valkyrienskies/core/api/ships/ServerShip;"),
            require = 1)
    private static Object starbridge$allocatePreparedShip(@Coerce Object world, Vector3ic center, boolean split,
                                                          double scale, String dimension) {
        return ReservedShipAllocation.allocate(world, center, split, scale, dimension);
    }
}
