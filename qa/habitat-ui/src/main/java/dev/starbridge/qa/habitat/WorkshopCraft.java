package dev.starbridge.qa.habitat;

import com.google.gson.Gson;
import dev.starbridge.client.FirstFlightScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.CraftingScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/** Click the stock recipe book in the rendered workbench and follow its output into the saved inventory. */
final class WorkshopCraft {
    static final Path MARKER = Path.of("starbridge-workshop.fixture");
    private static final ResourceLocation PRESS = new ResourceLocation("advancedrocketry", "tile.blockhandpress");
    private static int stage, next;
    private static List<String> layout;
    private WorkshopCraft() {}

    static boolean tick(Minecraft game, int tick) throws Exception {
        if (stage == 8) return true;
        if (tick < next) return false;
        var screen = game.screen;
        var player = game.player;
        var press = BuiltInRegistries.ITEM.getOptional(PRESS).orElseThrow();
        switch (stage) {
            case 0 -> {
                if (!(screen instanceof FirstFlightScreen) || !HabitatClient.click(screen, "gui.done")) return false;
            }
            case 1 -> {
                if (screen != null) return false;
                var table = new BlockPos(61, 149, 61);
                game.gameMode.useItemOn(player, InteractionHand.MAIN_HAND, new BlockHitResult(
                        Vec3.atCenterOf(table).add(0, .5, 0), Direction.UP, table, false));
            }
            case 2 -> {
                if (!(screen instanceof CraftingScreen table)) return false;
                if (!table.getRecipeBookComponent().isVisible()) click(table, table.getGuiLeft() + 15, table.height / 2 - 40);
                if (!table.getRecipeBookComponent().isVisible()) throw new IllegalStateException("Recipe book did not open");
                var name = press.getDefaultInstance().getHoverName().getString();
                click(table, bookLeft(table) + 41, (table.height - 166) / 2 + 19);
                for (char letter : name.toCharArray()) table.charTyped(letter, 0);
                HabitatClient.log("Searched the vanilla recipe book for " + name);
            }
            case 3 -> {
                if (!(screen instanceof CraftingScreen table)) return false;
                HabitatClient.snap(game, "00-workshop-book-search");
                click(table, bookLeft(table) + 22, (table.height - 166) / 2 + 42);
            }
            case 4 -> {
                if (!(screen instanceof CraftingScreen table)) return false;
                var slots = player.containerMenu.slots;
                if (!slots.get(0).getItem().is(press)) throw new IllegalStateException("No original press after selecting the book result: " + slots.subList(0, 10).stream().map(Slot::getItem).toList());
                layout = slots.subList(1,10).stream().map(s -> s.hasItem() ? BuiltInRegistries.ITEM.getKey(s.getItem().getItem()).toString() : "").toList();
                if (!layout.equals(List.of("", "minecraft:piston", "", "minecraft:iron_ingot", "minecraft:iron_ingot", "minecraft:iron_ingot", "", "", "")))
                    throw new IllegalStateException("Unexpected book layout " + layout);
                HabitatClient.snap(game, "00-workshop-craft-result");
                clickSlot(table, slots.get(0));
            }
            case 5 -> {
                if (!(screen instanceof CraftingScreen table) || !player.containerMenu.getCarried().is(press)) return false;
                var empty = player.containerMenu.slots.stream().filter(s -> s.container == player.getInventory() && !s.hasItem()).findFirst().orElseThrow();
                clickSlot(table, empty);
            }
            case 6 -> {
                if (!(screen instanceof CraftingScreen table) || !player.containerMenu.getCarried().isEmpty() || !player.getInventory().contains(new ItemStack(press))) return false;
                var host = game.getSingleplayerServer();
                if (host == null || host.getWorldData().getAllowCommands() || player.isCreative() || player.hasPermissions(2)) throw new IllegalStateException("Ordinary survival required");
                Files.writeString(Path.of("workshop-craft-result.json"), new Gson().toJson(Map.of("press", PRESS.toString(), "ingredients", layout, "cheats", false, "pass", true)));
                HabitatClient.log("Clicked the original press result and an empty inventory slot");
                table.onClose();
            }
            case 7 -> {
                if (screen != null) return false;
                game.gameMode.useItem(player, InteractionHand.MAIN_HAND);
            }
            default -> throw new IllegalStateException();
        }
        stage++;
        next = tick + 12;
        return false;
    }

    private static int bookLeft(CraftingScreen table) { return (table.width - 147) / 2 - (table.width < 379 ? 0 : 86); }
    private static void clickSlot(CraftingScreen table, Slot slot) { click(table, table.getGuiLeft() + slot.x + 8, table.getGuiTop() + slot.y + 8); }
    private static void click(CraftingScreen table, int x, int y) { table.mouseClicked(x,y,0); table.mouseReleased(x,y,0); }
}
