package dev.starbridge.survivalqa;

import baritone.api.BaritoneAPI;
import baritone.api.pathing.goals.GoalBlock;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/** Position a block with the selected survival item, an actual supporting face and a clear ray in reach. */
final class PlaceAt {
    private static BlockPos pending;
    private static long nextWalk;
    private PlaceAt() {}

    static boolean stand(Minecraft game, BlockPos destination) {
        if (game.player.blockPosition().equals(destination)) return true;
        if (game.level.getGameTime() >= nextWalk) {
            nextWalk = game.level.getGameTime() + 40;
            BaritoneAPI.getProvider().getPrimaryBaritone().getCustomGoalProcess().setGoalAndPath(new GoalBlock(destination));
        }
        return false;
    }

    static boolean tick(Minecraft game, String item, BlockPos destination) {
        if (game.screen != null) { game.screen.onClose(); return false; }
        var existing = game.level.getBlockState(destination);
        if (BuiltInRegistries.BLOCK.getKey(existing.getBlock()).toString().equals(item)) {
            if (destination.equals(pending)) Expedition.log("Observed placed " + item + " at " + destination.toShortString());
            else Expedition.log("Found existing " + item + " at " + destination.toShortString());
            pending = null;
            return true;
        }
        if (!existing.canBeReplaced() || !ExpeditionClient.select(game,item)) return false;
        var eye = game.player.getEyePosition();
        for (Direction face : Direction.values()) {
            var support = destination.relative(face.getOpposite());
            if (game.level.getBlockState(support).getCollisionShape(game.level,support).isEmpty()) continue;
            Vec3 point = Vec3.atCenterOf(support).add(Vec3.atLowerCornerOf(face.getNormal()).scale(.499));
            if (eye.distanceTo(point) > 4.4) continue;
            var hit = ExpeditionClient.ray(game,point);
            if (hit.getType() == HitResult.Type.BLOCK && hit.getBlockPos().equals(support) && hit.getDirection() == face) {
                pending = destination;
                ExpeditionClient.use(game, hit);
                return false;
            }
            // Only clear soft vegetation in the designated footprint, using the normal break action.
            if (hit.getType() == HitResult.Type.BLOCK && hit.getBlockPos().equals(destination)
                    && !existing.isAir() && existing.getFluidState().isEmpty() && existing.getDestroySpeed(game.level,destination) == 0) {
                game.gameMode.startDestroyBlock(destination, hit.getDirection());
                game.player.swing(InteractionHand.MAIN_HAND);
                return false;
            }
        }
        return false;
    }
}
