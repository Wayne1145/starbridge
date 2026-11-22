package dev.starbridge.qa.habitat;

import dev.starbridge.client.shipyard.ShipyardScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.commands.arguments.EntityAnchorArgument.Anchor;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import java.util.List;
import java.util.Map;

/** Uses the real assembler, then fills the hole through a normal survival placement. */
final class DockFloorJourney {
    private static final String PREFIX = "shipyard.starbridge.";
    private static int step, next;

    static void tick(Minecraft game, int ticks) throws Exception {
        if (ticks < next || step == 8) return;
        next = ticks + 10;
        var page = game.screen;
        try {
            switch (step) {
                case 0, 5 -> {
                    if (!(page instanceof ShipyardScreen)) {
                        if (page == null && game.level.getBlockState(DockFloorCamp.CONSOLE).is(DockFloorCamp.original("rocketbuilder"))) {
                            use(game, DockFloorCamp.CONSOLE, Vec3.atCenterOf(DockFloorCamp.CONSOLE));
                        }
                        return;
                    }
                    if (step == 0) HabitatClient.log("Used original assembler beside the incomplete pad");
                    else HabitatClient.log("Reopened original assembler after placing the spare pad");
                }
                case 1 -> {
                    if (active(page, "scan") || active(page, "down")) throw new IllegalStateException("Expected floor and disabled scan");
                    HabitatClient.snap(game, "dock-floor-gap");
                    HabitatClient.log("Saw disabled scan on the launchpad floor");
                    if (!HabitatClient.click(page, PREFIX + "rotate")) throw new IllegalStateException("No rotate button");
                }
                case 2 -> {
                    HabitatClient.snap(game, "dock-floor-rotated");
                    page.onClose();
                }
                case 3 -> {
                    if (page != null) return;
                    if (!game.player.getMainHandItem().is(DockFloorCamp.original("launchpad").asItem()))
                        throw new IllegalStateException("Missing spare launchpad");
                    var gap = DockFloorCamp.GAP;
                    use(game, gap.below(), new Vec3(gap.getX() + .5, gap.getY() - .01, gap.getZ() + .5));
                }
                case 4 -> {
                    if (!game.level.getBlockState(DockFloorCamp.GAP).is(DockFloorCamp.original("launchpad"))) return;
                    if (!game.player.getMainHandItem().isEmpty()) return;
                    HabitatClient.log("Placed the carried launchpad in the marked cell");
                }
                case 6 -> {
                    if (!active(page, "scan")) return;
                    if (!HabitatClient.click(page, PREFIX + "down")) throw new IllegalStateException("No live floor layer");
                }
                case 7 -> {
                    if (active(page, "down") || !active(page, "scan")) throw new IllegalStateException("Expected repaired floor with available scan");
                    HabitatClient.snap(game, "dock-floor-repaired");
                    HabitatClient.log("Saw the repaired floor and active scan");
                    GardenCamp.save("dock-floor-client", Map.of("pass", true, "states", List.of("gap", "rotated", "repaired"),
                            "scanBefore", false, "scanAfter", true, "spentPad", true));
                }
                default -> throw new IllegalStateException("Unknown dock floor step");
            }
            step++;
        } catch (RuntimeException | AssertionError failure) {
            GardenCamp.save("dock-floor-client", Map.of("pass", false, "step", step, "error", failure.toString()));
            step = 8;
        }
    }

    private static boolean active(Screen screen, String key) {
        for (var widget : screen.children()) if (widget instanceof Button button
                && button.getMessage().getContents() instanceof TranslatableContents text
                && text.getKey().equals(PREFIX + key)) return button.visible && button.active;
        throw new IllegalStateException("Missing dock control " + key);
    }

    private static void use(Minecraft game, BlockPos target, Vec3 aim) {
        game.player.lookAt(Anchor.EYES, aim);
        var eye = game.player.getEyePosition();
        var hit = game.level.clip(new ClipContext(eye, aim, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, game.player));
        if (hit.getType() != HitResult.Type.BLOCK || !hit.getBlockPos().equals(target) || eye.distanceTo(hit.getLocation()) > 4.5
                || target.equals(DockFloorCamp.GAP.below()) && hit.getDirection() != Direction.UP)
            throw new IllegalStateException("Unexpected crosshair hit while reaching " + target + ": " + hit.getBlockPos());
        game.gameMode.useItemOn(game.player, InteractionHand.MAIN_HAND, hit);
        game.player.swing(InteractionHand.MAIN_HAND);
    }
}
