package dev.starbridge.qa.habitat;

import dev.starbridge.client.shipyard.ShipyardScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.commands.arguments.EntityAnchorArgument.Anchor;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/** Operates the same controls a survival player uses, on the hosted display only. */
final class ForbiddenDockJourney {
    private static final String PREFIX = "shipyard.starbridge.";
    private static int step, next;
    private static boolean startedBreaking;

    static void tick(Minecraft game, int tick) throws Exception {
        if (step == 12 || tick < next || !Files.isRegularFile(Path.of("forbidden-camp-ready"))) return;
        next = tick + (step == 7 ? 1 : 10);
        var page = game.screen;
        try {
            switch (step) {
                case 0, 8 -> {
                    if (!(page instanceof ShipyardScreen)) {
                        if (page == null && game.level.getBlockState(DockFloorCamp.CONSOLE).is(DockFloorCamp.original("rocketbuilder"))) {
                            var hit = aim(game, DockFloorCamp.CONSOLE);
                            game.gameMode.useItemOn(game.player, InteractionHand.MAIN_HAND, hit);
                            game.player.swing(InteractionHand.MAIN_HAND);
                        }
                        return;
                    }
                    HabitatClient.log(step == 0 ? "Used powered original assembler with a configured chest aboard" : "Reopened original assembler after chopping the marked chest");
                }
                case 1, 9 -> { if (!HabitatClient.click(page, PREFIX + "scan")) return; }
                case 2 -> {
                    if (!(page instanceof ShipyardScreen) || !active(page,"down") || active(page,"assemble")) return;
                    // A transition from the starting deck to deck three unlocks a second downward move.
                    if (!game.level.getBlockState(ForbiddenDockCamp.CHEST).is(Blocks.CHEST)) throw new IllegalStateException("Expected excluded chest");
                    if (!HabitatClient.click(page,PREFIX + "down")) return;
                    if (!active(page,"locate_block")) {
                        if (!HabitatClient.click(page,PREFIX + "up")) throw new IllegalStateException("Could not revisit initial deck");
                        return;
                    }
                    HabitatClient.log("Moved one deck below the configured chest and saw Show block");
                }
                case 3 -> {
                    HabitatClient.snap(game,"dock-forbidden-lower");
                    if (!HabitatClient.click(page,PREFIX + "locate_block")) throw new IllegalStateException("Missing locator");
                }
                case 4 -> {
                    if (active(page,"locate_block") || active(page,"assemble")) throw new IllegalStateException("Expected marked live deck");
                    HabitatClient.snap(game,"dock-forbidden-chest");
                    if (!HabitatClient.click(page,PREFIX + "rotate")) throw new IllegalStateException("No rotate button");
                }
                case 5 -> { HabitatClient.snap(game,"dock-forbidden-rotated"); }
                case 6 -> { page.onClose(); }
                case 7 -> {
                    if (page != null) return;
                    if (!game.level.getBlockState(ForbiddenDockCamp.CHEST).isAir()) {
                        var hit = aim(game, ForbiddenDockCamp.CHEST);
                        if (!startedBreaking) { game.gameMode.startDestroyBlock(hit.getBlockPos(),hit.getDirection()); startedBreaking = true; }
                        else game.gameMode.continueDestroyBlock(hit.getBlockPos(),hit.getDirection());
                        game.player.swing(InteractionHand.MAIN_HAND);
                        return;
                    }
                    game.gameMode.stopDestroyBlock();
                    HabitatClient.log("Chopped the marked chest through the survival attack action");
                }
                case 10 -> {
                    if (!active(page,"locate") || active(page,"assemble")) return;
                    HabitatClient.snap(game,"dock-forbidden-next");
                    HabitatClient.log("The next original scan offers the missing navigation part");
                }
                case 11 -> GardenCamp.save("forbidden-dock-client", Map.of("pass",true,"mode","survival","states",List.of("lower","chest","rotated","next")));
                default -> throw new IllegalStateException("Unexpected journey step");
            }
            step++;
        } catch (RuntimeException | AssertionError failure) {
            GardenCamp.save("forbidden-dock-client",Map.of("pass",false,"step",step,"error",failure.toString()));
            step = 12;
        }
    }

    private static boolean active(Screen screen, String key) {
        if (screen == null) return false;
        for (var widget : screen.children()) if (widget instanceof Button b && b.visible
                && b.getMessage().getContents() instanceof TranslatableContents caption && caption.getKey().equals(PREFIX + key)) return b.active;
        return false;
    }

    private static BlockHitResult aim(Minecraft game, BlockPos pos) {
        var target = Vec3.atCenterOf(pos);
        game.player.lookAt(Anchor.EYES,target);
        var eye = game.player.getEyePosition();
        var hit = game.level.clip(new ClipContext(eye,target,ClipContext.Block.OUTLINE,ClipContext.Fluid.NONE,game.player));
        if (hit.getType() != HitResult.Type.BLOCK || !hit.getBlockPos().equals(pos) || eye.distanceTo(hit.getLocation()) > 4.5)
            throw new IllegalStateException("Cannot reach " + pos + " from this deck");
        return hit;
    }
}
