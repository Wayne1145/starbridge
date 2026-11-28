package dev.starbridge.qa.habitat;

import dev.starbridge.client.FirstFlightScreen;
import dev.starbridge.voyage.VoyageModule;

import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.ContainerScreen;
import net.minecraft.client.gui.screens.inventory.CraftingScreen;
import net.minecraft.commands.arguments.EntityAnchorArgument.Anchor;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * A rendered survival client harvests the naturally grown crop and clicks the real crafting and
 * terminal screens.
 */
final class GardenJourney {
    private static final String SHIPYARD = "slate.starbridge.guide.";

    private enum Step {
        WAIT,
        CUT_EAST,
        CUT_WEST,
        COLLECT,
        RETURN,
        CHEST,
        COMPASS,
        GLASS,
        CLOSE_CHEST,
        TABLE,
        PAPER_GRID,
        PAPER_OUTPUT,
        SLATE_GRID,
        SLATE_OUTPUT,
        LEAVE_TABLE,
        OPEN_GUIDE,
        GUIDE,
        PLAN,
        SIDE,
        PLAN_AGAIN,
        STORE_CHEST,
        DEPOSIT,
        FINISH,
        DONE
    }

    private static Step step = Step.WAIT;
    private static int next, harvested, since;
    private static Step recorded;
    private static Screen guide;

    private GardenJourney() {}

    static void tick(Minecraft game, int tick) throws Exception {
        if (tick < next || step == Step.DONE || !Files.isRegularFile(Path.of("garden-start.json")))
            return;
        next = tick + 5;
        try {
            if (step != recorded) {
                HabitatClient.log("Garden: " + step.name());
                recorded = step;
                since = tick;
            }
            if (step != Step.WAIT && tick - since > 1200)
                throw new IllegalStateException(
                        "Stalled at "
                                + step
                                + " with cane="
                                + count(game, Items.SUGAR_CANE)
                                + ", paper="
                                + count(game, Items.PAPER)
                                + ", iron="
                                + count(game, Items.IRON_INGOT));
            act(game, tick);
        } catch (RuntimeException | AssertionError error) {
            game.options.keyUp.setDown(false);
            game.options.keyJump.setDown(false);
            GardenCamp.save(
                    "garden-client",
                    Map.of("pass", false, "step", step.name(), "error", error.toString()));
            step = Step.DONE;
        }
    }

