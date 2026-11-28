package dev.starbridge.survivalqa;

import baritone.api.BaritoneAPI;
import baritone.api.pathing.goals.GoalBlock;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;

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

    private static boolean matches(net.minecraft.world.level.block.state.BlockState block, String item) {
        if (item.startsWith("#")) {
            var tag = net.minecraft.tags.TagKey.create(net.minecraft.core.registries.Registries.BLOCK,
                    new net.minecraft.resources.ResourceLocation(item.substring(1)));
            return block.is(tag);
        }
        String[] identity = item.split("#", 2);
        if (!BuiltInRegistries.BLOCK.getKey(block.getBlock()).toString().equals(identity[0])) return false;
        return identity.length == 1 || block.getValues().entrySet().stream().anyMatch(entry ->
                entry.getKey().getName().equals("meta") && entry.getValue().toString().equals(identity[1]));
    }

    static boolean tick(Minecraft game, String item, BlockPos destination) {
        if (game.screen != null) { game.screen.onClose(); return false; }
        var existing = game.level.getBlockState(destination);
        if (matches(existing, item)) {
            if (destination.equals(pending)) Expedition.log("Observed placed " + item + " at " + destination.toShortString());
            else Expedition.log("Found existing " + item + " at " + destination.toShortString());
            pending = null;
            return true;
        }
        if (!existing.canBeReplaced() || !ExpeditionClient.select(game,item)) return false;
        var eye = game.player.getEyePosition();
        var faces = item.equals("minecraft:torch") || item.endsWith("_door") ? new Direction[]{Direction.UP} : Direction.values();
        for (Direction face : faces) {
            var support = destination.relative(face.getOpposite());
            var supportState = game.level.getBlockState(support);
            if (supportState.getCollisionShape(game.level, support).isEmpty()) continue;
            var outline = supportState.getShape(game.level, support, CollisionContext.of(game.player));
            for (var box : outline.toAabbs()) {
                Vec3 point = facePoint(box, face).add(support.getX(), support.getY(), support.getZ());
                if (eye.distanceTo(point) > 4.4) continue;
                var hit = ExpeditionClient.ray(game, point);
                if (hit.getType() != HitResult.Type.BLOCK) continue;
                if (hit.getBlockPos().equals(support) && hit.getDirection() == face) {
                    pending = destination;
                    ExpeditionClient.use(game, hit);
                    return false;
                }
                // Only clear soft vegetation in the designated footprint, using the normal break action.
                if (hit.getBlockPos().equals(destination) && !existing.isAir() && existing.getFluidState().isEmpty()
                        && existing.getDestroySpeed(game.level, destination) == 0) {
                    game.gameMode.startDestroyBlock(destination, hit.getDirection());
                    game.player.swing(InteractionHand.MAIN_HAND);
                    return false;
                }
            }
        }
        return false;
    }

    private static Vec3 facePoint(AABB box, Direction face) {
        var center = box.getCenter();
        var axis = face.getAxis();
        double edge = face.getAxisDirection() == Direction.AxisDirection.POSITIVE
                ? box.max(axis) - .0005 : box.min(axis) + .0005;
        return center.with(axis, edge);
    }
}
