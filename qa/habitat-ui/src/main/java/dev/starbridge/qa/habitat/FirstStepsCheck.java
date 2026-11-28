package dev.starbridge.qa.habitat;

import com.google.gson.JsonObject;
import dev.starbridge.voyage.VoyageModule;
import net.minecraft.advancements.Advancement;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.advancements.AdvancementTab;
import net.minecraft.client.gui.screens.advancements.AdvancementsScreen;
import net.minecraft.client.gui.screens.inventory.ContainerScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BarrelBlockEntity;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import org.lwjgl.glfw.GLFW;

import java.nio.file.Files;
import java.nio.file.Path;

/** Exercise the vanilla advancement screen before receiving a compass and after crafting the slate. */
final class FirstStepsCheck {
    static final Path MARKER = Path.of("starbridge-first-steps.fixture");
    private static final BlockPos BARREL = new BlockPos(63,149,62);
    private static final ResourceLocation RECIPE = new ResourceLocation("starbridge", "navigation_slate");
    private static final JsonObject observations = new JsonObject();
    private static int step, next;

    private FirstStepsCheck() {}

    static void prepare(ServerLevel level, ServerPlayer player) {
        // Split the starter fixture into two ordinary pickups. The compass stays in the barrel.
        var inventory = player.getInventory();
        int index = inventory.findSlotMatchingItem(new ItemStack(Items.COMPASS));
        if (index < 0) throw new IllegalStateException("Missing starter compass");
        inventory.setItem(index, ItemStack.EMPTY);
        level.setBlockAndUpdate(BARREL, Blocks.BARREL.defaultBlockState());
        var barrel = (BarrelBlockEntity) level.getBlockEntity(BARREL);
        barrel.setItem(0, new ItemStack(Items.CRAFTING_TABLE));
        barrel.setItem(1, new ItemStack(Items.COMPASS));
    }

    private static boolean take(Minecraft game, int slot, int tick) {
        var wanted = slot == 0 ? Items.CRAFTING_TABLE : Items.COMPASS;
        if (game.player.getInventory().contains(new ItemStack(wanted))) {
            if (game.screen instanceof ContainerScreen) game.player.closeContainer();
            return true;
        }
        if (game.screen == null) {
            next = tick + 12;
            game.gameMode.useItemOn(game.player, InteractionHand.MAIN_HAND,
                    new BlockHitResult(Vec3.atCenterOf(BARREL).add(-0.5,0,0), Direction.WEST, BARREL, false));
            return false;
        }
        if (!(game.screen instanceof ContainerScreen)) return false;
        var menu = game.player.containerMenu;
        if (!menu.getSlot(slot).getItem().is(wanted)) return false;
        game.gameMode.handleInventoryMouseClick(menu.containerId, slot, 0, ClickType.QUICK_MOVE, game.player);
        next = tick + 8;
        return false;
    }

    private static Advancement node(Minecraft game, String name) {
        return game.getConnection().getAdvancements().getAdvancements().get(new ResourceLocation("starbridge", "first_flight/" + name));
    }

    private static boolean showTab(Minecraft game) {
        if (game.screen instanceof ContainerScreen) {
            game.player.closeContainer();
            return false;
        }
        if (game.screen == null) {
            KeyMapping.click(game.options.keyAdvancements.getKey());
            return false;
        }
        if (!(game.screen instanceof AdvancementsScreen screen)) return false;
        var root = node(game, "root");
        if (root == null || screen.getAdvancementWidget(root) == null) return false;
        int index = 0;
        for (var choice : game.getConnection().getAdvancements().getAdvancements().getRoots()) {
            if (choice.getDisplay() == null) continue;
            if (choice.equals(root)) {
                var tab = AdvancementTab.create(game, screen, index, root);
                if (tab == null || tab.getPage() != 0) throw new IllegalStateException("Test tab is not on the first page");
                int left = (screen.width - 252) / 2, top = (screen.height - 140) / 2;
                for (int y = top - 30; y < top + 170; y += 6) {
                    for (int x = left - 30; x < left + 282; x += 6) {
                        if (tab.isMouseOver(left, top, x, y)) {
                            screen.mouseClicked(x, y, 0);
                            return true;
                        }
                    }
                }
                throw new IllegalStateException("Starbridge tab has no clickable area");
            }
            index++;
        }
        return false;
    }

