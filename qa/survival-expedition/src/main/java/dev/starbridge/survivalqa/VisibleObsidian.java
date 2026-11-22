package dev.starbridge.survivalqa;

import baritone.api.BaritoneAPI;
import baritone.api.pathing.goals.GoalGetToBlock;
import baritone.api.pathing.goals.GoalXZ;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/** Ordinary surface walk. Only approach a block after a clear eye-height ray reaches its face. */
final class VisibleObsidian {
    private static final Set<BlockPos> visitedChests = new HashSet<>();
    private static BlockPos waypoint, lastTarget, lastChest;
    private static int leg, nextScan, nextPath;
    private static String kind;
    private static final Path ROUTE = Path.of("survival-scout-route.json");
    private static boolean restored;
    private static int nextChest;
    private VisibleObsidian() {}

    static boolean search(Minecraft game, int tick) throws java.io.IOException {
        if (!restored) {
            restored = true;
            if (Files.isRegularFile(ROUTE)) {
                var route = com.google.gson.JsonParser.parseString(Files.readString(ROUTE)).getAsJsonObject();
                waypoint = new BlockPos(route.get("x").getAsInt(), game.player.getBlockY(), route.get("z").getAsInt());
                leg = route.get("leg").getAsInt();
            }
        }
        var feet = game.player.blockPosition();
        if (ExpeditionClient.count(game, "minecraft:obsidian") > 0) return found("item", feet);
        if (ExpeditionClient.count(game, "minecraft:lava_bucket") > 0) return found("lava_bucket", feet);
        if (game.player.containerMenu instanceof ChestMenu chest) {
            for (int slot = 0; slot < chest.getRowCount() * 9; slot++) {
                if (chest.getSlot(slot).getItem().is(Items.OBSIDIAN)) {
                    game.gameMode.handleInventoryMouseClick(chest.containerId, slot, 0, ClickType.QUICK_MOVE, game.player);
                    return false;
                }
            }
            if (lastChest != null) visitedChests.add(lastChest);
            lastChest = null;
            lastTarget = null;
            game.player.closeContainer();
            return false;
        }
        if (game.screen != null) { game.screen.onClose(); return false; }
        if (tick >= nextScan) {
            nextScan = tick + 20;
            scan(game);
        }
        if (lastTarget != null) {
            var pos = lastTarget;
            if (kind.equals("lava") && FieldActions.fillBucket(game, tick, true)) return found("lava_bucket", pos);
            var hit = sight(game, pos);
            if (hit != null && game.player.getEyePosition().distanceTo(hit.getLocation()) <= 4.3) {
                if (kind.equals("base") && usableBase(game, pos)) return found("natural_base", pos);
                if (kind.equals("chest") && (lastChest == null || tick >= nextChest)) {
                    nextChest = tick + 40;
                    lastChest = pos;
                    game.player.lookAt(net.minecraft.commands.arguments.EntityAnchorArgument.Anchor.EYES, hit.getLocation());
                    game.gameMode.useItemOn(game.player, InteractionHand.MAIN_HAND, hit);
                    game.player.swing(InteractionHand.MAIN_HAND);
                    nextScan = tick + 16;
                    return false;
                }
            }
            if (tick >= nextPath) {
                nextPath = tick + 40;
                BaritoneAPI.getProvider().getPrimaryBaritone().getCustomGoalProcess().setGoalAndPath(new GoalGetToBlock(pos));
            }
            return false;
        }
        if (waypoint == null || Math.abs(feet.getX() - waypoint.getX()) + Math.abs(feet.getZ() - waypoint.getZ()) < 5) {
            var dir = new Direction[]{Direction.EAST, Direction.NORTH, Direction.WEST, Direction.SOUTH}[leg % 4];
            int length = 64 * (1 + leg / 2);
            waypoint = (waypoint == null ? feet : waypoint).relative(dir, length);
            leg++;
            Expedition.save(ROUTE.toString(), Map.of("x",waypoint.getX(),"z",waypoint.getZ(),"leg",leg));
            nextPath = tick;
        }
        if (tick >= nextPath) {
            nextPath = tick + 80;
            BaritoneAPI.getProvider().getPrimaryBaritone().getCustomGoalProcess().setGoalAndPath(new GoalXZ(waypoint.getX(), waypoint.getZ()));
        }
        return false;
    }

    private static void scan(Minecraft game) {
        var center = game.player.blockPosition();
        var eye = game.player.getEyePosition();
        double best = 28 * 28;
        BlockPos chosen = null;
        String candidateKind = null;
        for (BlockPos pos : BlockPos.betweenClosed(center.offset(-26, -8, -26), center.offset(26, 10, 26))) {
            double distance = eye.distanceToSqr(Vec3.atCenterOf(pos));
            if (distance >= best) continue;
            var state = game.level.getBlockState(pos);
            String type;
            if (state.is(Blocks.OBSIDIAN) && usableBase(game, pos)) type = "base";
            else if (state.getFluidState().is(FluidTags.LAVA) && state.getFluidState().isSource()) type = "lava";
            else if (state.is(Blocks.CHEST) && !visitedChests.contains(pos)
                    && !(pos.getX() >= -100 && pos.getX() < -70 && pos.getZ() >= -35 && pos.getZ() < -8)) type = "chest";
            else continue;
            if (sight(game, pos) == null) continue;
            chosen = pos.immutable(); candidateKind = type; best = distance;
        }
        if (chosen != null && !chosen.equals(lastTarget)) {
            Expedition.log("Saw surface " + candidateKind + " at " + chosen.toShortString() + " from " + center.toShortString());
            nextPath = 0;
        }
        lastTarget = chosen; kind = candidateKind;
    }

    private static BlockHitResult sight(Minecraft game, BlockPos pos) {
        return ray(game,pos,ClipContext.Fluid.SOURCE_ONLY);
    }

    private static BlockHitResult ray(Minecraft game, BlockPos pos, ClipContext.Fluid fluid) {
        var target = Vec3.atCenterOf(pos);
        var hit = game.level.clip(new ClipContext(game.player.getEyePosition(), target, ClipContext.Block.OUTLINE, fluid, game.player));
        return hit.getType() == HitResult.Type.BLOCK && hit.getBlockPos().equals(pos) ? hit : null;
    }

    private static boolean usableBase(Minecraft game, BlockPos pos) {
        return game.level.getBlockState(pos.above()).isAir() && game.level.getBlockState(pos.above(2)).isAir();
    }

    private static boolean found(String result, BlockPos pos) {
        Expedition.save("survival-obsidian-site.json", Map.of("kind",result,"x",pos.getX(),"y",pos.getY(),"z",pos.getZ()));
        Expedition.log("Reached " + result + " for a press base at " + pos.toShortString());
        return true;
    }
}
