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

import static dev.starbridge.compat.legacy.LegacyApis.*;
import static dev.starbridge.compat.legacy.LegacyContract.invoke;

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
            Map<?,?> types = (Map<?,?>) dev.starbridge.compat.legacy.LegacyContract.get(DIMENSION_PROVIDERS, null);
            Set<Object> reachable = new HashSet<>(((Map<?,?>) invoke(GC_PLANETS, null)).values());
            reachable.addAll(((Map<?,?>) invoke(GC_MOONS, null)).values());
            for (Object candidate : reachable) {
                if (!Boolean.TRUE.equals(invoke(BODY_REACHABLE, candidate))) continue;
                Class<?> provider = (Class<?>) invoke(BODY_PROVIDER, candidate);
                if (provider == null) continue;
                int local = (int) invoke(BODY_DIMENSION, candidate);
                int dim = (int) invoke(REAL_DIMENSION, null, local, provider.getName());
                int type = (int) invoke(REAL_DIMENSION_TYPE, null, local, provider.getName());
                if (types.get(type) != provider || (boolean) invoke(DIMENSION_REGISTERED, null, dim)) continue;
                ResourceLocation key = new ResourceLocation("retrofit", "legacy_dim_" + dim);
                if (advertised.stream().noneMatch(level -> key.equals(level.location()))) continue;
                invoke(DIMENSION_REGISTER, null, dim, type);
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
            for (var added : installed.entrySet()) {
                if ((boolean) invoke(DIMENSION_REGISTERED, null, added.getKey()) && added.getValue().equals(invoke(DIMENSION_PROVIDER_TYPE, null, added.getKey())))
                    invoke(DIMENSION_UNREGISTER, null, added.getKey());
            }
        } catch (ReflectiveOperationException | LinkageError | RuntimeException e) {
            Starbridge.LOGGER.warn("Could not clear original client dimension registrations",e);
        } finally {
            installed.clear();
            warned = false;
        }
    }
}