    /** Move only the test window's cursor callback, including on an inactive Windows desktop. */
    private static void hoverSlate(Minecraft game, AdvancementsScreen screen, Advancement slate) {
        int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE;
        for (String name : new String[]{"root", "compass", "slate"}) {
            var widget = screen.getAdvancementWidget(node(game, name));
            minX = Math.min(minX, widget.getX()); minY = Math.min(minY, widget.getY());
            maxX = Math.max(maxX, widget.getX() + 28); maxY = Math.max(maxY, widget.getY() + 27);
        }
        var widget = screen.getAdvancementWidget(slate);
        int x = screen.width / 2 + widget.getX() + 13 - (minX + maxX) / 2;
        int y = (screen.height - 140) / 2 + 18 + 56 + widget.getY() + 13 - (minY + maxY) / 2;
        var window = game.getWindow();
        var handle = window.getWindow();
        var cursor = GLFW.glfwSetCursorPosCallback(handle, null);
        if (cursor == null) throw new IllegalStateException("Test window has no cursor callback");
        GLFW.glfwSetCursorPosCallback(handle, cursor);
        cursor.invoke(handle, (x + .5) * window.getScreenWidth() / screen.width, (y + .5) * window.getScreenHeight() / screen.height);
    }

    static boolean beforeCraft(Minecraft game, int tick) throws Exception {
        if (step >= 5) return true;
        if (tick < next || !game.level.getBlockState(BARREL).is(Blocks.BARREL)) return false;
        var recipe = game.level.getRecipeManager().byKey(RECIPE).orElseThrow();
        switch (step) {
            case 0 -> { if (!take(game, 0, tick)) return false; }
            case 1 -> {
                if (node(game, "compass") == null) return false;
                if (!showTab(game)) return false;
                var screen = (AdvancementsScreen) game.screen;
                if (game.player.getRecipeBook().contains(recipe) || screen.getAdvancementWidget(node(game, "compass")) == null)
                    throw new IllegalStateException("Expected the visible next step before the slate recipe unlocked");
                observations.addProperty("compassStepVisibleBeforeRecipe", true);
            }
            case 2 -> {
                HabitatClient.snap(game, "00-first-steps-workbench");
                game.screen.onClose();
            }
            case 3 -> { if (!take(game, 1, tick)) return false; }
            case 4 -> {
                if (!game.player.getRecipeBook().contains(recipe)) return false;
                observations.addProperty("recipeUnlockedAfterTakingCompass", true);
                HabitatClient.log("Opened the vanilla advancement tab from a workbench, then took the compass and unlocked the slate recipe");
            }
            default -> throw new IllegalStateException();
        }
        step++;
        next = tick + 10;
        return step == 5;
    }

    static boolean beforeUse(Minecraft game, int tick) throws Exception {
        if (step >= 7) return true;
        if (tick < next || !game.player.getMainHandItem().is(VoyageModule.SLATE.get())) return false;
        var slate = node(game, "slate");
        if (slate == null || slate.getDisplay() == null) return false;
        if (step == 5) {
            if (!showTab(game)) return false;
            String hint = slate.getDisplay().getDescription().getString();
            String binding = game.options.keyUse.getTranslatedKeyMessage().getString();
            if (((AdvancementsScreen) game.screen).getAdvancementWidget(slate) == null || !hint.contains(binding))
                throw new IllegalStateException("The terminal step must name the player's Use binding: " + hint);
            hoverSlate(game, (AdvancementsScreen) game.screen, slate);
            observations.addProperty("terminalDescription", hint);
            observations.addProperty("useBinding", binding);
        } else {
            HabitatClient.snap(game, "00-first-steps-slate");
            game.screen.onClose();
            observations.addProperty("pass", true);
            Files.writeString(Path.of("first-steps-result.json"), observations.toString());
        }
        step++;
        next = tick + 10;
        return step == 7;
    }
}
