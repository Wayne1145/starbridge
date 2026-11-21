package dev.starbridge.compat.galacticraft;

import dev.starbridge.Starbridge;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraftforge.fml.ModList;

import java.lang.reflect.Method;
import java.util.*;

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
    static ClassLoader gameLoader() throws ReflectiveOperationException {
        Class<?> retrofit = Class.forName("retrofit.forge.RetrofitMod");
        Object runtime = retrofit.getMethod("runtime").invoke(null);
        return runtime == null ? null : (ClassLoader) runtime.getClass().getMethod("gameLoader").invoke(runtime);
    }
    private static void read(MinecraftServer server, Map<String, CelestialPort> result) throws ReflectiveOperationException {
        ClassLoader loader = gameLoader();
        if (loader == null) return;
        Class<?> registry = loader.loadClass("micdoodle8.mods.galacticraft.api.galaxies.GalaxyRegistry");
        Class<?> body = loader.loadClass("micdoodle8.mods.galacticraft.api.galaxies.CelestialBody");
        Class<?> fml = loader.loadClass("cpw.mods.fml.common.Loader");
        Object fmlInstance = fml.getMethod("instance").invoke(null);
        Map<String,String> owners = owners((List<?>) fml.getMethod("getActiveModList").invoke(fmlInstance), loader);
        Method real = loader.loadClass("retrofit.legacy.bridge.DimensionIds").getMethod("real",int.class,String.class);
        Class<?> dimensions = loader.loadClass("net.minecraftforge.common.DimensionManager");
        Method registered = dimensions.getMethod("isDimensionRegistered",int.class);
        Method providerAt = dimensions.getMethod("createProviderFor",int.class);
        var planets = new ArrayList<>(((Map<?,?>) registry.getMethod("getRegisteredPlanets").invoke(null)).values());
        var bodies = new ArrayList<Object>(planets);
        bodies.addAll(((Map<?,?>) registry.getMethod("getRegisteredMoons").invoke(null)).values());
        Map<String, CelestialPort> live = new TreeMap<>();
        Set<String> ambiguous = new HashSet<>();
        for (Object entry : bodies) {
            if (!Boolean.TRUE.equals(body.getMethod("getReachable").invoke(entry))) continue;
            Class<?> providerClass = (Class<?>) body.getMethod("getWorldProvider").invoke(entry);
            if (providerClass == null) continue;
            String provider = owners.get(providerClass.getPackageName());
            if (provider == null) continue;
            int personal = (int) body.getMethod("getDimensionID").invoke(entry);
            int id = (int) real.invoke(null, personal, providerClass.getName());
            if (id == 0 || id == 1 || id == -1 || !Boolean.TRUE.equals(registered.invoke(null,id))) continue;
            Object actual = providerAt.invoke(null, id);
            if (actual == null || actual.getClass() != providerClass) continue;
            int tier = (int) body.getMethod("getTierRequirement").invoke(entry);
            if (tier < 1 || tier > 100) continue;
            String name = (String) body.getMethod("getName").invoke(entry);
            String label = (String) body.getMethod("getLocalizedName").invoke(entry);
            if (label == null || label.isBlank() || label.contains(".")) label = name;
            CelestialPort port = CelestialPort.legacy(name, label, provider, tier, id);
            ServerLevel level = server.getLevel(port.dimension());
            if (level == null || !level.getChunkSource().getGenerator().getClass().getName().equals("retrofit.forge.world.LegacyDimensions$Generator")) continue;
            if (live.putIfAbsent(port.key(), port) != null) ambiguous.add(port.key());
        }
        ambiguous.forEach(live::remove);
        result.putAll(live);
    }
    private static Map<String,String> owners(List<?> mods, ClassLoader loader) throws ReflectiveOperationException {
        Class<?> container = loader.loadClass("cpw.mods.fml.common.ModContainer");
        Method id = container.getMethod("getModId"), packages = container.getMethod("getOwnedPackages");
        Map<String,String> result = new HashMap<>();
        for (Object mod : mods) {
            String name = CelestialPort.owner((String) id.invoke(mod));
            if (name.isEmpty()) continue;
            for (Object pack : (Collection<?>) packages.invoke(mod)) result.put((String) pack, name);
        }
        return result;
    }
}
