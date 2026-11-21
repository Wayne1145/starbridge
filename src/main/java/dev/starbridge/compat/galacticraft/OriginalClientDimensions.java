package dev.starbridge.compat.galacticraft;

import dev.starbridge.Starbridge;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.event.level.LevelEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.common.Mod;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/** A remote client needs original planetary providers before Retrofit creates its legacy client world. */
@Mod.EventBusSubscriber(modid = Starbridge.ID, value = Dist.CLIENT)
public final class OriginalClientDimensions {
    private static final Map<Integer,Integer> installed = new HashMap<>();
    private static boolean warned;

    private OriginalClientDimensions() {}

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void load(LevelEvent.Load event) {
        if (event.getLevel() instanceof ClientLevel world) prepare(Set.of(world.dimension()));
    }

    @SubscribeEvent
    public static void login(ClientPlayerNetworkEvent.LoggingIn event) {
        var connection = Minecraft.getInstance().getConnection();
        if (connection != null) prepare(connection.levels());
    }

    private static void prepare(Set<ResourceKey<Level>> advertised) {
        var remote = Minecraft.getInstance();
        if (remote.hasSingleplayerServer() || !supported() || advertised.isEmpty()) return;
        try {
            ClassLoader loader = OriginalStarMap.gameLoader();
            if (loader == null) return;
            Class<?> registry = loader.loadClass("micdoodle8.mods.galacticraft.api.galaxies.GalaxyRegistry");
            Class<?> body = loader.loadClass("micdoodle8.mods.galacticraft.api.galaxies.CelestialBody");
            Class<?> manager = loader.loadClass("net.minecraftforge.common.DimensionManager");
            var ids = loader.loadClass("retrofit.legacy.bridge.DimensionIds");
            var realId = ids.getMethod("real", int.class, String.class);
            var realType = ids.getMethod("realType", int.class, String.class);
            var isRegistered = manager.getMethod("isDimensionRegistered", int.class);
            var register = manager.getMethod("registerDimension", int.class, int.class);
            var declaredTypes = manager.getDeclaredField("providers");
            declaredTypes.setAccessible(true);
            Map<?,?> types = (Map<?,?>) declaredTypes.get(null);
            Set<Object> reachable = new HashSet<>(((Map<?,?>) registry.getMethod("getRegisteredPlanets").invoke(null)).values());
            reachable.addAll(((Map<?,?>) registry.getMethod("getRegisteredMoons").invoke(null)).values());
            var reachableBody = body.getMethod("getReachable");
            var getProvider = body.getMethod("getWorldProvider");
            var dimensionId = body.getMethod("getDimensionID");
            for (Object candidate : reachable) {
                if (!Boolean.TRUE.equals(reachableBody.invoke(candidate))) continue;
                Class<?> provider = (Class<?>) getProvider.invoke(candidate);
                if (provider == null) continue;
                int local = (int) dimensionId.invoke(candidate);
                int dim = (int) realId.invoke(null, local, provider.getName());
                int type = (int) realType.invoke(null, local, provider.getName());
                if (types.get(type) != provider || (boolean) isRegistered.invoke(null, dim)) continue;
                ResourceLocation key = new ResourceLocation("retrofit", "legacy_dim_" + dim);
                if (advertised.stream().noneMatch(level -> key.equals(level.location()))) continue;
                register.invoke(null, dim, type);
                installed.put(dim,type);
                Starbridge.LOGGER.info("Registered original client provider {} for {}", provider.getSimpleName(), key);
            }
        } catch (ClassNotFoundException absent) {
            // The optional original Galacticraft JAR is not installed.
        } catch (ReflectiveOperationException | LinkageError | RuntimeException ex) {
            if (!warned) { Starbridge.LOGGER.warn("Could not register original client dimension providers", ex); warned = true; }
        }
    }

    private static boolean supported() {
        return ModList.get().getModContainerById("retrofit").filter(mod -> "0.6.2-beta".equals(mod.getModInfo().getVersion().toString())).isPresent();
    }

    @SubscribeEvent
    public static void logout(ClientPlayerNetworkEvent.LoggingOut event) {
        if (installed.isEmpty()) return;
        try {
            ClassLoader loader = OriginalStarMap.gameLoader();
            if (loader == null) return;
            Class<?> manager = loader.loadClass("net.minecraftforge.common.DimensionManager");
            var registered = manager.getMethod("isDimensionRegistered", int.class);
            var kind = manager.getMethod("getProviderType", int.class);
            var remove = manager.getMethod("unregisterDimension", int.class);
            for (var added : installed.entrySet()) {
                if ((boolean) registered.invoke(null, added.getKey()) && added.getValue().equals(kind.invoke(null, added.getKey())))
                    remove.invoke(null, added.getKey());
            }
        } catch (ReflectiveOperationException | LinkageError | RuntimeException e) {
            Starbridge.LOGGER.warn("Could not clear original client dimension registrations",e);
        } finally {
            installed.clear();
            warned = false;
        }
    }
}
