package dev.starbridge.flightqa;

import com.google.gson.Gson;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.living.*;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.io.IOException;
import java.lang.reflect.*;
import java.nio.file.*;
import java.util.*;

/**
 * Read-only recorder for the disposable two-crew launch fixture. Existing original player mirrors
 * are observed in place.
 */
@Mod.EventBusSubscriber(modid = "starbridge_flightqa")
public final class FlightDamageTrace {
    private static final Gson JSON = new Gson();
    private static final Path FILE = Path.of("crew-health.jsonl");
    private static final Map<UUID, Object> mirrors = new HashMap<>();
    private static final Map<UUID, Float> previous = new HashMap<>();
    private static boolean attached;
    private static final int MAX_ROWS = 6000;
    private static int rows;

    static void bind(ServerPlayer p, Object mirror) {
        if (!MissionSignals.JOINED) return;
        mirrors.put(p.getUUID(), mirror);
        if (!attached) {
            attached = true;
            try {
                observeOriginal(mirror.getClass().getClassLoader(), p);
            } catch (ReflectiveOperationException | LinkageError e) {
                write(Map.of("phase", "original-hook-error", "error", e.toString()));
            }
        }
        write(sample(p, "suit-installed"));
    }

    private static boolean crew(ServerPlayer p) {
        return MissionSignals.JOINED && mirrors.containsKey(p.getUUID());
    }

    private static void write(Map<String, ?> data) {
        if (++rows > MAX_ROWS) return;
        try {
            Files.writeString(
                    FILE,
                    JSON.toJson(data) + "\n",
                    StandardOpenOption.CREATE,
                    StandardOpenOption.APPEND);
        } catch (IOException e) {
            throw new IllegalStateException("Could not record crew health", e);
        }
    }

