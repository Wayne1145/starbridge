package dev.starbridge.qa.habitat;

import dev.starbridge.client.FirstFlightScreen;
import dev.starbridge.client.MachineAssemblyScreen;
import dev.starbridge.compat.rocketry.OriginalMachineLayout;
import dev.starbridge.client.shipyard.FabricationScreen;
import dev.starbridge.voyage.VoyageModule;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
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
    private static final String TOWER = "advancedrocketry:structuretower";
    private static final String GUIDANCE = "advancedrocketry:tile.guidancecomputer";
    private static final String CIRCUIT = "advancedrocketry:circuitic";
    private static final String PLATE = "libvulpes:libvulpesproductplate";
    private static final String INGOT = "libvulpes:libvulpesproductingot";
    private static final String ARC_FURNACE = "advancedrocketry:tile.electricarcfurnace";
    private static int step, next;
    private static int stockedSlots;

    private MachineRecipeJourney() {}

    static void supplyWorkbenchSample(net.minecraft.server.level.ServerPlayer player) {
        var circuit = ForgeRegistries.ITEMS.getValue(ResourceLocation.parse(CIRCUIT));
        if (circuit == null) throw new IllegalStateException("Original circuit required");
        var tracking = new ItemStack(circuit);
        tracking.getOrCreateTag().putInt("LegacyMeta", 1);
        var control = new ItemStack(circuit, 3);
        control.getOrCreateTag().putInt("LegacyMeta", 3);
        player.getInventory().setItem(10, tracking);
        player.getInventory().setItem(11, control);
        player.getInventory().setItem(12, new ItemStack(net.minecraft.world.item.Items.REDSTONE, 2));
        var tower = ForgeRegistries.ITEMS.getValue(ResourceLocation.parse(TOWER));
        if (tower == null) throw new IllegalStateException("Original tower required");
        player.getInventory().setItem(13, new ItemStack(tower, 2));
    }

    static void tick(Minecraft game, int tick) throws Exception {
        if (step > 18 || tick < next) return;
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
                if (!(screen instanceof FabricationScreen) || !contains(screen, CIRCUIT, 1)) return;
                stockedSlots = integer(screen, "readySlots");
                if (stockedSlots != 3 || integer(screen, "neededSlots") != 9) {
                    throw new IllegalStateException("One tracking circuit and two redstone must fill exactly three slots, including with three wrong circuits carried");
                }
                HabitatClient.snap(game, "machine-guidance-stock");
                HabitatClient.log("Guidance workbench showed three stocked slots of nine");
                if (!clickItem(screen, CIRCUIT, 1)) return;
                HabitatClient.log("Selected the tracking circuit ingredient");
            }
            case 5 -> {
                if (!contains(screen, "advancedrocketry:precisionassemblingmachine", 0)
                        || !contains(screen, "advancedrocketry:circuitplate", 0)
                        || !contains(screen, "minecraft:ender_eye", 0)) return;
                HabitatClient.snap(game, "machine-tracking-circuit");
                HabitatClient.log("Saw the original precision assembler and circuit ingredients");
                if (!HabitatClient.click(screen, "slate.starbridge.propellant.assembly")) return;
            }
            case 6 -> {
                if (!layout(screen, 4, 3, 3, 36, 2)) return;
                HabitatClient.snap(game, "machine-precision-assembly");
                if (!HabitatClient.click(screen, "slate.starbridge.machine.back_recipe")) return;
            }
            case 7 -> {
                if (!contains(screen, "advancedrocketry:precisionassemblingmachine", 0)) return;
                if (!HabitatClient.click(screen, "slate.starbridge.materials.back_ingredient")) return;
            }
            case 8 -> {
                if (!clickItem(screen, PLATE, 7)) return;
                HabitatClient.log("Selected the titanium plate ingredient");
            }
            case 9 -> {
                if (!contains(screen, "advancedrocketry:tile.rollingmachine", 0)
                        || !contains(screen, "libvulpes:libvulpesproductingot", 7)) return;
                HabitatClient.snap(game, "machine-titanium-plate");
                HabitatClient.log("Saw the original rolling machine and titanium ingot");
                if (!HabitatClient.click(screen, "slate.starbridge.propellant.assembly")) return;
            }
            case 10 -> {
                if (!layout(screen, 4, 2, 3, 22, 6)) return;
                HabitatClient.snap(game, "machine-rolling-layout");
                if (!HabitatClient.click(screen, "slate.starbridge.machine.back_recipe")) return;
            }
            case 11 -> {
                if (!contains(screen, "advancedrocketry:tile.rollingmachine", 0) || !clickItem(screen, INGOT, 7)) return;
                HabitatClient.log("Selected the titanium ingot ingredient");
            }
            case 12 -> {
                if (!HabitatClient.click(screen, "slate.starbridge.materials.machine_list")) return;
            }
            case 13 -> {
                if (!contains(screen, ARC_FURNACE, 0) || !contains(screen, "libvulpes:libvulpesore0", 8)) return;
                HabitatClient.snap(game, "machine-titanium-ingot");
                if (!HabitatClient.click(screen, "slate.starbridge.propellant.assembly")) return;
            }
            case 14 -> {
                if (!layout(screen, 5, 5, 5, 105, 15)) return;
                HabitatClient.snap(game, "machine-arc-layout");
                if (!HabitatClient.click(screen, "slate.starbridge.machine.back_recipe")) return;
            }
            case 15 -> {
                if (!contains(screen, ARC_FURNACE, 0)) return;
                if (!HabitatClient.click(screen, "slate.starbridge.materials.back_ingredient")) return;
            }
            case 16 -> {
                if (!contains(screen, "advancedrocketry:tile.rollingmachine", 0)) return;
                if (!HabitatClient.click(screen, "slate.starbridge.materials.back_ingredient")) return;
            }
            case 17 -> {
                if (!contains(screen, CIRCUIT, 1)) return;
                if (!clickItem(screen, TOWER, 0)) return;
            }
            case 18 -> {
                if (!contains(screen, "libvulpes:libvulpesproductrod", 6)) return;
                int towerBatches = towerBatches(screen);
                if (towerBatches != 4) throw new IllegalStateException("Two finished tower blocks must leave four production batches");
                HabitatClient.snap(game, "machine-tower-batches");
                var server = game.getSingleplayerServer();
                if (server == null || server.getWorldData().getAllowCommands() || game.player.isCreative()
                        || game.player.hasPermissions(2)) throw new IllegalStateException("Survival and cheats off required");
                GardenCamp.save("machine-recipes-client", Map.of("pass", true, "gameType", "survival", "cheats", false,
                        "activeParts", List.of("trackingCircuit", "titaniumPlate", "titaniumIngot"), "returnedToGuidance", true,
                        "layers", List.of(3, 2, 5), "guidanceStock", List.of(stockedSlots, 9), "towerBatches", towerBatches));
            }
            default -> throw new IllegalStateException("Unexpected fabrication step");
        }
        step++;
        next = tick + 16;
    }

    private static int towerBatches(Screen screen) throws Exception {
        var details = FabricationScreen.class.getDeclaredMethod("resultDetails", String.class, int.class, int.class);
        var stock = FabricationScreen.class.getDeclaredMethod("owned", ItemStack.class);
        details.setAccessible(true);
        stock.setAccessible(true);
        var tower = new ItemStack(ForgeRegistries.ITEMS.getValue(ResourceLocation.parse(TOWER)));
        int owned = (int) stock.invoke(screen, tower);
        var rendered = (Component) details.invoke(screen, "shaped", 1, owned);
        if (rendered.getContents() instanceof TranslatableContents label
                && label.getKey().equals("slate.starbridge.materials.plan_batches")
                && label.getArgs()[0] instanceof Integer batches) return batches;
        throw new IllegalStateException("Missing tower quantity from the open crafting panel");
    }

    private static int integer(Screen screen, String name) throws Exception {
        var field = FabricationScreen.class.getDeclaredField(name);
        field.setAccessible(true);
        return field.getInt(screen);
    }

    private static boolean layout(Screen screen, int width, int layers, int depth, int cells, int clear) throws Exception {
        if (!(screen instanceof MachineAssemblyScreen)) return false;
        var field = MachineAssemblyScreen.class.getDeclaredField("plan");
        field.setAccessible(true);
        if (!(field.get(screen) instanceof OriginalMachineLayout plan) || plan.width() != width || plan.depth() != depth
                || plan.height() != layers || plan.cells().size() != cells || plan.cells().stream()
                .filter(cell -> cell.role() == OriginalMachineLayout.Role.CLEAR).count() != clear) {
            throw new IllegalStateException("The original machine layout did not show the registered cells");
        }
        return true;
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
