package dev.starbridge.compat.legacy;

import dev.starbridge.Starbridge;
import net.minecraftforge.fml.ModList;

import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;

/**
 * Logs the legacy contract once the server has started, so an upstream mismatch shows up as a
 * named warning instead of a feature that silently does nothing.
 */
public final class LegacyStartupCheck {
    private LegacyStartupCheck() {}

    public static void run() {
        if (!ModList.get().isLoaded("retrofit")) return;
        try {
            if (LegacyContract.retrofitGameLoader() == null) {
                Starbridge.LOGGER.info("Legacy contract check skipped: the Retrofit 1.7.10 runtime has not started");
                return;
            }
        } catch (ReflectiveOperationException | LinkageError | RuntimeException unavailable) {
            Starbridge.LOGGER.warn("Legacy contract check skipped: Retrofit's runtime is not reachable", unavailable);
            return;
        }
        checkOxygenItems();
        List<LegacyContract.Missing> missing = LegacyApis.verify();
        int total = LegacyContract.declared().size();
        if (missing.isEmpty()) {
            Starbridge.LOGGER.info("Legacy contract: all {} required classes and members resolved", total);
            return;
        }
        Map<String, List<LegacyContract.Missing>> byModule = missing.stream()
                .collect(Collectors.groupingBy(m -> m.ref().module(), TreeMap::new, Collectors.toList()));
        for (var module : byModule.entrySet()) {
            long declared = LegacyContract.declared().stream().filter(r -> r.module().equals(module.getKey())).count();
            if (module.getValue().size() == declared) {
                // Nothing at all resolves: the mod is simply not installed.
                Starbridge.LOGGER.info("Legacy contract: {} not installed ({} references unused)", module.getKey(), declared);
                continue;
            }
            Starbridge.LOGGER.warn("Legacy contract: {} is an incompatible version, {} of {} references missing:",
                    module.getKey(), module.getValue().size(), declared);
            for (LegacyContract.Missing entry : module.getValue()) Starbridge.LOGGER.warn("  {}", entry.describe());
        }
    }

    /** The cabin vent reads original oxygen items by id; report which of those ids this game registers. */
    private static void checkOxygenItems() {
        var items = net.minecraftforge.registries.ForgeRegistries.ITEMS;
        var ids = new java.util.ArrayList<>(dev.starbridge.voyage.CabinPressure.OXYGEN_TANKS.keySet());
        ids.add(dev.starbridge.voyage.CabinPressure.LIQUID_OXYGEN);
        for (var id : ids)
            Starbridge.LOGGER.info("Cabin vent oxygen source {}: {}", id, items.containsKey(id) ? "registered" : "absent");
    }
}
