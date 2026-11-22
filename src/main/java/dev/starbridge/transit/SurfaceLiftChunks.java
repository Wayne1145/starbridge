package dev.starbridge.transit;

import dev.starbridge.orbit.HelmIssuer;

import dev.starbridge.Starbridge;
import dev.starbridge.block.ShipCoreBlockEntity;
import dev.starbridge.compat.vs.VsShipBackend;
import dev.starbridge.server.CoreRegistry;
import dev.starbridge.voyage.VoyageNetwork;
import dev.starbridge.voyage.Voyages;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.level.ChunkPos;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.server.ServerStoppedEvent;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Hold the source blocks of a docked ship until the native assembler has lifted its whole cabin. */
public final class SurfaceLiftChunks {
    private static final VsShipBackend SHIPS = new VsShipBackend();
    private static final TicketType<UUID> LIFT = TicketType.create("starbridge_surface_lift", UUID::compareTo);
    private static final Map<UUID, Request> pending = new LinkedHashMap<>();
    private static final int TICK_BUDGET = 4;
    private static int cursor;

    private SurfaceLiftChunks() {}

    private static final class Request {
        final ServerLevel level;
        final UUID core, pilot;
        final long ship;
        final int deadline;
        final List<ChunkPos> chunks;
        String target;
        boolean automatic;
        HelmIssuer issuer;
        int claimed;

        Request(ServerPlayer captain, UUID core, long ship, List<ChunkPos> chunks, String target, boolean automatic, HelmIssuer issuer) {
            this.level = captain.serverLevel();
            this.pilot = captain.getUUID();
            this.core = core;
            this.ship = ship;
            this.chunks = chunks;
            this.target = target;
            this.automatic = automatic;
            this.issuer = issuer;
            this.deadline = level.getServer().getTickCount() + 1200;
        }

        boolean loaded() {
            var cache = level.getChunkSource();
            return chunks.stream().allMatch(c -> cache.getChunkNow(c.x, c.z) != null);
        }

        void release() {
            for (int i = 0; i < claimed; i++) level.getChunkSource().removeRegionTicket(LIFT, chunks.get(i), 1, core);
        }
    }

    static void register() {
        MinecraftForge.EVENT_BUS.addListener(SurfaceLiftChunks::tick);
        MinecraftForge.EVENT_BUS.addListener((ServerStoppedEvent event) -> { pending.clear(); cursor = 0; });
    }

    /** A repeated request from the same pilot updates the chosen course without scheduling another takeoff. */
    public static boolean ready(ServerPlayer pilot, ShipCoreBlockEntity core, long ship, String target, boolean automatic, HelmIssuer issuer) {
        var request = pending.get(core.id());
        if (request == null) {
            var box = SHIPS.storageBoundsById(pilot.serverLevel(), ship).orElseThrow();
            HullTransit.checkExtent(box);
            var chunks = new ArrayList<ChunkPos>();
            for (int x = box.minX() >> 4; x <= box.maxX() >> 4; x++)
                for (int z = box.minZ() >> 4; z <= box.maxZ() >> 4; z++) chunks.add(new ChunkPos(x, z));
            request = new Request(pilot, core.id(), ship, List.copyOf(chunks), target, automatic, issuer);
            if (request.loaded()) return true;
            pending.put(core.id(), request);
        } else {
            if (!request.pilot.equals(pilot.getUUID()) || request.ship != ship || request.level != pilot.serverLevel())
                throw new IllegalStateException("nav.starbridge.lift.busy");
            request.target = target;
            request.automatic = automatic;
            request.issuer = issuer;
        }
        VoyageNetwork.open(pilot, core, Component.translatable("nav.starbridge.lift.loading"));
        return false;
    }

    public static void cancel(UUID ship, HelmIssuer issuer) {
        var request = pending.get(ship);
        if (request != null && request.issuer.equals(issuer)) {
            pending.remove(ship);
            request.release();
        }
    }

    private static void tick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || pending.isEmpty()) return;
        var requests = List.copyOf(pending.values());
        int budget = TICK_BUDGET;
        int first = Math.floorMod(cursor++, requests.size());
        for (int i = 0; i < requests.size(); i++) {
            var request = requests.get((first + i) % requests.size());
            var captain = event.getServer().getPlayerList().getPlayer(request.pilot);
            var core = CoreRegistry.find(request.core).orElse(null);
            if (captain == null || captain.level() != request.level || !captain.isAlive() || core == null
                    || core.getLevel() != request.level || !core.authorized(captain)) {
                pending.remove(request.core);
                request.release();
                continue;
            }
            if (event.getServer().getTickCount() >= request.deadline) {
                pending.remove(request.core);
                request.release();
                VoyageNetwork.open(captain, core, Component.translatable("nav.starbridge.lift.timeout"));
                continue;
            }
            while (budget > 0 && request.claimed < request.chunks.size()) {
                request.level.getChunkSource().addRegionTicket(LIFT, request.chunks.get(request.claimed++), 1, request.core);
                budget--;
            }
            if (request.claimed != request.chunks.size() || !request.loaded()) continue;
            pending.remove(request.core);
            try {
                if (!SHIPS.belongsTo(request.level, request.ship)
                        || SHIPS.inspect(request.level, core.getBlockPos()).map(s -> s.shipId() != request.ship).orElse(true))
                    throw new IllegalStateException("nav.starbridge.lift.changed");
                // Revalidate the pilot on deck, current fare, permissions and destination as for the original click.
                Voyages.depart(captain, core, request.target, request.automatic, request.issuer);
            } catch (RuntimeException refused) {
                Starbridge.LOGGER.warn("Prepared takeoff of {} did not start: {}", request.core, refused.toString());
                var notice = Component.translatable(VoyageNetwork.feedbackKey(refused));
                VoyageNetwork.open(captain, core, notice);
            } finally {
                request.release();
            }
        }
    }
}
