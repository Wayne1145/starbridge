package dev.starbridge.orbit;

import dev.starbridge.block.ShipCoreBlockEntity;
import dev.starbridge.server.CoreRegistry;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.server.ServerStoppedEvent;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** The block opens a read-only view for crew in this ship's cabin. Only the server creates a watch. */
public final class CrewWatch {
    public enum Motion { MANUAL, CRUISE, BRAKE, COAST, PAUSED, ENDED }
    public record Reading(UUID ship, UUID session, boolean open, String from, String to,
            String captain, Motion motion, HelmIssuer issuer, double remaining, double span,
            double speed, int energy, int reserve) {}
    private record Watch(UUID ship, UUID session) {}
    private static final Map<UUID, Watch> viewers = new HashMap<>();

    private CrewWatch() {}

    static void register() {
        MinecraftForge.EVENT_BUS.addListener(CrewWatch::tick);
        MinecraftForge.EVENT_BUS.addListener((PlayerEvent.PlayerLoggedOutEvent e) -> viewers.remove(e.getEntity().getUUID()));
        MinecraftForge.EVENT_BUS.addListener((ServerStoppedEvent e) -> viewers.clear());
    }

    public static boolean open(ServerPlayer player, ShipCoreBlockEntity core) {
        var watch = new Watch(core.id(), UUID.randomUUID());
        var reading = reading(player, core, watch, true);
        if (reading == null) return false;
        viewers.put(player.getUUID(), watch);
        OrbitWire.send(player, reading);
        return true;
    }

    static void close(ServerPlayer player, UUID session) {
        var watch = viewers.get(player.getUUID());
        if (watch != null && watch.session().equals(session)) viewers.remove(player.getUUID());
    }

    private static Reading reading(ServerPlayer player, ShipCoreBlockEntity core, Watch watch, boolean open) {
        if (core == null || !OrbitModule.aboard(player, core)) return null;
        MinecraftServer server = player.serverLevel().getServer();
        var flight = OrbitLedger.get(server).legs.get(watch.ship());
        if (flight == null) return null;
        var captain = server.getPlayerList().getPlayer(flight.captain);
        String name = captain == null ? "" : captain.getGameProfile().getName();
        var target = OrbitalFlight.body(flight.to);
        Motion motion;
        if (flight.suspended) motion = Motion.PAUSED;
        else if (flight.braking) motion = Motion.BRAKE;
        else if (flight.cruise) motion = Motion.CRUISE;
        else if (OrbitModule.manualClaimed(core)) motion = Motion.MANUAL;
        else motion = Motion.COAST;
        return new Reading(watch.ship(), watch.session(), open, flight.from, flight.to, name, motion,
                flight.issuer, target.subtract(flight.pose.position()).length(), target.subtract(OrbitalFlight.body(flight.from)).length(),
                flight.suspended ? 0 : 20 * flight.pose.velocity().length(), core.energy(), Math.max(0, flight.reserve));
    }

    private static void tick(TickEvent.ServerTickEvent e) {
        if (e.phase != TickEvent.Phase.END || viewers.isEmpty() || e.getServer().getTickCount() % 10 != 0) return;
        var it = viewers.entrySet().iterator();
        while (it.hasNext()) {
            var entry = it.next();
            var player = e.getServer().getPlayerList().getPlayer(entry.getKey());
            if (player == null) { it.remove(); continue; }
            var watch = entry.getValue();
            var state = reading(player, CoreRegistry.find(watch.ship()).orElse(null), watch, false);
            if (state == null) {
                it.remove();
                state = new Reading(watch.ship(), watch.session(), false, "", "", "", Motion.ENDED,
                        HelmIssuer.HELM, 0, 0, 0, 0, 0);
            }
            OrbitWire.send(player, state);
        }
    }
}
