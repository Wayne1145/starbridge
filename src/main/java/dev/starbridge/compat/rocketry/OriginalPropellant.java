package dev.starbridge.compat.rocketry;

import dev.starbridge.Starbridge;
import net.minecraft.server.MinecraftServer;
import net.minecraftforge.fml.ModList;

import java.lang.ref.WeakReference;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Reads the installed original machines' fluid-only recipes on the server when a player opens the guide. */
public final class OriginalPropellant {
    public record Batches(int water, int oxygenMade, int hydrogenMade, int oxygenUsed, int hydrogenUsed, int fuel) {
        public static final Batches EMPTY = new Batches(0, 0, 0, 0, 0, 0);
    }

    private static WeakReference<MinecraftServer> previous = new WeakReference<>(null);
    private static Batches cached = Batches.EMPTY;
    private static int readAt;
    private static boolean warned;
    private OriginalPropellant() {}

    public static Batches forServer(MinecraftServer server) {
        if (!ModList.get().isLoaded("retrofit")) return Batches.EMPTY;
        if (previous.get() == server && server.getTickCount() - readAt < 200) return cached;
        previous = new WeakReference<>(server);
        readAt = server.getTickCount();
        cached = Batches.EMPTY;
        try {
            var realm = dev.starbridge.compat.galacticraft.OriginalStarMap.gameLoader();
            if (realm != null) cached = read(realm);
        } catch (ClassNotFoundException absent) {
            // Advanced Rocketry and LibVulpes are optional when running just the standalone helm.
        } catch (ReflectiveOperationException | RuntimeException | LinkageError unavailable) {
            if (!warned) Starbridge.LOGGER.warn("Cannot inspect the original propellant recipes", unavailable);
            warned = true;
        }
        return cached;
    }

    private static Batches read(ClassLoader realm) throws ReflectiveOperationException {
        var fluids = realm.loadClass("zmaster587.advancedRocketry.api.AdvancedRocketryFluids");
        Object oxygen = fluids.getField("fluidOxygen").get(null), hydrogen = fluids.getField("fluidHydrogen").get(null);
        Object fuel = fluids.getField("fluidRocketFuel").get(null);
        Object water = realm.loadClass("net.minecraftforge.fluids.FluidRegistry").getField("WATER").get(null);
        var registry = realm.loadClass("zmaster587.libVulpes.recipe.RecipesMachine");
        Object recipes = registry.getMethod("getInstance").invoke(null);
        var recipeType = realm.loadClass("zmaster587.libVulpes.interfaces.IRecipe");
        var stackType = realm.loadClass("net.minecraftforge.fluids.FluidStack");
        var split = batch(realm, recipes, registry, recipeType, stackType, "TileElectrolyser", Set.of(water), Set.of(oxygen, hydrogen));
        var mix = batch(realm, recipes, registry, recipeType, stackType, "TileChemicalReactor", Set.of(oxygen, hydrogen), Set.of(fuel));
        return new Batches(split.getOrDefault(water, 0), split.getOrDefault(oxygen, 0), split.getOrDefault(hydrogen, 0),
                mix.getOrDefault(oxygen, 0), mix.getOrDefault(hydrogen, 0), mix.getOrDefault(fuel, 0));
    }

    private static Map<Object, Integer> batch(ClassLoader realm, Object recipes, Class<?> registry, Class<?> recipeType,
            Class<?> stackType, String machineName, Set<Object> expectedInputs, Set<Object> expectedOutputs) throws ReflectiveOperationException {
        var machine = realm.loadClass("zmaster587.advancedRocketry.tile.multiblock.machine." + machineName);
        var rows = (List<?>) registry.getMethod("getRecipes", Class.class).invoke(recipes, machine);
        if (rows == null) return Map.of();
        for (var row : rows) {
            if (!((List<?>) recipeType.getMethod("getIngredients").invoke(row)).isEmpty()
                    || !((List<?>) recipeType.getMethod("getOutput").invoke(row)).isEmpty()) continue;
            var inputs = amounts(stackType, (List<?>) recipeType.getMethod("getFluidIngredients").invoke(row));
            var outputs = amounts(stackType, (List<?>) recipeType.getMethod("getFluidOutputs").invoke(row));
            if (inputs.keySet().equals(expectedInputs) && outputs.keySet().equals(expectedOutputs)) {
                inputs.putAll(outputs);
                return inputs;
            }
        }
        return Map.of();
    }

    private static Map<Object, Integer> amounts(Class<?> type, List<?> stacks) throws ReflectiveOperationException {
        Map<Object, Integer> result = new HashMap<>();
        for (var stack : stacks) {
            Object fluid = type.getMethod("getFluid").invoke(stack);
            int amount = type.getField("amount").getInt(stack);
            if (fluid == null || amount <= 0) return Map.of();
            result.merge(fluid, amount, Math::addExact);
        }
        return result;
    }
}
