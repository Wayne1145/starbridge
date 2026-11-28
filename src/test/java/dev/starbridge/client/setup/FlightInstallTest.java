package dev.starbridge.client.setup;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import static dev.starbridge.client.setup.FlightInstall.State.*;
import static org.junit.jupiter.api.Assertions.*;

class FlightInstallTest {
    private static Map<String, FlightInstall.Entry> rows(Set<String> modern, FlightInstall.Realm legacy) {
        return FlightInstall.snapshot(modern::contains, legacy).stream()
                .collect(Collectors.toMap(FlightInstall.Entry::id, Function.identity()));
    }

    @Test void mainJarAloneDistinguishesMissingForgeModsFromUnreadLegacyMods() {
        var entries = rows(Set.of("starbridge"), () -> { throw new AssertionError("Retrofit is absent"); });
        assertEquals(11, entries.size());
        assertEquals(8, entries.values().stream().filter(e -> !e.optional()).count());
        assertEquals(LOADED, entries.get("starbridge").state());
        for (var id : List.of("retrofit", "valkyrienskies", "kotlinforforge", "computercraft")) {
            assertEquals(MISSING, entries.get(id).state(), id);
            assertEquals("mods", entries.get(id).directory(), id);
        }
        for (var id : List.of("dock", "advancedrocketry", "libvulpes", "galacticraft", "extraplanets", "galaxyspace")) {
            assertEquals(WAITING, entries.get(id).state(), id);
            assertEquals("retrofit/mods", entries.get(id).directory(), id);
        }
        assertEquals(FlightInstall.Group.EXPANSIONS, entries.get("galaxyspace").group());
        assertEquals(FlightInstall.Group.COMPUTER, entries.get("computercraft").group());
        assertEquals(entries.get("starbridge").source(), entries.get("dock").source());
        assertTrue(entries.values().stream().allMatch(e -> e.source() != null && e.source().startsWith("https://")));
    }

    @Test void legacyRuntimeWaitingForItsFirstDownloadDiffersFromAnUnsupportedApi() {
        var waiting = rows(Set.of("retrofit"), () -> null);
        var unsupported = rows(Set.of("retrofit"), () -> { throw new NoSuchMethodException("gameLoader"); });
        for (var id : List.of("galacticraft", "dock")) {
            assertEquals(WAITING, waiting.get(id).state());
            assertEquals(UNKNOWN, unsupported.get(id).state());
        }
    }

    @Test void activeRegistryAndRegisteredTransformerAreSeparateChecks() {
        var modern = Set.of("starbridge", "retrofit", "kotlinforforge");
        var partial = rows(modern, () -> new TestRealm(CoreOnly.class));
        assertEquals(UNKNOWN, partial.get("galacticraft").state());
        assertEquals(MISSING, partial.get("dock").state());
        var full = rows(modern, () -> new TestRealm(AllLegacy.class));
        for (var id : List.of("advancedrocketry", "libvulpes", "galacticraft", "extraplanets", "galaxyspace", "kotlinforforge")) {
            assertEquals(LOADED, full.get(id).state(), id);
        }
        assertEquals(MISSING, full.get("dock").state());
        var unsupportedFml = rows(modern, () -> new TestRealm(Object.class));
        assertEquals(UNKNOWN, unsupportedFml.get("galacticraft").state());
        assertEquals(MISSING, unsupportedFml.get("dock").state());
    }

    public static class TestRealm extends ClassLoader {
        private final Class<?> registry;
        TestRealm(Class<?> registry) { this.registry = registry; }
        @Override public Class<?> loadClass(String name) throws ClassNotFoundException {
            return switch (name) {
                case "cpw.mods.fml.common.Loader" -> registry;
                case "cpw.mods.fml.common.ModContainer" -> ModId.class;
                default -> super.loadClass(name);
            };
        }
        public List<?> getTransformers() { return List.of(); }
    }
    public record ModId(String getModId) {}
    public static class CoreOnly {
        public static CoreOnly instance() { return new CoreOnly(); }
        public List<ModId> getActiveModList() { return List.of(new ModId("GalacticraftCore")); }
    }
    public static class AllLegacy {
        public static AllLegacy instance() { return new AllLegacy(); }
        public List<ModId> getActiveModList() {
            return List.of("advancedRocketry", "libVulpes", "GalacticraftCore", "GalacticraftMars", "ExtraPlanets", "GalaxySpace")
                    .stream().map(ModId::new).toList();
        }
    }
}
