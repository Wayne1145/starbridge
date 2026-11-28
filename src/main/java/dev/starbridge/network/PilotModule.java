package dev.starbridge.network;

import dev.starbridge.Starbridge;
import dev.starbridge.block.ShipCoreBlockEntity;
import dev.starbridge.flight.Vec3d;
import dev.starbridge.server.CoreRegistry;
import dev.starbridge.text.Lang;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.network.simple.SimpleChannel;
import net.minecraftforge.server.ServerLifecycleHooks;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Server-owned manual piloting sessions. The client never supplies a position or a target entity.
 * Text fields carry translation keys ({@code starbridge.*}) that each client renders in its language.
 */
public final class PilotModule {
    private static final String VERSION = "2";
    private static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(
            new ResourceLocation(Starbridge.ID, "pilot"), () -> VERSION, VERSION::equals, VERSION::equals);
    private static final Map<UUID, Session> SESSIONS = new HashMap<>();
    private static final Map<UUID, UUID> PILOTS = new HashMap<>();
    private static boolean registered;

    public enum Action { DRIVE, HOLD, BRAKE, HEARTBEAT, CLOSE, JUMP, LAUNCH }
    public enum UpdateKind { OPEN, UPDATE, CLOSE }
    /** {@code yaw} is a normalized turn request in [-1, 1]; positive turns left (counter-clockwise from above). */
    public record PilotInput(UUID coreId, Action action, double x, double y, double z, double yaw) {}
    public record PilotState(UUID coreId, UpdateKind kind, long serverTick, int energy, int capacity,
                             String mode, String dimension, boolean hasPosition,
                             double x, double y, double z, double vx, double vy, double vz,
                             double heading, double tilt, boolean docked, boolean jumpReady, int jumpCost,
                             String jumpBlocker, String error, String notice) {
        static PilotState closed(UUID coreId, long tick, String reason) {
            return new PilotState(coreId, UpdateKind.CLOSE, tick, 0, 0, "CLOSED", "", false,
                    0, 0, 0, 0, 0, 0, 0, 0, false, false, 0, "", "", reason);
        }
    }

    private PilotModule() {}

    /** Server-thread query for other control adapters that should defer to an active human pilot. */
    public static boolean isBeingPiloted(UUID coreId) { return PILOTS.containsKey(coreId); }

