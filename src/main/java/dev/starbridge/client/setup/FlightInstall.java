package dev.starbridge.client.setup;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.starbridge.compat.galacticraft.OriginalStarMap;
import net.minecraftforge.fml.ModList;

import java.io.IOException;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Predicate;

/** Installation hints for this client, paired with the components its two loaders actually activated. */
public final class FlightInstall {
    public enum Group { REQUIRED, EXPANSIONS, COMPUTER }
    public enum State { LOADED, MISSING, WAITING, UNKNOWN }
    public record Entry(Group group, String id, String name, State state, String file, String directory, String source) {
        public boolean optional() { return group != Group.REQUIRED; }
        private Entry withState(State value) { return new Entry(group, id, name, value, file, directory, source); }
    }

    private static final String LOCK = "/META-INF/starbridge/original-spaceflight.lock.json";
    private static final String DOCK_TRANSFORMER = "dev.starbridge.legacydock.DockTransformer";
    private static final Map<String, List<String>> LEGACY_IDS = Map.of(
            "advancedrocketry", List.of("advancedRocketry"), "libvulpes", List.of("libVulpes"),
            "galacticraft", List.of("GalacticraftCore", "GalacticraftMars"),
            "extraplanets", List.of("ExtraPlanets"), "galaxyspace", List.of("GalaxySpace"));
    private static final List<Entry> CATALOG = readCatalog();

    private FlightInstall() {}

    public static List<Entry> snapshot() {
        // The pinned Kotlin for Forge bundle registers its embedded kffmod under kotlinforforge.
        return snapshot(ModList.get()::isLoaded, OriginalStarMap::gameLoader);
    }

    @FunctionalInterface interface Realm { ClassLoader get() throws ReflectiveOperationException; }

    static List<Entry> snapshot(Predicate<String> forgeLoaded, Realm realm) {
        Legacy legacy = inspectLegacy(forgeLoaded.test("retrofit"), realm);
        var rows = new ArrayList<Entry>(CATALOG.size());
        for (Entry entry : CATALOG) {
            State state;
            if (entry.id().equals("dock")) state = legacy.dock();
            else if (LEGACY_IDS.containsKey(entry.id())) state = legacy.status(LEGACY_IDS.get(entry.id()));
            else state = forgeLoaded.test(entry.id()) ? State.LOADED : State.MISSING;
            rows.add(entry.withState(state));
        }
        return List.copyOf(rows);
    }

    private record Legacy(Set<String> active, State registry, State dock) {
        static Legacy unavailable(State reason) { return new Legacy(Set.of(), reason, reason); }
        State status(List<String> ids) {
            if (registry != State.LOADED) return registry;
            if (active.containsAll(ids)) return State.LOADED;
            // A partial Galacticraft install must not invite the player to add a second copy of the same file.
            return ids.stream().anyMatch(active::contains) ? State.UNKNOWN : State.MISSING;
        }
    }

    private static Legacy inspectLegacy(boolean retrofit, Realm realm) {
        if (!retrofit) return Legacy.unavailable(State.WAITING);
        ClassLoader loader;
        try {
            loader = realm.get();
        } catch (ReflectiveOperationException | LinkageError | RuntimeException unavailable) {
            return Legacy.unavailable(State.UNKNOWN);
        }
        if (loader == null) return Legacy.unavailable(State.WAITING);
        State dock = inspectDock(loader);
        try {
            var fml = loader.loadClass("cpw.mods.fml.common.Loader");
            var instance = fml.getMethod("instance").invoke(null);
            var mods = (List<?>) fml.getMethod("getActiveModList").invoke(instance);
            var id = loader.loadClass("cpw.mods.fml.common.ModContainer").getMethod("getModId");
            var active = new HashSet<String>();
            for (Object mod : mods) active.add((String) id.invoke(mod));
            return new Legacy(Set.copyOf(active), State.LOADED, dock);
        } catch (ReflectiveOperationException | LinkageError | RuntimeException unavailable) {
            return new Legacy(Set.of(), State.UNKNOWN, dock);
        }
    }

    private static State inspectDock(ClassLoader loader) {
        try {
            // FML 1.7.10 registers each coremod transformer directly on the LaunchClassLoader.
            var registered = (List<?>) loader.getClass().getMethod("getTransformers").invoke(loader);
            return registered.stream().anyMatch(t -> t.getClass().getName().equals(DOCK_TRANSFORMER))
                    ? State.LOADED : State.MISSING;
        } catch (ReflectiveOperationException | LinkageError | RuntimeException unavailable) {
            return State.UNKNOWN;
        }
    }

    private static List<Entry> readCatalog() {
        var rows = new ArrayList<Entry>();
        try (var reader = new InputStreamReader(Objects.requireNonNull(FlightInstall.class.getResourceAsStream(LOCK), LOCK),
                StandardCharsets.UTF_8)) {
            var lock = JsonParser.parseReader(reader).getAsJsonObject();
            for (var element : lock.getAsJsonArray("project")) {
                var row = element.getAsJsonObject();
                boolean dock = row.get("file").getAsString().startsWith("starbridge-dock-");
                rows.add(artifact(row, Group.REQUIRED, dock ? "dock" : "starbridge", dock ? "Starbridge Dock" : "Starbridge"));
            }
            for (var element : lock.getAsJsonArray("dependencies")) {
                var row = element.getAsJsonObject();
                String name = row.get("name").getAsString();
                String id = switch (name) {
                    case "Galacticraft Legacy" -> "galacticraft";
                    case "CC:Tweaked" -> "computercraft";
                    default -> name.replace(" ", "").toLowerCase(Locale.ROOT);
                };
                Group group = Group.valueOf(row.get("group").getAsString().toUpperCase(Locale.ROOT));
                rows.add(artifact(row, group, id, name));
            }
        } catch (IOException ex) {
            throw new UncheckedIOException("Could not read the packaged flight manifest", ex);
        }
        return List.copyOf(rows);
    }

    private static Entry artifact(JsonObject row, Group group, String id, String name) {
        return new Entry(group, id, name, State.UNKNOWN, row.get("file").getAsString(), row.get("directory").getAsString(),
                row.has("source") ? row.get("source").getAsString() : null);
    }
}
