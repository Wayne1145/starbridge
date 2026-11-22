package dev.starbridge.compat.rocketry;

import dev.starbridge.Starbridge;
import dev.starbridge.compat.galacticraft.OriginalStarMap;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.registries.ForgeRegistries;

import java.lang.ref.WeakReference;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/** Read the original machine registry without modifying recipes or granting any items. */
public final class OriginalFabrication {
    public static final int MAX_BATCHES = 6;
    public static final int MAX_INPUTS = 9;
    public static final int MAX_ALTERNATIVES = 12;
    public record Fluid(String name, int amount) {}
    public record Batch(ItemStack station, List<List<ItemStack>> inputs, List<Fluid> fluids,
                        ItemStack output, int ticks, int power) {}
    private record Machine(String tile, String station) {}
    private static final List<Machine> MACHINES = List.of(
            new Machine("TileRollingMachine", "advancedrocketry:tile.rollingmachine"),
            new Machine("TilePrecisionAssembler", "advancedrocketry:precisionassemblingmachine"),
            new Machine("TileCuttingMachine", "advancedrocketry:cuttingmachine"),
            new Machine("TileCrystallizer", "advancedrocketry:crystallizer"),
            new Machine("TileElectricArcFurnace", "advancedrocketry:tile.electricarcfurnace"));
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
                if (realm != null) catalog = read(realm);
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

    private static List<Batch> read(ClassLoader realm) throws ReflectiveOperationException {
        var registry = realm.loadClass("zmaster587.libVulpes.recipe.RecipesMachine");
        var recipes = registry.getMethod("getInstance").invoke(null);
        var getRecipes = registry.getMethod("getRecipes", Class.class);
        var recipe = realm.loadClass("zmaster587.libVulpes.interfaces.IRecipe");
        var modern = Class.forName("retrofit.forge.world.LegacyStacks").getMethod("toModern", Object.class);
        var fluidStack = realm.loadClass("net.minecraftforge.fluids.FluidStack");
        var getFluid = fluidStack.getMethod("getFluid");
        var amount = fluidStack.getField("amount");
        var name = realm.loadClass("net.minecraftforge.fluids.Fluid").getMethod("getName");
        var batches = new ArrayList<Batch>();
        for (var machine : MACHINES) {
            var controller = ForgeRegistries.ITEMS.getValue(ResourceLocation.parse(machine.station()));
            if (controller == null) continue;
            var type = realm.loadClass("zmaster587.advancedRocketry.tile.multiblock.machine." + machine.tile());
            var rows = (List<?>) getRecipes.invoke(recipes, type);
            if (rows == null) continue;
            var station = new ItemStack(controller);
            for (var row : rows) {
                var outputs = (List<?>) recipe.getMethod("getOutput").invoke(row);
                if (outputs.size() != 1 || !((List<?>) recipe.getMethod("getFluidOutputs").invoke(row)).isEmpty()) continue;
                var output = (ItemStack) modern.invoke(null, outputs.get(0));
                if (output.isEmpty()) continue;
                var inputs = items((List<?>) recipe.getMethod("getIngredients").invoke(row), modern);
                if (inputs.isEmpty()) continue;
                var fluids = new ArrayList<Fluid>();
                for (Object stack : (List<?>) recipe.getMethod("getFluidIngredients").invoke(row)) {
                    var id = (String) name.invoke(getFluid.invoke(stack));
                    fluids.add(new Fluid(id, amount.getInt(stack)));
                }
                if (fluids.size() > 3 || fluids.stream().anyMatch(f -> f.amount() <= 0 || f.name().length() > 48)) continue;
                batches.add(new Batch(station, inputs, List.copyOf(fluids), output,
                        (int) recipe.getMethod("getTime").invoke(row), (int) recipe.getMethod("getPower").invoke(row)));
            }
        }
        return List.copyOf(batches);
    }

    private static List<List<ItemStack>> items(List<?> rows, Method modern) throws ReflectiveOperationException {
        if (rows.isEmpty() || rows.size() > MAX_INPUTS) return List.of();
        var inputs = new ArrayList<List<ItemStack>>();
        for (var row : rows) {
            var alternatives = new ArrayList<ItemStack>();
            for (var legacy : (List<?>) row) {
                var stack = (ItemStack) modern.invoke(null, legacy);
                if (!stack.isEmpty() && alternatives.stream().noneMatch(prior -> ItemStack.matches(prior, stack))) alternatives.add(stack);
                if (alternatives.size() == MAX_ALTERNATIVES) break;
            }
            if (alternatives.isEmpty()) return List.of();
            inputs.add(List.copyOf(alternatives));
        }
        return List.copyOf(inputs);
    }
}
