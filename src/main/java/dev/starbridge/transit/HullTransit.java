package dev.starbridge.transit;

import dev.starbridge.Starbridge;
import dev.starbridge.block.ShipCoreBlockEntity;
import dev.starbridge.compat.vs.VsShipBackend;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.server.ServerStoppedEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import java.util.*;

/** Sends the same VS ship through its native dimension portal and reconciles the crew against that ship. */
public final class HullTransit {
    private static final VsShipBackend VS = new VsShipBackend();
    private static final TicketType<UUID> TICKET = TicketType.create("starbridge_voyage", UUID::compareTo, 100);
    private static final Set<UUID> sealedThisRun = new HashSet<>();
    private static final Map<UUID, PassageStorage> checkpoints = new HashMap<>();
    private static ArrivalListener arrivals;
    private static TransitJournal journal;
    private static MinecraftServer serverInstance;

    private HullTransit() {}
    public static void register(ArrivalListener listener) {
        if (arrivals != null) throw new IllegalStateException("Hull transit registered twice");
        arrivals = Objects.requireNonNull(listener);
        MinecraftForge.EVENT_BUS.register(new HullTransit());
        MinecraftForge.EVENT_BUS.addListener(PassageStorage::loaded);
        MinecraftForge.EVENT_BUS.addListener(PassageStorage::saving);
    }

    public static Optional<Vec3> toShipLocal(ServerLevel level, long ship, Vec3 world) {
        return VS.transformPoint(level, ship, vs(world), false).map(HullTransit::mc);
    }
    public static Optional<Vec3> toWorld(ServerLevel level, long ship, Vec3 local) {
        return VS.transformPoint(level, ship, vs(local), true).map(HullTransit::mc);
    }
    private static Vec3 mc(VsShipBackend.Vector point) { return new Vec3(point.x(), point.y(), point.z()); }
    private static VsShipBackend.Vector vs(Vec3 point) { return new VsShipBackend.Vector(point.x, point.y, point.z); }

    public static UUID begin(ShipCoreBlockEntity core, ServerPlayer owner, ServerLevel destination, Vec3 target, String route, int fare) {
        ServerLevel source = owner.serverLevel();
        MinecraftServer server = source.getServer();
        if (!server.isSameThread() || source == destination || destination.getServer() != server || core.getLevel() != source
                || core.isRemoved() || !core.authorized(owner) || !owner.isAlive() || owner.isSpectator())
            throw new IllegalStateException("An authorized pilot aboard the source ship is required");
        if (fare <= 0 || core.energy() < fare || route == null || route.isBlank()) throw new IllegalStateException("Invalid route or insufficient drive energy");
        var record = VS.inspect(source, core.getBlockPos()).orElseThrow(() -> new IllegalStateException("Assemble and load the ship before departure"));
        long ship = record.shipId();
        var bounds = VS.storageBoundsById(source, ship).orElseThrow(() -> new IllegalStateException("Ship has no physical hull"));
        var ledger = ledger(server);
        if (ledger.locked(ship)) throw new IllegalStateException("A previous passage is still being reconciled");
        if (bounds.minY() < destination.getMinBuildHeight() || bounds.maxY() >= destination.getMaxBuildHeight())
            throw new IllegalStateException("The destination cannot store all the ship's deck heights");
        var crew = passengers(source, ship, bounds, owner);
        Vec3 berth = BerthPlanner.find(source, destination, ship, bounds, mc(record.position()), target, route.equals("home"), VS);
        checkExtent(bounds);
        if (HullManifest.inspect(destination, bounds).blocks() != 0) throw new IllegalStateException("Destination ship storage is already occupied");
        core.release();
        var machines = HullManifest.machines(source,bounds);
        var seal = HullManifest.inspect(source, bounds);
        if (seal.blocks() == 0) throw new IllegalStateException("There are no physical ship blocks");
        var leg = TransitJournal.Leg.create(core, ship, source, destination, berth, route, fare, crew, bounds, seal.digest());
        ledger.prepare(leg);
        holdTickets(source, destination, leg, true);
        try {
            if (!VS.setStatic(source, ship, true)) throw new IllegalStateException("Departure ship could not be parked");
            VS.transfer(source, destination, ship, vs(berth));
            HullManifest.restoreMovedMachines(source,destination,machines);
            // Seal in the same server turn, before a running furnace or visiting player can change legitimate cargo.
            if (matchingCargo(source,destination,leg)) {
                sealedThisRun.add(leg.id());
                // Persist the pre-debit state while inventories cannot yet tick. The native ship record may update later.
                var proof=TransitJournal.FareProof.capture(matchesCore(destination,leg).saveWithoutMetadata(),leg.digest());
                ledger.preparePayment(leg,proof);
            }
            shelterCrew(source,destination,leg);
        } catch (RuntimeException failed) {
            // VS can move terrain before reporting a failure. A verified source can cancel later;
            // never run a second native movement as an exception handler or retry.
            Starbridge.LOGGER.error("Native passage {} did not return normally; reconciling", leg.id(), failed);
            throw new IllegalStateException("Ship passage needs reconciliation; the crew manifest was retained", failed);
        }
        return leg.id();
    }