    private static void act(Minecraft game, int tick) throws Exception {
        var screen = game.screen;
        switch (step) {
            case WAIT -> {
                if (!Files.isRegularFile(Path.of("garden-growth.json"))) return;
                BlockPos top =
                        game.level.getBlockState(GardenCamp.EAST.above()).is(Blocks.SUGAR_CANE)
                                ? GardenCamp.EAST.above()
                                : GardenCamp.WEST.above();
                if (!game.level.getBlockState(top).is(Blocks.SUGAR_CANE)) return;
                game.player.lookAt(Anchor.EYES, Vec3.atCenterOf(top));
                HabitatClient.log("Saw a naturally grown upper cane at the rooftop garden");
            }
            case CUT_EAST, CUT_WEST -> {
                var pos = step == Step.CUT_EAST ? GardenCamp.EAST : GardenCamp.WEST;
                if (game.level.getBlockState(pos).is(Blocks.SUGAR_CANE)) {
                    if (step == Step.CUT_EAST) HabitatClient.snap(game, "garden-grown");
                    var hit = aim(game, pos);
                    if (hit == null) return;
                    game.gameMode.startDestroyBlock(pos, hit.getDirection());
                    game.player.swing(InteractionHand.MAIN_HAND);
                    return;
                }
            }
            case COLLECT -> {
                harvested = count(game, Items.SUGAR_CANE);
                if (harvested < 3) {
                    var drops =
                            game.level.getEntitiesOfClass(
                                    ItemEntity.class,
                                    game.player.getBoundingBox().inflate(4),
                                    e -> e.getItem().is(Items.SUGAR_CANE));
                    drops.stream()
                            .min(Comparator.comparingDouble(e -> game.player.distanceToSqr(e)))
                            .ifPresent(e -> walk(game, e.position()));
                    return;
                }
                stop(game);
                HabitatClient.log("Collected at least three cane from the two planted stems");
            }
            case RETURN -> {
                Vec3 origin = new Vec3(62.5, 149, 61.5);
                if (!walk(game, origin)) return;
            }
            case CHEST, STORE_CHEST -> {
                if (!(screen instanceof ContainerScreen)) {
                    if (screen == null) useBlock(game, GardenCamp.CHEST);
                    return;
                }
            }
            case COMPASS, GLASS -> {
                var chest = (ContainerScreen) screen;
                Item required = step == Step.COMPASS ? Items.COMPASS : Items.GLASS_PANE;
                transfer(chest, find(chest, game, required, false), empty(chest, game, true));
            }
            case CLOSE_CHEST -> {
                GardenCamp.require(
                        count(game, Items.COMPASS) == 1 && count(game, Items.GLASS_PANE) == 16,
                        "Did not take the camp's original materials");
                screen.onClose();
            }
            case TABLE -> {
                if (!(screen instanceof CraftingScreen)) {
                    if (screen == null) useBlock(game, GardenCamp.TABLE);
                    return;
                }
            }
            case PAPER_GRID -> {
                var table = (CraftingScreen) screen;
                place(table, game, Items.SUGAR_CANE, 1, 2, 3);
            }
            case PAPER_OUTPUT -> {
                var table = (CraftingScreen) screen;
                var out = table.getMenu().getSlot(0);
                if (!out.getItem().is(Items.PAPER) || out.getItem().getCount() != 3) return;
                HabitatClient.snap(game, "garden-paper");
                transfer(table, out, empty(table, game, true));
                HabitatClient.log("Took three paper from the workbench output");
            }
            case SLATE_GRID -> {
                var table = (CraftingScreen) screen;
                place(table, game, Items.GLASS_PANE, 2);
                place(table, game, Items.COMPASS, 3);
                place(table, game, Items.REDSTONE, 4, 6);
                place(table, game, Items.IRON_INGOT, 5);
                place(table, game, Items.PAPER, 8);
            }
            case SLATE_OUTPUT -> {
                var table = (CraftingScreen) screen;
                var out = table.getMenu().getSlot(0);
                if (!out.getItem().is(VoyageModule.SLATE.get()) || out.getItem().getCount() != 1)
                    return;
                var hotbar =
                        table.getMenu().slots.stream()
                                .filter(
                                        s ->
                                                s.container == game.player.getInventory()
                                                        && s.getContainerSlot() == 6
                                                        && !s.hasItem())
                                .findFirst()
                                .orElseThrow();
                HabitatClient.snap(game, "garden-terminal");
                transfer(table, out, hotbar);
                HabitatClient.log("Took the crafted navigation terminal from the workbench output");
            }
            case LEAVE_TABLE -> {
                GardenCamp.require(
                        game.player.getInventory().getItem(6).is(VoyageModule.SLATE.get()),
                        "Terminal not in the player's hotbar");
                screen.onClose();
                game.player.getInventory().selected = 6;
                game.player.setXRot(-20);
                game.player.setYRot(0);
            }
            case OPEN_GUIDE -> {
                // The room's far wall is outside placement range. Avoid using the terminal on an
                // interactive block.
                if (!(screen instanceof FirstFlightScreen)) {
                    if (screen == null
                            && game.player.getMainHandItem().is(VoyageModule.SLATE.get())) {
                        KeyMapping.click(game.options.keyUse.getKey());
                        next = tick + 15;
                    }
                    return;
                }
                guide = screen;
                HabitatClient.log("Opened the self-crafted terminal with Use");
            }
            case GUIDE -> GardenCamp.require(
                    HabitatClient.click(guide, SHIPYARD + "shipyard"), "Shipyard entry");
            case PLAN -> {
                selected(game, "plan");
                HabitatClient.snap(game, "garden-plan");
                GardenCamp.require(HabitatClient.click(guide, SHIPYARD + "elevation"), "Side view");
            }
            case SIDE -> {
                selected(game, "elevation");
                HabitatClient.snap(game, "garden-elevation");
                GardenCamp.require(HabitatClient.click(guide, SHIPYARD + "plan"), "Plan view");
            }
            case PLAN_AGAIN -> {
                selected(game, "plan");
                GardenCamp.require(HabitatClient.click(guide, "gui.done"), "Close guide");
            }
            case DEPOSIT -> {
                var chest = (ContainerScreen) screen;
                transfer(
                        chest,
                        find(chest, game, VoyageModule.SLATE.get(), true),
                        empty(chest, game, false));
                HabitatClient.log("Stored the terminal in the camp chest");
            }
            case FINISH -> {
                if (!Files.isRegularFile(Path.of("garden-server.json"))) return;
                GardenCamp.require(
                        count(game, VoyageModule.SLATE.get()) == 0, "Terminal still on the player");
                screen.onClose();
                GardenCamp.save(
                        "garden-client",
                        Map.of(
                                "pass",
                                true,
                                "collectedCane",
                                harvested,
                                "views",
                                List.of("plan", "elevation", "plan"),
                                "mode",
                                game.gameMode.getPlayerMode().getName(),
                                "useKey",
                                game.options.keyUse.getTranslatedKeyMessage().getString()));
            }
            default -> {
                return;
            }
        }
        step = Step.values()[step.ordinal() + 1];
    }