    public static void register(IEventBus modBus) {
        if (registered) return;
        registered = true;
        CHANNEL.messageBuilder(PilotInput.class, 0, NetworkDirection.PLAY_TO_SERVER)
                .encoder((packet, buffer) -> {
                    buffer.writeUUID(packet.coreId()); buffer.writeEnum(packet.action());
                    buffer.writeDouble(packet.x()); buffer.writeDouble(packet.y()); buffer.writeDouble(packet.z());
                    buffer.writeDouble(packet.yaw());
                })
                .decoder(buffer -> new PilotInput(buffer.readUUID(), buffer.readEnum(Action.class),
                        buffer.readDouble(), buffer.readDouble(), buffer.readDouble(), buffer.readDouble()))
                .consumerMainThread(PilotModule::input).add();
        CHANNEL.messageBuilder(PilotState.class, 1, NetworkDirection.PLAY_TO_CLIENT)
                .encoder(PilotModule::encodeState).decoder(PilotModule::decodeState)
                .consumerMainThread((packet, context) -> {
                    DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> dev.starbridge.client.PilotClient.receive(packet));
                    context.get().setPacketHandled(true);
                }).add();
        MinecraftForge.EVENT_BUS.register(new PilotModule());
    }

    /** Intended for a server command or a trusted server-side block interaction. */
    public static void open(ServerPlayer player, ShipCoreBlockEntity core) {
        String denial = validate(player, core);
        if (denial != null) { player.sendSystemMessage(Lang.keyOrText(denial)); return; }
        UUID occupant = PILOTS.get(core.id());
        if (occupant != null && !occupant.equals(player.getUUID())) {
            player.sendSystemMessage(Lang.msg("pilot_occupied"));
            return;
        }
        Session previous = SESSIONS.get(player.getUUID());
        if (previous != null) close(player.getUUID(), "starbridge.message.pilot_changed", false);
        Session session = new Session(core.id(), now(player.getServer()));
        SESSIONS.put(player.getUUID(), session);
        PILOTS.put(core.id(), player.getUUID());
        try { core.hold(); }
        catch (RuntimeException exception) { session.notice = Lang.keyOf(exception); }
        send(player, state(player.getServer(), core, UpdateKind.OPEN, session.notice));
    }

    /** Called only by the client UI; direction is enforced by the registered channel message. */
    public static void sendInput(UUID coreId, Action action, double x, double y, double z, double yaw) {
        CHANNEL.sendToServer(new PilotInput(coreId, action, x, y, z, yaw));
    }

    private static void input(PilotInput packet, Supplier<NetworkEvent.Context> contextSupplier) {
        NetworkEvent.Context context = contextSupplier.get();
        ServerPlayer player = context.getSender();
        context.setPacketHandled(true);
        if (player == null) return;
        Session session = SESSIONS.get(player.getUUID());
        if (session == null || !session.coreId.equals(packet.coreId())) return;
        if (packet.action() == Action.CLOSE) {
            // Closing is always permitted; rate limiting must never prevent release of a console.
            close(player.getUUID(), "starbridge.message.pilot_closed", false);
            return;
        }
        long tick = now(player.getServer());
        if (!PilotInputPolicy.acceptsTick(tick, session.lastInput)) return;
        session.lastInput = tick;
        ShipCoreBlockEntity core = CoreRegistry.find(session.coreId).orElse(null);
        String denial = validate(player, core);
        if (denial != null) { close(player.getUUID(), denial, true); return; }
        final Vec3d acceleration;
        final double yawRate;
        try {
            acceleration = PilotInputPolicy.acceleration(packet.x(), packet.y(), packet.z())
                    .multiply(core.maxManualAcceleration() / PilotInputPolicy.MAX_ACCELERATION);
            yawRate = PilotInputPolicy.yawRate(packet.yaw());
        } catch (IllegalArgumentException invalid) {
            close(player.getUUID(), "starbridge.error.bad_input", true);
            return;
        }
        session.lastHeartbeat = tick;
        try {
            switch (packet.action()) {
                case DRIVE -> core.jog(acceleration, yawRate, 20);
                case HOLD -> core.hold();
                case BRAKE -> core.stop();
                case LAUNCH -> core.launch();
                case JUMP -> {
                    core.jump(player);
                    close(player.getUUID(), "starbridge.message.jump_relaunch", true);
                    return;
                }
                case HEARTBEAT, CLOSE -> { }
            }
            if (packet.action() != Action.HEARTBEAT) session.notice = "";
        } catch (RuntimeException failure) {
            session.notice = Lang.keyOf(failure);
            // Never let held movement keys cancel a launch that is still waiting for physics.
            if (packet.action() == Action.DRIVE && !"starbridge.error.launch_pending".equals(session.notice)) {
                try { core.stop(); } catch (RuntimeException ignored) { /* Already reported. */ }
            }
        }
    }

    private static String validate(ServerPlayer player, ShipCoreBlockEntity core) {
        if (core == null || core.isRemoved() || core.getLevel() == null) return "starbridge.message.pilot_unloaded";
        if (!player.isAlive() || player.isSpectator()) return "starbridge.message.pilot_inactive";
        if (!core.authorized(player)) return "message.starbridge.not_owner";
        if (core.getLevel() != player.serverLevel()) return "starbridge.message.pilot_dimension";
        Map<String, Object> status = core.status();
        Double x = finite(status.get("x")), y = finite(status.get("y")), z = finite(status.get("z"));
        if (x == null || y == null || z == null) return "starbridge.message.pilot_no_telemetry";
        if (finite(status.get("vx")) == null || finite(status.get("vy")) == null || finite(status.get("vz")) == null)
            return "starbridge.message.pilot_no_telemetry";
        if (!PilotInputPolicy.withinRange(player.getX(), player.getY(), player.getZ(), x, y, z))
            return "starbridge.message.pilot_range";
        return null;
    }

    @SubscribeEvent
    public void tick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || SESSIONS.isEmpty()) return;
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server == null) return;
        long tick = now(server);
        for (Map.Entry<UUID, Session> entry : new ArrayList<>(SESSIONS.entrySet())) {
            Session session = entry.getValue();
            ServerPlayer player = server.getPlayerList().getPlayer(entry.getKey());
            if (player == null) { close(entry.getKey(), "starbridge.message.pilot_disconnected", false); continue; }
            if (tick - session.lastHeartbeat > 40) { close(entry.getKey(), "starbridge.message.pilot_heartbeat", true); continue; }
            ShipCoreBlockEntity core = CoreRegistry.find(session.coreId).orElse(null);
            String denial = validate(player, core);
            if (denial != null) { close(entry.getKey(), denial, true); continue; }
            if (tick % 5 == 0) send(player, state(server, core, UpdateKind.UPDATE, session.notice));
        }
    }

    @SubscribeEvent
    public void logout(PlayerEvent.PlayerLoggedOutEvent event) {
        close(event.getEntity().getUUID(), "starbridge.message.pilot_disconnected", false);
    }

    @SubscribeEvent
    public void stopping(ServerStoppingEvent event) {
        for (Session session : SESSIONS.values()) CoreRegistry.find(session.coreId).ifPresent(ShipCoreBlockEntity::release);
        SESSIONS.clear();
        PILOTS.clear();
    }

    private static void close(UUID pilot, String reason, boolean notifyClient) {
        Session session = SESSIONS.remove(pilot);
        if (session == null) return;
        PILOTS.remove(session.coreId, pilot);
        CoreRegistry.find(session.coreId).ifPresent(core -> {
            try { core.hold(); }
            catch (RuntimeException failure) {
                try { core.stop(); } catch (RuntimeException ignored) { /* Nothing more to release. */ }
            }
        });
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        ServerPlayer player = server == null ? null : server.getPlayerList().getPlayer(pilot);
        if (notifyClient && player != null) send(player, PilotState.closed(session.coreId, now(server), reason));
    }

    private static PilotState state(MinecraftServer server, ShipCoreBlockEntity core, UpdateKind kind, String notice) {
        Map<String, Object> status = core.status();
        Double x = finite(status.get("x")), y = finite(status.get("y")), z = finite(status.get("z"));
        boolean position = x != null && y != null && z != null;
        return new PilotState(core.id(), kind, now(server), integer(status.get("energy")), integer(status.get("capacity")),
                text(status.get("mode"), 64), text(status.get("dimension"), 256), position,
                position ? x : 0, position ? y : 0, position ? z : 0,
                number(status.get("vx")), number(status.get("vy")), number(status.get("vz")),
                number(status.get("heading")), number(status.get("tilt")),
                Boolean.TRUE.equals(status.get("static")), Boolean.TRUE.equals(status.get("jumpReady")),
                integer(status.get("jumpCost")), text(status.get("jumpBlocker"), 128),
                text(status.get("lastErrorKey"), 512), text(notice, 512));
    }

    private static void send(ServerPlayer player, PilotState state) {
        CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), state);
    }

    private static long now(MinecraftServer server) { return server == null ? 0 : server.overworld().getGameTime(); }
    private static Double finite(Object value) { return value instanceof Number n && Double.isFinite(n.doubleValue()) ? n.doubleValue() : null; }
    private static double number(Object value) { Double n = finite(value); return n == null ? 0 : n; }
    private static int integer(Object value) { return value instanceof Number n ? Math.max(0, n.intValue()) : 0; }
    private static String text(Object value, int max) { String text = value == null ? "" : value.toString(); return text.length() <= max ? text : text.substring(0, max); }

    private static void encodeState(PilotState packet, FriendlyByteBuf buffer) {
        buffer.writeUUID(packet.coreId()); buffer.writeEnum(packet.kind()); buffer.writeLong(packet.serverTick());
        buffer.writeVarInt(packet.energy()); buffer.writeVarInt(packet.capacity());
        buffer.writeUtf(packet.mode(), 64); buffer.writeUtf(packet.dimension(), 256); buffer.writeBoolean(packet.hasPosition());
        buffer.writeDouble(packet.x()); buffer.writeDouble(packet.y()); buffer.writeDouble(packet.z());
        buffer.writeDouble(packet.vx()); buffer.writeDouble(packet.vy()); buffer.writeDouble(packet.vz());
        buffer.writeDouble(packet.heading()); buffer.writeDouble(packet.tilt());
        buffer.writeBoolean(packet.docked()); buffer.writeBoolean(packet.jumpReady()); buffer.writeVarInt(packet.jumpCost());
        buffer.writeUtf(packet.jumpBlocker(), 128);
        buffer.writeUtf(packet.error(), 512); buffer.writeUtf(packet.notice(), 512);
    }

    private static PilotState decodeState(FriendlyByteBuf buffer) {
        return new PilotState(buffer.readUUID(), buffer.readEnum(UpdateKind.class), buffer.readLong(), buffer.readVarInt(), buffer.readVarInt(),
                buffer.readUtf(64), buffer.readUtf(256), buffer.readBoolean(), buffer.readDouble(), buffer.readDouble(), buffer.readDouble(),
                buffer.readDouble(), buffer.readDouble(), buffer.readDouble(), buffer.readDouble(), buffer.readDouble(),
                buffer.readBoolean(), buffer.readBoolean(), buffer.readVarInt(), buffer.readUtf(128),
                buffer.readUtf(512), buffer.readUtf(512));
    }

    private static final class Session {
        final UUID coreId;
        long lastInput;
        long lastHeartbeat;
        String notice = "";
        Session(UUID coreId, long tick) { this.coreId = coreId; lastHeartbeat = tick; lastInput = tick - 2; }
    }
}
