package dev.starbridge.compat.rocketry;

import dev.starbridge.Starbridge;
import dev.starbridge.compat.galacticraft.OriginalStarMap;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.registries.ForgeRegistries;

import java.lang.ref.WeakReference;
import dev.starbridge.compat.legacy.LegacyContract;

import java.util.ArrayList;
import java.util.List;

import static dev.starbridge.compat.legacy.LegacyApis.*;
import static dev.starbridge.compat.legacy.LegacyContract.invoke;

/** Read the original machine registry without modifying recipes or granting any items. */
public final class OriginalFabrication {
    public static final int MAX_BATCHES = 6;
    public static final int MAX_INPUTS = 9;
    public static final int MAX_ALTERNATIVES = 12;
    public record Fluid(String name, int amount) {}
    public record Batch(ItemStack station, List<List<ItemStack>> inputs, List<Fluid> fluids,
                        ItemStack output, int ticks, int power) {}
    private record Machine(LegacyContract.Ref type, String station) {}
    private static final List<Machine> MACHINES = List.of(
            new Machine(ROLLING_MACHINE, "advancedrocketry:tile.rollingmachine"),
            new Machine(PRECISION_ASSEMBLER, "advancedrocketry:precisionassemblingmachine"),
            new Machine(CUTTING_MACHINE, "advancedrocketry:cuttingmachine"),
            new Machine(CRYSTALLIZER, "advancedrocketry:crystallizer"),
            new Machine(ARC_FURNACE, "advancedrocketry:tile.electricarcfurnace"),
            new Machine(HAND_PRESS, "advancedrocketry:tile.blockhandpress"));
    private static WeakReference<MinecraftServer> previous = new WeakReference<>(null);
    private static List<Batch> catalog = List.of();
    private static int readAt;
    private static boolean warned;

    private OriginalFabrication() {}

    public static List<Batch> matching(MinecraftServer server, ResourceLocation item, int metadata) {
        if (!ModList.get().isLoaded("retrofit") || metadata < 0 || metadata > 65535
                || !(item.getNamespace().equals("advancedrocketry") || item.getNamespace().equals("libvulpes"))) return List.of();
        if (previous.get() != server || server.getTickCount() - readAt >= 200) {
            previous = new WeakReference<>(server);
            readAt = server.getTickCount();
            catalog = List.of();
            try {
                var realm = OriginalStarMap.gameLoader();
                if (realm != null) catalog = read();
            } catch (ClassNotFoundException absent) {
                // The original workshop is optional when only the modern mod is installed.
            } catch (ReflectiveOperationException | RuntimeException | LinkageError unavailable) {
                if (!warned) Starbridge.LOGGER.warn("Cannot inspect original fabrication recipes", unavailable);
                warned = true;
            }
        }
        return catalog.stream().filter(batch -> item.equals(ForgeRegistries.ITEMS.getKey(batch.output().getItem()))
                        && metadata == (batch.output().getTag() == null ? 0 : batch.output().getTag().getInt("LegacyMeta")))
                .limit(MAX_BATCHES).toList();
    }

    private static List<Batch> read() throws ReflectiveOperationException {
        var recipes = invoke(RECIPES_INSTANCE, null);
        var batches = new ArrayList<Batch>();
        for (var machine : MACHINES) {
            var controller = ForgeRegistries.ITEMS.getValue(ResourceLocation.parse(machine.station()));
            if (controller == null) continue;
            var rows = (List<?>) invoke(RECIPES_FOR, recipes, LegacyContract.type(machine.type()));
            if (rows == null) continue;
            var station = new ItemStack(controller);
            for (var row : rows) {
                var outputs = (List<?>) invoke(RECIPE_OUTPUT, row);
                if (outputs.size() != 1 || !((List<?>) invoke(RECIPE_FLUID_OUTPUTS, row)).isEmpty()) continue;
                var output = (ItemStack) invoke(STACK_TO_MODERN, null, outputs.get(0));
                if (output.isEmpty()) continue;
                var inputs = items((List<?>) invoke(RECIPE_INGREDIENTS, row));
                if (inputs.isEmpty()) continue;
                var fluids = new ArrayList<Fluid>();
                for (Object stack : (List<?>) invoke(RECIPE_FLUID_INGREDIENTS, row)) {
                    var id = (String) invoke(FLUID_NAME, invoke(FLUID_OF_STACK, stack));
                    fluids.add(new Fluid(id, LegacyContract.field(FLUID_AMOUNT).getInt(stack)));
                }
                if (fluids.size() > 3 || fluids.stream().anyMatch(f -> f.amount() <= 0 || f.name().length() > 48)) continue;
                batches.add(new Batch(station, inputs, List.copyOf(fluids), output,
                        (int) invoke(RECIPE_TIME, row), (int) invoke(RECIPE_POWER, row)));
            }
        }
        return List.copyOf(batches);
    }

    private static List<List<ItemStack>> items(List<?> rows) throws ReflectiveOperationException {
        if (rows.isEmpty() || rows.size() > MAX_INPUTS) return List.of();
        var inputs = new ArrayList<List<ItemStack>>();
        for (var row : rows) {
            var alternatives = new ArrayList<ItemStack>();
            for (var legacy : (List<?>) row) {
                var stack = (ItemStack) invoke(STACK_TO_MODERN, null, legacy);
                if (!stack.isEmpty() && alternatives.stream().noneMatch(prior -> ItemStack.matches(prior, stack))) alternatives.add(stack);
                if (alternatives.size() == MAX_ALTERNATIVES) break;
            }
            if (alternatives.isEmpty()) return List.of();
            inputs.add(List.copyOf(alternatives));
        }
        return List.copyOf(inputs);
    }
}