    private static void selected(Minecraft game, String view) {
        GardenCamp.require(
                game.screen == guide
                        && guide.children().stream()
                                .anyMatch(
                                        child ->
                                                child instanceof Button b
                                                        && b.visible
                                                        && !b.active
                                                        && b.getMessage().getContents()
                                                                instanceof TranslatableContents t
                                                        && t.getKey().equals(SHIPYARD + view)),
                "Expected selected " + view);
    }

    private static void stop(Minecraft game) {
        game.options.keyUp.setDown(false);
        game.options.keyJump.setDown(false);
    }

    private static boolean walk(Minecraft game, Vec3 goal) {
        double dx = goal.x - game.player.getX(), dz = goal.z - game.player.getZ();
        if (dx * dx + dz * dz < .18) {
            stop(game);
            return true;
        }
        game.player.setYRot((float) Math.toDegrees(Math.atan2(-dx, dz)));
        game.player.setXRot(7);
        game.options.keyUp.setDown(true);
        game.options.keyJump.setDown(game.player.isInWater());
        return false;
    }

    private static BlockHitResult aim(Minecraft game, BlockPos pos) {
        var eye = game.player.getEyePosition();
        var point = Vec3.atCenterOf(pos);
        game.player.lookAt(Anchor.EYES, point);
        var hit =
                game.level.clip(
                        new ClipContext(
                                eye,
                                point,
                                ClipContext.Block.OUTLINE,
                                ClipContext.Fluid.NONE,
                                game.player));
        return hit.getType() == HitResult.Type.BLOCK
                        && hit.getBlockPos().equals(pos)
                        && eye.distanceTo(hit.getLocation()) <= 4.4
                ? hit
                : null;
    }

    private static void useBlock(Minecraft game, BlockPos pos) {
        var hit = aim(game, pos);
        if (hit != null) game.gameMode.useItemOn(game.player, InteractionHand.MAIN_HAND, hit);
    }

    private static int count(Minecraft game, Item item) {
        return game.player.getInventory().items.stream()
                .filter(s -> s.is(item))
                .mapToInt(s -> s.getCount())
                .sum();
    }

    private static Slot find(
            AbstractContainerScreen<?> screen, Minecraft game, Item item, boolean player) {
        return screen.getMenu().slots.stream()
                .filter(
                        s ->
                                s.hasItem()
                                        && s.getItem().is(item)
                                        && (s.container == game.player.getInventory()) == player)
                .findFirst()
                .orElseThrow();
    }

    private static Slot empty(AbstractContainerScreen<?> screen, Minecraft game, boolean player) {
        return screen.getMenu().slots.stream()
                .filter(s -> !s.hasItem() && (s.container == game.player.getInventory()) == player)
                .findFirst()
                .orElseThrow();
    }

    private static void click(AbstractContainerScreen<?> screen, Slot slot, int mouseButton) {
        int x = screen.getGuiLeft() + slot.x + 8, y = screen.getGuiTop() + slot.y + 8;
        screen.mouseClicked(x, y, mouseButton);
        screen.mouseReleased(x, y, mouseButton);
    }

    private static void transfer(AbstractContainerScreen<?> screen, Slot source, Slot destination) {
        click(screen, source, 0);
        click(screen, destination, 0);
        GardenCamp.require(
                screen.getMenu().getCarried().isEmpty(),
                "Container cursor after transferring item");
    }

    private static void place(CraftingScreen screen, Minecraft game, Item item, int... gridSlots) {
        Slot source = find(screen, game, item, true);
        click(screen, source, 0);
        for (int slot : gridSlots) click(screen, screen.getMenu().getSlot(slot), 1);
        if (!screen.getMenu().getCarried().isEmpty()) click(screen, source, 0);
        GardenCamp.require(
                screen.getMenu().getCarried().isEmpty(), "Container cursor after placing " + item);
    }
}
