package dev.starbridge.compat.rocketry;

import dev.starbridge.Starbridge;
import dev.starbridge.voyage.CabinPressure;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.server.ServerStoppedEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.common.Mod;

import dev.starbridge.compat.legacy.LegacyContract;

import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.UUID;

import static dev.starbridge.compat.legacy.LegacyApis.*;

/**
 * Let the original vacuum handler test the crew's cabin before it consumes a suit tank or applies
 * effects.
 */
@Mod.EventBusSubscriber(modid = Starbridge.ID)
public final class OriginalCabinAir {
    private static boolean attempted;
    private static Subscription subscription;

    private OriginalCabinAir() {}

    @SubscribeEvent
    public static void login(PlayerEvent.PlayerLoggedInEvent event) {
        if (attempted || !(event.getEntity() instanceof ServerPlayer player)) return;
        attempted = true;
        if (ModList.get()
                .getModContainerById("retrofit")
                .filter(mod -> mod.getModInfo().getVersion().toString().equals("0.6.2-beta"))
                .isEmpty()) return;
        try {
            Object mirror = LegacyContract.invoke(PLAYER_MIRROR, null, player, false);
            if (mirror == null) {
                attempted = false;
                return;
            }
            subscription = subscribe(mirror.getClass().getClassLoader(), player.serverLevel().getServer());
        } catch (ReflectiveOperationException | LinkageError error) {
            Starbridge.LOGGER.warn(
                    "Could not connect the cabin vent to the original Advanced Rocketry atmosphere",
                    error);
        }
    }

    private static Subscription subscribe(ClassLoader realm, MinecraftServer server)
            throws ReflectiveOperationException {
        Object probe;
        try {
            // 1.7.10 FML creates the no-arg event constructor and a separate listener list for
            // each event type.
            probe = LegacyContract.create(NEW_ATMOSPHERE_TICK);
        } catch (ClassNotFoundException noOriginalRocketry) {
            return null;
        }
        Class<?> dispatch = LegacyContract.type(EVENT_LISTENER);
        Class<?> actor = LegacyContract.type(PLAYER_MP);
        Object vacuum = LegacyContract.get(VACUUM, null);
        Object listeners = LegacyContract.invoke(TICK_LISTENERS, probe);
        int busId = LegacyContract.field(EVENT_BUS_ID).getInt(LegacyContract.get(FORGE_EVENT_BUS, null));
        Object listener =
                Proxy.newProxyInstance(
                        realm,
                        new Class<?>[] {dispatch},
                        (proxy, method, args) -> {
                            if (method.getDeclaringClass() == Object.class) {
                                return switch (method.getName()) {
                                    case "hashCode" -> System.identityHashCode(proxy);
                                    case "equals" -> proxy == args[0];
                                    default -> "Starbridge cabin atmosphere";
                                };
                            }
                            Object tick = args[0];
                            if (server.isSameThread() && LegacyContract.get(TICK_ATMOSPHERE, tick) == vacuum) {
                                Object originalPlayer = LegacyContract.get(TICK_ENTITY, tick);
                                if (actor.isInstance(originalPlayer)) {
                                    ServerPlayer crew =
                                            server.getPlayerList()
                                                    .getPlayer((UUID) LegacyContract.invoke(PLAYER_UUID, originalPlayer));
                                    if (crew != null
                                            && crew.isAlive()
                                            && !crew.isSpectator()
                                            && !crew.isCreative()
                                            && CabinPressure.breathable(crew))
                                        LegacyContract.invoke(TICK_CANCEL, tick, true);
                                }
                            }
                            return null;
                        });
        LegacyContract.invoke(LISTENERS_REGISTER, listeners, busId, LegacyContract.get(EVENT_PRIORITY_LOWEST, null), listener);
        return new Subscription(listeners, LegacyContract.method(LISTENERS_UNREGISTER), busId, listener);
    }

    private record Subscription(Object listeners, Method unregister, int busId, Object listener) {}

    @SubscribeEvent
    public static void stopped(ServerStoppedEvent event) {
        try {
            if (subscription != null)
                subscription.unregister.invoke(
                        subscription.listeners, subscription.busId, subscription.listener);
        } catch (ReflectiveOperationException error) {
            Starbridge.LOGGER.warn("Could not detach the original atmosphere listener", error);
        } finally {
            subscription = null;
            attempted = false;
        }
    }
}
