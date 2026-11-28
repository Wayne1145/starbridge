package dev.starbridge.compat.rocketry;

import dev.starbridge.Starbridge;
import net.minecraft.server.MinecraftServer;
import net.minecraftforge.fml.ModList;

import java.lang.ref.WeakReference;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import dev.starbridge.compat.legacy.LegacyContract;

import static dev.starbridge.compat.legacy.LegacyApis.*;
import static dev.starbridge.compat.legacy.LegacyContract.invoke;

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
            if (realm != null) cached = read();
        } catch (ClassNotFoundException absent) {
            // Advanced Rocketry and LibVulpes are optional when running just the standalone helm.
        } catch (ReflectiveOperationException | RuntimeException | LinkageError unavailable) {
            if (!warned) Starbridge.LOGGER.warn("Cannot inspect the original propellant recipes", unavailable);
            warned = true;
        }
        return cached;
    }

    private static Batches read() throws ReflectiveOperationException {
        Object oxygen = LegacyContract.get(FLUID_OXYGEN, null), hydrogen = LegacyContract.get(FLUID_HYDROGEN, null);
        Object fuel = LegacyContract.get(FLUID_ROCKET_FUEL, null);
        Object water = LegacyContract.get(FLUID_WATER, null);
        Object recipes = invoke(RECIPES_INSTANCE, null);
        var split = batch(recipes, ELECTROLYSER, Set.of(water), Set.of(oxygen, hydrogen));
        var mix = batch(recipes, CHEMICAL_REACTOR, Set.of(oxygen, hydrogen), Set.of(fuel));
        return new Batches(split.getOrDefault(water, 0), split.getOrDefault(oxygen, 0), split.getOrDefault(hydrogen, 0),
                mix.getOrDefault(oxygen, 0), mix.getOrDefault(hydrogen, 0), mix.getOrDefault(fuel, 0));
    }

    private static Map<Object, Integer> batch(Object recipes, LegacyContract.Ref machine,
            Set<Object> expectedInputs, Set<Object> expectedOutputs) throws ReflectiveOperationException {
        var rows = (List<?>) invoke(RECIPES_FOR, recipes, LegacyContract.type(machine));
        if (rows == null) return Map.of();
        for (var row : rows) {
            if (!((List<?>) invoke(RECIPE_INGREDIENTS, row)).isEmpty()
                    || !((List<?>) invoke(RECIPE_OUTPUT, row)).isEmpty()) continue;
            var inputs = amounts((List<?>) invoke(RECIPE_FLUID_INGREDIENTS, row));
            var outputs = amounts((List<?>) invoke(RECIPE_FLUID_OUTPUTS, row));
            if (inputs.keySet().equals(expectedInputs) && outputs.keySet().equals(expectedOutputs)) {
                inputs.putAll(outputs);
                return inputs;
            }
        }
        return Map.of();
    }

    private static Map<Object, Integer> amounts(List<?> stacks) throws ReflectiveOperationException {
        Map<Object, Integer> result = new HashMap<>();
        for (var stack : stacks) {
            Object fluid = invoke(FLUID_OF_STACK, stack);
            int amount = LegacyContract.field(FLUID_AMOUNT).getInt(stack);
            if (fluid == null || amount <= 0) return Map.of();
            result.merge(fluid, amount, Math::addExact);
        }
        return result;
    }
}
