package dev.starbridge.qa.habitat;

import dev.starbridge.client.FirstFlightScreen;
import dev.starbridge.client.shipyard.FabricationScreen;
import dev.starbridge.voyage.VoyageModule;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.registries.ForgeRegistries;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/** Use the survival terminal and click the original navigation computer's ingredients. */
final class MachineRecipeJourney {
    static final Path MARKER = Path.of("starbridge-machine-recipes.fixture");
    private static final String GUIDANCE = "advancedrocketry:tile.guidancecomputer";
    private static final String CIRCUIT = "advancedrocketry:circuitic";
    private static final String PLATE = "libvulpes:libvulpesproductplate";
    private static int step, next;

    private MachineRecipeJourney() {}

    static void tick(Minecraft game, int tick) throws Exception {
        if (step > 8 || tick < next) return;
        var screen = game.screen;
        switch (step) {
            case 0 -> {
                if (!game.player.getMainHandItem().is(VoyageModule.SLATE.get())) return;
                for (String part : List.of(GUIDANCE, CIRCUIT, PLATE)) {
                    if (!ForgeRegistries.ITEMS.containsKey(ResourceLocation.parse(part))) {
                        throw new IllegalStateException("Original workshop item not installed: " + part);
                    }
                }
                game.gameMode.useItem(game.player, InteractionHand.MAIN_HAND);
            }
            case 1 -> {
                if (!(screen instanceof FirstFlightScreen) || !HabitatClient.click(screen, "slate.starbridge.guide.shipyard")) return;
            }
            case 2 -> {
                if (!HabitatClient.click(screen, "slate.starbridge.materials.title")) return;
            }
            case 3 -> {
                if (!clickItem(screen, GUIDANCE, 0)) return;
                HabitatClient.log("Selected the navigation computer recipe");
            }
            case 4 -> {
                if (!clickItem(screen, CIRCUIT, 1)) return;
                HabitatClient.log("Selected the basic circuit ingredient");
            }
            case 5 -> {
                if (!contains(screen, "advancedrocketry:precisionassemblingmachine", 0)
                        || !contains(screen, "advancedrocketry:circuitplate", 0)
                        || !contains(screen, "minecraft:ender_eye", 0)) return;
                HabitatClient.snap(game, "machine-basic-circuit");
                HabitatClient.log("Saw the original precision assembler and circuit ingredients");
                if (!HabitatClient.click(screen, "slate.starbridge.materials.back_ingredient")) {
                    throw new IllegalStateException("Missing return to the parent recipe");
                }
            }
            case 6 -> {
                if (!clickItem(screen, PLATE, 7)) return;
                HabitatClient.log("Selected the aluminium plate ingredient");
            }
            case 7 -> {
                if (!contains(screen, "advancedrocketry:tile.rollingmachine", 0)
                        || !contains(screen, "libvulpes:libvulpesproductingot", 7)) return;
                HabitatClient.snap(game, "machine-aluminium-plate");
                HabitatClient.log("Saw the original rolling machine and aluminium ingot");
                if (!HabitatClient.click(screen, "slate.starbridge.materials.back_ingredient")) {
                    throw new IllegalStateException("Missing return to the parent recipe");
                }
            }
            case 8 -> {
                if (!contains(screen, CIRCUIT, 1)) return;
                var server = game.getSingleplayerServer();
                if (server == null || server.getWorldData().getAllowCommands() || game.player.isCreative()
                        || game.player.hasPermissions(2)) throw new IllegalStateException("Survival and cheats off required");
                GardenCamp.save("machine-recipes-client", Map.of("pass", true, "gameType", "survival", "cheats", false,
                        "activeParts", List.of("basicCircuit", "aluminiumPlate"), "returnedToGuidance", true));
            }
            default -> throw new IllegalStateException("Unexpected fabrication step");
        }
        step++;
        next = tick + 16;
    }

    private static boolean clickItem(Screen screen, String id, int metadata) throws Exception {
        var button = itemButton(screen, id, metadata);
        if (button == null) return false;
        double x = button.getX() + button.getWidth() / 2.0;
        double y = button.getY() + button.getHeight() / 2.0;
        if (!screen.mouseClicked(x, y, 0)) return false;
        screen.mouseReleased(x, y, 0);
        return true;
    }

    private static boolean contains(Screen screen, String id, int metadata) throws Exception {
        return itemButton(screen, id, metadata) != null;
    }

    private static Button itemButton(Screen screen, String id, int metadata) throws Exception {
        if (!(screen instanceof FabricationScreen)) return null;
        for (var widget : screen.children()) {
            if (!(widget instanceof Button button) || !button.visible || !button.active
                    || !button.getClass().getSimpleName().equals("IconButton")) continue;
            var icon = button.getClass().getDeclaredField("icon");
            icon.setAccessible(true);
            if (((Supplier<?>) icon.get(button)).get() instanceof ItemStack stack
                    && !stack.isEmpty() && id.equals(String.valueOf(ForgeRegistries.ITEMS.getKey(stack.getItem())))
                    && metadata == (stack.getTag() == null ? 0 : stack.getTag().getInt("LegacyMeta"))) return button;
        }
        return null;
    }
}