    public static boolean isAboard(ServerPlayer player, ShipCoreBlockEntity core) {
        ServerLevel level = player.serverLevel();
        if (core.getLevel() != level || core.isRemoved()) return false;
        return VS.inspect(level, core.getBlockPos()).map(ship -> VS.storageBoundsById(level, ship.shipId())
                .flatMap(bounds -> toShipLocal(level, ship.shipId(), player.position()).map(local -> onDeck(level, bounds, local)))
                .orElse(false)).orElse(false);
    }

    private static List<TransitJournal.Rider> passengers(ServerLevel level, long ship, VsShipBackend.StorageBounds bounds, ServerPlayer pilot) {
        List<TransitJournal.Rider> result = new ArrayList<>();
        for (ServerPlayer player : level.players()) {
            if (player.isSpectator() || !player.isAlive()) continue;
            Vec3 local = toShipLocal(level, ship, player.position()).orElseThrow();
            if (!onDeck(level, bounds, local)) continue;
            if (player.isPassenger() || player.isVehicle()) throw new IllegalStateException("Dismount before transferring the ship");
            result.add(new TransitJournal.Rider(player.getUUID(), local, player.getYRot(), player.getXRot()));
        }
        if (result.stream().noneMatch(r -> r.player().equals(pilot.getUUID()))) throw new IllegalStateException("Stand on a deck of this ship before departure");
        return result;
    }
    static boolean onDeck(ServerLevel level, VsShipBackend.StorageBounds b, Vec3 p) {
        // The strict lateral bounds and a solid ship block below the feet distinguish crew from bystanders.
        if (p.x < b.minX() || p.x >= b.maxX()+1.0 || p.z < b.minZ() || p.z >= b.maxZ()+1.0
                || p.y < b.minY() || p.y > b.maxY()+3) return false;
        BlockPos cell = BlockPos.containing(p.x, p.y - 0.02, p.z);
        for (int i=0; i<3; i++) {
            BlockPos below=cell.below(i);
            if (below.getY()<b.minY()) break;
            if (!level.getBlockState(below).getCollisionShape(level,below).isEmpty()) return true;
        }
        return false;
    }
    static void checkExtent(VsShipBackend.StorageBounds b) {
        long x=(long)b.maxX()-b.minX()+1, y=(long)b.maxY()-b.minY()+1, z=(long)b.maxZ()-b.minZ()+1;
        if (x<1 || y<1 || z<1 || x>302 || z>302 || y>302 || x*y*z>2_025_000)
            throw new IllegalStateException("Hull volume exceeds the supported passage survey limits");
    }
    private static boolean intactTransfer(ServerLevel source, ServerLevel dest, TransitJournal.Leg leg) {
        return VS.belongsTo(dest,leg.ship()) && matchingCargo(source,dest,leg);
    }
    private static boolean matchingCargo(ServerLevel source,ServerLevel dest,TransitJournal.Leg leg) {
        var targetCore=matchesCore(dest,leg);
        var oldCore=matchesCore(source,leg);
        var destSeal=HullManifest.inspect(dest,leg.bounds(),leg);
        var sourceSeal=HullManifest.inspect(source,leg.bounds());
        boolean matches=targetCore!=null && oldCore==null && destSeal.digest().equals(leg.expectedArrivalDigest()) && sourceSeal.blocks()==0;
        if (!matches && (leg.fareProof()==null && source.getGameTime()%20==0 || !sealedThisRun.contains(leg.id()) && source.getGameTime()%100==0)) {
            Starbridge.LOGGER.warn("Ship {} awaiting cargo verification: destination core={}, source core={}, destination blocks={}, source blocks={}, expected={}, actual={}",leg.ship(),targetCore!=null,oldCore!=null,destSeal.blocks(),sourceSeal.blocks(),leg.expectedArrivalDigest(),destSeal.digest());
        }
        return matches;
    }
    /** Once the unique physical console has moved, keep its crew on their decks even during billing or recovery. */
    private static void shelterCrew(ServerLevel source,ServerLevel dest,TransitJournal.Leg leg) {
        if(matchesCore(dest,leg)==null || matchesCore(source,leg)!=null) return;
        for(var rider:leg.crew()) {
            var player=source.getServer().getPlayerList().getPlayer(rider.player());
            if(player==null || !player.isAlive() || player.level()!=source || leg.done().contains(rider.player())) continue;
            var spot=toWorld(dest,leg.ship(),rider.local()).orElse(null);
            if(spot==null) {
                // The destination has the core but VS has not installed a transform yet. Hold the rider for this short gap.
                player.setAirSupply(player.getMaxAirSupply());player.fallDistance=0;player.setDeltaMovement(Vec3.ZERO);continue;
            }
            player.stopRiding();player.teleportTo(dest,spot.x,spot.y,spot.z,rider.yaw(),rider.pitch());
            player.setDeltaMovement(Vec3.ZERO);player.setAirSupply(player.getMaxAirSupply());player.fallDistance=0;
        }
    }
    private static void holdTickets(ServerLevel source, ServerLevel dest, TransitJournal.Leg leg, boolean add) {
        var b=leg.bounds(); checkExtent(b);
        // Data chunks are loaded by VS's own native portal code. Brief FULL tickets retain both sides until the next save.
        for(int x=b.minX()>>4;x<=b.maxX()>>4;x++) for(int z=b.minZ()>>4;z<=b.maxZ()>>4;z++) {
            ChunkPos pos=new ChunkPos(x,z); ticket(source,pos,leg.id(),add); ticket(dest,pos,leg.id(),add);
        }
        ticket(dest,new ChunkPos(BlockPos.containing(leg.berth())),leg.id(),add);
    }
    private static void ticket(ServerLevel level,ChunkPos chunk,UUID id,boolean add) {
        if(add) level.getChunkSource().addRegionTicket(TICKET,chunk,0,id);
        else level.getChunkSource().removeRegionTicket(TICKET,chunk,0,id);
    }
    public static boolean inProgress(MinecraftServer server, UUID core) {
        return ledger(server).legs().stream().anyMatch(leg->leg.core().equals(core));
    }
    private static TransitJournal ledger(MinecraftServer server) {
        if (server != serverInstance) { journal = TransitJournal.open(server); serverInstance=server; }
        return journal;
    }
    @SubscribeEvent public void stop(ServerStoppedEvent event) { journal=null; serverInstance=null; sealedThisRun.clear(); checkpoints.clear(); ArrivalDeck.clear(); }
    @SubscribeEvent public void tick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || event.getServer().getTickCount()%10!=0) return;
        MinecraftServer server=event.getServer();
        var ledger=ledger(server);
        for (var leg : List.copyOf(ledger.legs())) {
            try { reconcile(server, ledger, leg); }
            catch (RuntimeException e) { if (server.getTickCount()%200==0) Starbridge.LOGGER.error("Cannot reconcile voyage {}",leg.id(),e); }
        }
    }
    private static ServerLevel level(MinecraftServer server, String id) {
        return server.getLevel(ResourceKey.create(Registries.DIMENSION, new ResourceLocation(id)));
    }
    private static void reconcile(MinecraftServer server, TransitJournal ledger, TransitJournal.Leg leg) {
        ServerLevel from=level(server,leg.source()), dest=level(server,leg.destination());
        if (from==null || dest==null) return;
        boolean online=leg.crew().stream().anyMatch(r -> server.getPlayerList().getPlayer(r.player())!=null);
        if (!online) return; // Tickets expire if the entire crew is offline; reconcile at login.
        holdTickets(from,dest,leg,true);
        shelterCrew(from,dest,leg);
        if (!VS.belongsTo(dest,leg.ship())) {
            if (VS.belongsTo(from,leg.ship()) && matchesCore(from,leg)!=null && matchesCore(dest,leg)==null) {
                // Native teleport is synchronous. On restart a fully intact source means the uncommitted leg rolled back.
                if (leg.done().isEmpty() && !leg.committed() && HullManifest.inspect(from,leg.bounds()).digest().equals(leg.digest())
                        && HullManifest.inspect(dest,leg.bounds()).blocks()==0) {
                    holdTickets(from,dest,leg,false); ArrivalDeck.forget(leg.id()); ledger.remove(leg);
                }
            }
            return;
        }
        ShipCoreBlockEntity core=matchesCore(dest,leg);
        if (core==null || matchesCore(from,leg)!=null) return;
        if (!leg.committed()) {
            boolean verifiedThisRun = sealedThisRun.contains(leg.id());
            if (!verifiedThisRun && !paidCheckpoint(from,dest,core,leg) && !intactTransfer(from,dest,leg)) return;
            if (leg.fareProof() == null) {
                if (core.energy() < leg.fare()) return;
                // A furnace can tick between native arrival and billing. Journal this exact state before touching FE.
                var seal = HullManifest.inspect(dest, leg.bounds());
                leg = ledger.preparePayment(leg, TransitJournal.FareProof.capture(core.saveWithoutMetadata(), seal.digest()));
                sealedThisRun.remove(leg.id());
            }
            if (!core.payForPassage(leg.id(),leg.fare())) return;
            // The receipt makes re-entry at the next tick idempotent. Recheck the full seal on a new process.
            sealedThisRun.add(leg.id());
            var id = leg.id();
            var bounds = leg.bounds();
            var checkpoint = checkpoints.computeIfAbsent(id, ignored -> new PassageStorage(from, dest, bounds, id));
            try {
                if (!checkpoint.advance()) return;
            } catch (RuntimeException diskFailure) {
                checkpoints.remove(id);
                throw diskFailure;
            }
            server.overworld().getDataStorage().save();
            leg = ledger.commit(leg);
            checkpoints.remove(id);
            sealedThisRun.remove(leg.id());
        }
        List<UUID> delivered = new ArrayList<>();
        for (var rider:leg.crew()) {
            if (leg.done().contains(rider.player())) continue;
            ServerPlayer p=server.getPlayerList().getPlayer(rider.player());
            if (p==null || !p.isAlive()) continue;
            if (!ArrivalDeck.settled(p,dest,leg,rider)) continue;
            arrivals.arrived(p,leg.route(),leg.id());
            delivered.add(p.getUUID());
        }
        if (!delivered.isEmpty()) {
            server.getPlayerList().saveAll();
            server.overworld().getDataStorage().save();
            for (UUID player: delivered) leg=ledger.deliver(leg,player);
        }
        if (leg.done().size()==leg.crew().size()) { holdTickets(from,dest,leg,false); ArrivalDeck.forget(leg.id()); ledger.remove(leg); }
    }
    private static boolean paidCheckpoint(ServerLevel from,ServerLevel dest,ShipCoreBlockEntity core,TransitJournal.Leg leg) {
        var proof=leg.fareProof();
        if (proof==null || !proof.paidOn(core.saveWithoutMetadata(),leg.core(),leg.id(),leg.fare())) return false;
        if (HullManifest.inspect(from,leg.bounds()).blocks()!=0) return false;
        if (proof.chunkWitnesses()) return PassageStorage.witnessed(from,dest,leg.bounds(),leg.id());
        return LegacyPaidHull.matches(dest,core,leg.bounds());
    }

    private static ShipCoreBlockEntity matchesCore(ServerLevel level, TransitJournal.Leg leg) {
        BlockEntity entity=level.getBlockEntity(leg.corePos());
        return entity instanceof ShipCoreBlockEntity core && core.id().equals(leg.core()) ? core : null;
    }
}