    private static Map<String, Object> sample(ServerPlayer p, String phase) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("tick", p.serverLevel().getServer().getTickCount());
        m.put("phase", phase);
        m.put("who", p.getGameProfile().getName());
        m.put("world", p.level().dimension().location().toString());
        m.put("position", p.position().toString());
        m.put("health", p.getHealth());
        m.put("oxygen", p.getAirSupply());
        m.put("fallDistance", p.fallDistance);
        m.put("inWall", p.isInWall());
        m.put("food", p.getFoodData().getFoodLevel());
        m.put("frozen", p.getTicksFrozen());
        var suits = new ArrayList<String>();
        for (EquipmentSlot slot :
                List.of(
                        EquipmentSlot.HEAD,
                        EquipmentSlot.CHEST,
                        EquipmentSlot.LEGS,
                        EquipmentSlot.FEET)) {
            ItemStack item = p.getItemBySlot(slot);
            suits.add(
                    MissionSignals.id(item)
                            + " "
                            + (item.hasTag() ? item.getTag().toString() : ""));
        }
        m.put("suit", suits);
        m.put(
                "effects",
                p.getActiveEffects().stream()
                        .map(
                                e ->
                                        e.getEffect().getDescriptionId()
                                                + ":"
                                                + e.getAmplifier()
                                                + ":"
                                                + e.getDuration())
                        .toList());
        Object legacy = mirrors.get(p.getUUID());
        if (legacy != null) {
            try {
                var type = legacy.getClass();
                m.put("oldHP", type.getMethod("func_110143_aJ").invoke(legacy));
                m.put("oldDimension", type.getField("field_71093_bK").get(legacy));
                var realm = type.getClassLoader();
                var pl = realm.loadClass("net.minecraft.entity.player.EntityPlayerMP");
                var gs =
                        realm.loadClass(
                                "micdoodle8.mods.galacticraft.core.entities.player.GCPlayerStats");
                var stats = gs.getMethod("get", pl).invoke(null, legacy);
                var data = new LinkedHashMap<>();
                for (var field :
                        List.of(
                                "airRemaining",
                                "airRemaining2",
                                "oxygenSetupValid",
                                "thermalLevel"))
                    try {
                        data.put(field, gs.getField(field).get(stats));
                    } catch (NoSuchFieldException ignored) {
                    }
                var inv = gs.getField("extendedInventory").get(stats);
                var get = inv.getClass().getMethod("func_70301_a", int.class);
                var extended = new ArrayList<String>();
                for (int i = 0; i < 10; i++) extended.add(String.valueOf(get.invoke(inv, i)));
                data.put("equipment", extended);
                m.put("GC", data);
                var worn = new ArrayList<String>();
                for (int i = 1; i <= 4; i++)
                    worn.add(
                            String.valueOf(
                                    type.getMethod("func_71124_b", int.class).invoke(legacy, i)));
                m.put("oldArmor", worn);
            } catch (ReflectiveOperationException | LinkageError e) {
                m.put("oldObservationError", e.toString());
            }
        }
        return m;
    }

    private static void event(
            net.minecraft.world.entity.LivingEntity living,
            DamageSource source,
            float amount,
            String phase) {
        if (!(living instanceof ServerPlayer p) || !crew(p)) return;
        var m = sample(p, phase);
        m.put("source", source.getMsgId());
        m.put(
                "type",
                p.level()
                        .registryAccess()
                        .registryOrThrow(net.minecraft.core.registries.Registries.DAMAGE_TYPE)
                        .getKey(source.type())
                        .toString());
        m.put("amount", amount);
        m.put("direct", String.valueOf(source.getDirectEntity()));
        m.put("caller", stack());
        write(m);
    }

    private static List<String> stack() {
        return StackWalker.getInstance()
                .walk(
                        frames ->
                                frames.map(Object::toString)
                                        .filter(
                                                s ->
                                                        !s.contains("FlightDamageTrace")
                                                                && (s.contains("retrofit.")
                                                                        || s.toLowerCase(
                                                                                        Locale.ROOT)
                                                                                .contains(
                                                                                        "galaxyspace")
                                                                        || s.toLowerCase(
                                                                                        Locale.ROOT)
                                                                                .contains(
                                                                                        "extraplanets")
                                                                        || s.contains("zmaster587.")
                                                                        || s.contains("micdoodle8.")
                                                                        || s.contains(
                                                                                "net.minecraft.world.")
                                                                        || s.contains(
                                                                                "net.minecraft.server.level.")))
                                        .limit(18)
                                        .toList());
    }

    @SubscribeEvent(priority = EventPriority.LOWEST, receiveCanceled = true)
    public static void hurt(LivingHurtEvent e) {
        event(e.getEntity(), e.getSource(), e.getAmount(), "hurt:" + e.isCanceled());
    }

    @SubscribeEvent(priority = EventPriority.LOWEST, receiveCanceled = true)
    public static void damage(LivingDamageEvent e) {
        event(e.getEntity(), e.getSource(), e.getAmount(), "damage:" + e.isCanceled());
    }

    @SubscribeEvent(priority = EventPriority.LOWEST, receiveCanceled = true)
    public static void death(LivingDeathEvent e) {
        event(e.getEntity(), e.getSource(), 0, "death:" + e.isCanceled());
    }

    @SubscribeEvent
    public static void tick(TickEvent.PlayerTickEvent e) {
        if (e.phase != TickEvent.Phase.END || !(e.player instanceof ServerPlayer p) || !crew(p))
            return;
        float h = p.getHealth();
        Float old = previous.put(p.getUUID(), h);
        if (old == null || h != old || p.tickCount % 40 == 0) write(sample(p, "health"));
    }

    private static void observeOriginal(ClassLoader realm, ServerPlayer equipped)
            throws ReflectiveOperationException {
        Class<?> busClass = realm.loadClass("cpw.mods.fml.common.eventhandler.EventBus"),
                list = realm.loadClass("cpw.mods.fml.common.eventhandler.ListenerList"),
                iface = realm.loadClass("cpw.mods.fml.common.eventhandler.IEventListener"),
                priority = realm.loadClass("cpw.mods.fml.common.eventhandler.EventPriority"),
                player = realm.loadClass("net.minecraft.entity.player.EntityPlayerMP");
        Object bus =
                realm.loadClass("net.minecraftforge.common.MinecraftForge")
                        .getField("EVENT_BUS")
                        .get(null);
        Field fid = busClass.getDeclaredField("busID");
        fid.setAccessible(true);
        int idx = fid.getInt(bus);
        var register = list.getMethod("register", int.class, priority, iface);
        var id = player.getMethod("func_110124_au");
        for (String name : List.of("LivingAttackEvent", "LivingHurtEvent", "LivingDeathEvent")) {
            Class<?> cls = realm.loadClass("net.minecraftforge.event.entity.living." + name);
            Object listeners =
                    cls.getMethod("getListenerList").invoke(cls.getConstructor().newInstance());
            var ent = cls.getField("entityLiving");
            var source = cls.getField("source");
            Object hook =
                    Proxy.newProxyInstance(
                            realm,
                            new Class<?>[] {iface},
                            (proxy, method, args) -> {
                                if (method.getDeclaringClass() == Object.class)
                                    return switch (method.getName()) {
                                        case "hashCode" -> System.identityHashCode(proxy);
                                        case "equals" -> proxy == args[0];
                                        default -> "starbridge-crew-health";
                                    };
                                try {
                                    Object legacy = ent.get(args[0]);
                                    if (player.isInstance(legacy)) {
                                        UUID uuid = (UUID) id.invoke(legacy);
                                        ServerPlayer p =
                                                equipped.serverLevel()
                                                        .getServer()
                                                        .getPlayerList()
                                                        .getPlayer(uuid);
                                        if (p != null && crew(p)) {
                                            mirrors.put(uuid, legacy);
                                            var m = sample(p, "original:" + name);
                                            Object cause = source.get(args[0]);
                                            m.put(
                                                    "source",
                                                    cause.getClass()
                                                            .getMethod("func_76355_l")
                                                            .invoke(cause));
                                            m.put("sourceClass", cause.getClass().getName());
                                            if (!name.equals("LivingDeathEvent"))
                                                m.put(
                                                        "amount",
                                                        cls.getField("ammount").get(args[0]));
                                            m.put("caller", stack());
                                            write(m);
                                        }
                                    }
                                } catch (ReflectiveOperationException | LinkageError ex) {
                                    write(
                                            Map.of(
                                                    "phase",
                                                    "original-observe-error",
                                                    "error",
                                                    ex.toString()));
                                }
                                return null;
                            });
            register.invoke(listeners, idx, priority.getField("LOWEST").get(null), hook);
        }
    }
}
