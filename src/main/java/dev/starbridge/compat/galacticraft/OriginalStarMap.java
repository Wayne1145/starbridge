package dev.starbridge.compat.galacticraft;

import dev.starbridge.Starbridge;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraftforge.fml.ModList;

import java.util.*;

import static dev.starbridge.compat.legacy.LegacyApis.*;
import static dev.starbridge.compat.legacy.LegacyContract.invoke;

/** Reads the original GC3 registry on its own class loader, after both sides have started. */
public final class OriginalStarMap {
    private static MinecraftServer cachedServer;
    private static Map<String, CelestialPort> cached = Map.of();
    private static int nextRefresh;
    private static boolean warned;
    private OriginalStarMap() {}

    public static Map<String, CelestialPort> ports(MinecraftServer server) {
        if (!server.isSameThread()) throw new IllegalStateException("Read star map on server thread");
        if (server == cachedServer && server.getTickCount() < nextRefresh) return cached;
        var result = new LinkedHashMap<String, CelestialPort>();
        result.put("home", CelestialPort.HOME);
        for (String name : List.of("moon", "mars", "belt")) result.put(name, CelestialPort.missing(name));
        if (ModList.get().isLoaded("retrofit")) {
            try { read(server, result); }
            catch (ReflectiveOperationException | LinkageError | RuntimeException e) {
                if (!warned) { Starbridge.LOGGER.warn("Original Galacticraft star map not available", e); warned = true; }
            }
        }
        cachedServer = server;
        cached = Collections.unmodifiableMap(result);
        nextRefresh = server.getTickCount() + 600;
        return cached;
    }
    public static void clear() { cachedServer = null; cached = Map.of(); nextRefresh = 0; warned = false; }
    public static CelestialPort byLevel(ServerLevel level) {
        return ports(level.getServer()).values().stream().filter(p -> level.dimension().equals(p.dimension())).findFirst().orElse(null);
    }
    public static ClassLoader gameLoader() throws ReflectiveOperationException {
        return dev.starbridge.compat.legacy.LegacyContract.retrofitGameLoader();
    }
    private static void read(MinecraftServer server, Map<String, CelestialPort> result) throws ReflectiveOperationException {
        ClassLoader loader = gameLoader();
        if (loader == null) return;
        Map<String,String> owners = owners((List<?>) invoke(FML_ACTIVE_MODS, invoke(FML_INSTANCE, null)));
        var bodies = new ArrayList<Object>(((Map<?,?>) invoke(GC_PLANETS, null)).values());
        bodies.addAll(((Map<?,?>) invoke(GC_MOONS, null)).values());
        Map<String, CelestialPort> live = new TreeMap<>();
        Set<String> ambiguous = new HashSet<>();
        for (Object entry : bodies) {
            if (!Boolean.TRUE.equals(invoke(BODY_REACHABLE, entry))) continue;
            Class<?> providerClass = (Class<?>) invoke(BODY_PROVIDER, entry);
            if (providerClass == null) continue;
            String provider = owners.get(providerClass.getPackageName());
            if (provider == null) continue;
            int personal = (int) invoke(BODY_DIMENSION, entry);
            int id = (int) invoke(REAL_DIMENSION, null, personal, providerClass.getName());
            if (id == 0 || id == 1 || id == -1 || !Boolean.TRUE.equals(invoke(DIMENSION_REGISTERED, null, id))) continue;
            Object actual = invoke(DIMENSION_PROVIDER, null, id);
            if (actual == null || actual.getClass() != providerClass) continue;
            int tier = (int) invoke(BODY_TIER, entry);
            if (tier < 1 || tier > 100) continue;
            String name = (String) invoke(BODY_NAME, entry);
            String label = (String) invoke(BODY_LOCALIZED_NAME, entry);
            if (label == null || label.isBlank() || label.contains(".")) label = name;
            CelestialPort port = CelestialPort.legacy(name, label, provider, tier, id);
            ServerLevel level = server.getLevel(port.dimension());
            if (level == null || !level.getChunkSource().getGenerator().getClass().getName().equals("retrofit.forge.world.LegacyDimensions$Generator")) continue;
            if (live.putIfAbsent(port.key(), port) != null) ambiguous.add(port.key());
        }
        ambiguous.forEach(live::remove);
        result.putAll(live);
    }
    private static Map<String,String> owners(List<?> mods) throws ReflectiveOperationException {
        Map<String,String> result = new HashMap<>();
        for (Object mod : mods) {
            String name = CelestialPort.owner((String) invoke(MOD_ID, mod));
            if (name.isEmpty()) continue;
            for (Object pack : (Collection<?>) invoke(MOD_PACKAGES, mod)) result.put((String) pack, name);
        }
        return result;
    }
}
