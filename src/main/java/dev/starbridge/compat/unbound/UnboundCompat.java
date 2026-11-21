package dev.starbridge.compat.unbound;

import com.mojang.logging.LogUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.fml.ModList;
import org.slf4j.Logger;

import java.lang.reflect.Method;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/** Optional boundary to Unbound 0.16.1's public Travel and Journal API. No Unbound classes load on standalone servers. */
public final class UnboundCompat {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final AtomicBoolean warned = new AtomicBoolean();
    private static final Set<String> BODIES = Set.of("moon", "mars");

    private UnboundCompat() {}

    public static boolean enabled() {
        return ModList.get().isLoaded("unbound");
    }

    /** A known native registry key. The route planner must additionally confirm discovery and the live server level. */
    public static ResourceKey<Level> destination(String route) {
        if (route == null || !BODIES.contains(route)) throw new IllegalArgumentException("Unknown Unbound route " + route);
        return ResourceKey.create(Registries.DIMENSION, new ResourceLocation("unbound", route));
    }

    public static boolean discovered(ServerPlayer player, String route) {
        if (!enabled() || route == null || !BODIES.contains(route)) return false;
        try {
            Api api = Api.Holder.INSTANCE;
            if (api == null) return false;
            Object body = api.body(route, destination(route));
            return body != null && api.hasKey(player, (String) api.discovery.invoke(body));
        } catch (ReflectiveOperationException | RuntimeException | LinkageError failure) {
            warn(failure);
            return false;
        }
    }

    /** Only call after this crew member has actually arrived. Replaying after recovery is safe. */
    public static void arrived(ServerPlayer player, String route) {
        if (!enabled() || route == null) return;
        boolean home = "earth".equals(route) || "home".equals(route);
        if (!home && !BODIES.contains(route)) return;
        ResourceKey<Level> expected = home ? Level.OVERWORLD : destination(route);
        if (!player.serverLevel().dimension().equals(expected)) return;
        try {
            Api api = Api.Holder.INSTANCE;
            if (api == null) return;
            Object body = api.body(home ? "earth" : route, expected);
            if (body == null) return;
            String key = (String) api.landingKey.invoke(body);
            if (key != null && !key.isBlank() && !api.hasKey(player, key)) api.grant.invoke(null, player, key);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError failure) {
            warn(failure);
        }
    }

    /** Pass a unique lease key outside build height and the breathing player's actual eye position. */
    static boolean holdAir(ServerLevel level, BlockPos key, Vec3 eyes) {
        try {
            Api api = Api.Holder.INSTANCE;
            if (api == null) return false;
            api.holdAir.invoke(null, level, key, eyes, 0.25D);
            return true;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError failure) {
            warn(failure);
            return false;
        }
    }

    static void releaseAir(ServerLevel level, BlockPos key) {
        try {
            Api api = Api.Holder.INSTANCE;
            if (api != null) api.releaseAir.invoke(null, level, key);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError failure) {
            warn(failure);
        }
    }

    private static void warn(Throwable failure) {
        if (warned.compareAndSet(false, true)) LOGGER.error("Unbound journal/travel interface unavailable; check the installed Unbound release", failure);
    }

    private record Api(Method lookup, Method dimension, Method discovery, Method landingKey, Method has, Method grant,
                       Method holdAir, Method releaseAir) {
        private static final class Holder {
            private static final Api INSTANCE = load();
        }

        private static Api load() {
            try {
                ClassLoader loader = UnboundCompat.class.getClassLoader();
                Class<?> travel = Class.forName("me.moltea.unbound.api.travel.Travel", false, loader);
                Class<?> body = Class.forName("me.moltea.unbound.api.travel.Destination", false, loader);
                Class<?> journal = Class.forName("me.moltea.unbound.api.journal.Journal", false, loader);
                Class<?> environment = Class.forName("me.moltea.unbound.api.life.Environment", false, loader);
                return new Api(travel.getMethod("destination", ResourceLocation.class), body.getMethod("dimension"),
                        body.getMethod("discovery"), body.getMethod("landingKey"),
                        journal.getMethod("has", ServerPlayer.class, String.class),
                        journal.getMethod("grant", ServerPlayer.class, String.class),
                        environment.getMethod("holdAirSphere", ServerLevel.class, BlockPos.class, Vec3.class, double.class),
                        environment.getMethod("releaseAir", ServerLevel.class, BlockPos.class));
            } catch (ReflectiveOperationException | RuntimeException | LinkageError failure) {
                warn(failure);
                return null;
            }
        }

        private Object body(String route, ResourceKey<Level> expected) throws ReflectiveOperationException {
            Object value = lookup.invoke(null, new ResourceLocation("unbound", route));
            if (value == null || !expected.equals(dimension.invoke(value))) {
                warn(new IllegalStateException("No matching Unbound destination for " + expected.location()));
                return null;
            }
            return value;
        }

        private boolean hasKey(ServerPlayer player, String key) throws ReflectiveOperationException {
            return key != null && !key.isBlank() && Boolean.TRUE.equals(has.invoke(null, player, key));
        }
    }
}
