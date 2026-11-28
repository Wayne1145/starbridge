package dev.starbridge.compat.galacticraft;

import dev.starbridge.Starbridge;
import dev.starbridge.compat.galacticraft.OriginalNavigationTech.Sample;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Collection;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Set;

/** Reads the player's existing NASA Workbench pages from the original Galacticraft save. */
public final class OriginalWorkbenchNavigation {
    private record Reader(ClassLoader loader, Method mirror, Method getStats, Field unlocked,
                          Map<Object, Sample> registeredPages) {}

    private static Reader reader;
    private static boolean warned;

    private OriginalWorkbenchNavigation() {}

    public static Set<Sample> unlocked(ServerPlayer player) {
        var examples = OriginalNavigationTech.examples();
        if (examples.isEmpty()) return Set.of();
        try {
            var loader = OriginalStarMap.gameLoader();
            if (loader == null) return Set.of();
            if (reader == null || reader.loader() != loader) reader = readRegistry(loader, examples);
            var legacyPlayer = reader.mirror().invoke(null, player, false);
            if (legacyPlayer == null) return Set.of();
            var stats = reader.getStats().invoke(null, legacyPlayer);
            if (stats == null) return Set.of();

            Set<Sample> known = new HashSet<>();
            for (var page : (Collection<?>) reader.unlocked().get(stats)) {
                var sample = reader.registeredPages().get(page);
                if (sample != null) known.add(sample);
            }
            return Set.copyOf(known);
        } catch (ReflectiveOperationException | LinkageError | RuntimeException failure) {
            if (!warned) Starbridge.LOGGER.warn("Unable to read original NASA Workbench pages", failure);
            warned = true;
            return Set.of();
        }
    }

    private static Reader readRegistry(ClassLoader loader, Collection<Sample> examples)
            throws ReflectiveOperationException {
        var legacyStack = loader.loadClass("net.minecraft.item.ItemStack");
        var toLegacy = Class.forName("retrofit.forge.world.LegacyStacks")
                .getMethod("toLegacy", ItemStack.class);
        var match = loader.loadClass("micdoodle8.mods.galacticraft.api.recipe.SchematicRegistry")
                .getMethod("getMatchingRecipeForItemStack", legacyStack);
        // Galacticraft restores pages from this same registry when it loads player data.
        Map<Object, Sample> registeredPages = new IdentityHashMap<>();
        for (var sample : examples) {
            if (sample.rocket()) continue;
            var stack = sample.stack();
            if (stack.isEmpty()) continue;
            var page = match.invoke(null, toLegacy.invoke(null, stack));
            if (page != null) registeredPages.putIfAbsent(page, sample);
        }
        var mirror = Class.forName("retrofit.forge.world.LegacyPlayers")
                .getMethod("mirror", Player.class, boolean.class);
        var stats = loader.loadClass("micdoodle8.mods.galacticraft.core.entities.player.GCPlayerStats");
        return new Reader(loader, mirror,
                stats.getMethod("get", loader.loadClass("net.minecraft.entity.player.EntityPlayerMP")),
                stats.getField("unlockedSchematics"), registeredPages);
    }
}
