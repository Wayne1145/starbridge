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

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.UUID;

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
            Object mirror =
                    Class.forName("retrofit.forge.world.LegacyPlayers")
                            .getMethod("mirror", Player.class, boolean.class)
                            .invoke(null, player, false);
            if (mirror == null) {
                attempted = false;
                return;
            }
            subscription =
                    subscribe(mirror.getClass().getClassLoader(), player.serverLevel().getServer());
        } catch (ReflectiveOperationException | LinkageError error) {
            Starbridge.LOGGER.warn(
                    "Could not connect the cabin vent to the original Advanced Rocketry atmosphere",
                    error);
        }
    }

    private static Subscription subscribe(ClassLoader realm, MinecraftServer server)
            throws ReflectiveOperationException {
        Class<?> atmosphereTick;
        try {
            atmosphereTick =
                    realm.loadClass(
                            "zmaster587.advancedRocketry.api.event.AtmosphereEvent$AtmosphereTickEvent");
        } catch (ClassNotFoundException noOriginalRocketry) {
            return null;
        }
        String events = "cpw.mods.fml.common.eventhandler.";
        Class<?> dispatch = realm.loadClass(events + "IEventListener");
        Class<?> priorities = realm.loadClass(events + "EventPriority");
        Class<?> list = realm.loadClass(events + "ListenerList");
        Class<?> actor = realm.loadClass("net.minecraft.entity.player.EntityPlayerMP");
        Object vacuum =
                realm.loadClass("zmaster587.advancedRocketry.atmosphere.AtmosphereType")
                        .getField("VACUUM")
                        .get(null);
        Field atmosphere = atmosphereTick.getField("atmosphere"),
                entity = atmosphereTick.getField("entity");
        Method uuid = actor.getMethod("func_110124_au"),
                cancel = atmosphereTick.getMethod("setCanceled", boolean.class);
        // 1.7.10 FML creates the no-arg event constructor and a separate listener list for each
        // event type.
        Object listeners =
                atmosphereTick
                        .getMethod("getListenerList")
                        .invoke(atmosphereTick.getConstructor().newInstance());
        Object bus =
                realm.loadClass("net.minecraftforge.common.MinecraftForge")
                        .getField("EVENT_BUS")
                        .get(null);
        Field id = realm.loadClass(events + "EventBus").getDeclaredField("busID");
        id.setAccessible(true);
        int busId = id.getInt(bus);
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
                            if (server.isSameThread() && atmosphere.get(tick) == vacuum) {
                                Object originalPlayer = entity.get(tick);
                                if (actor.isInstance(originalPlayer)) {
                                    ServerPlayer crew =
                                            server.getPlayerList()
                                                    .getPlayer((UUID) uuid.invoke(originalPlayer));
                                    if (crew != null
                                            && crew.isAlive()
                                            && !crew.isSpectator()
                                            && !crew.isCreative()
                                            && CabinPressure.breathable(crew))
                                        cancel.invoke(tick, true);
                                }
                            }
                            return null;
                        });
        Method unregister = list.getMethod("unregister", int.class, dispatch);
        list.getMethod("register", int.class, priorities, dispatch)
                .invoke(listeners, busId, priorities.getField("LOWEST").get(null), listener);
        return new Subscription(listeners, unregister, busId, listener);
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
