package dev.starbridge.transit;

import dev.starbridge.Starbridge;
import dev.starbridge.block.ShipCoreBlockEntity;
import dev.starbridge.compat.vs.VsShipBackend;
import dev.starbridge.compat.vs.VsShipBackend.StorageBounds;
import dev.starbridge.server.CoreRegistry;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.*;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.phys.Vec3;
import net.minecraft.tags.TagKey;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TickEvent;

import java.util.*;

/** Converts the one live cabin into a parked VS ship on approach, then transfers the same hull through HullTransit. */
public final class CabinApproach extends SavedData {
    private static final VsShipBackend VS = new VsShipBackend();
    private static final TagKey<Block> BLACKLIST = TagKey.create(Registries.BLOCK,new ResourceLocation("valkyrienskies","assemble_blacklist"));
    private final Map<UUID,Approach> approaches = new LinkedHashMap<>();
    private static final int ACTIVE_WARMERS = 2;
    private static final int CHUNK_REQUESTS_PER_TICK = 4;
    private final Map<UUID, ApproachChunks> warming = new LinkedHashMap<>();
    private final Map<UUID, String> reportedHandoffs = new HashMap<>();
    private int lastPassageTick = -1;
    private CabinApproach() {}
    public static void register() { MinecraftForge.EVENT_BUS.addListener(CabinApproach::tick); }
    private static CabinApproach get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(CabinApproach::load,CabinApproach::new,"starbridge_physical_approaches");
    }
    private record Rider(UUID id, Vec3 local, float yaw,float pitch) {}
    private record Approach(UUID core,UUID rocket,UUID pilot,String from,String to,BlockPos console,Vec3 berth,String route,int fare,
                            long ship, List<Rider> crew) {
        Approach { crew=List.copyOf(crew); }
    }
    public static void begin(ServerPlayer pilot, ShipCoreBlockEntity core,StorageBounds bounds,ServerLevel destination,Vec3 berth,String route,int fare) {
        ServerLevel source = pilot.serverLevel();
        if (!VS.probe().available()) throw new IllegalStateException("nav.starbridge.flight.physics");
        if (source == destination || core.getLevel()!=source || !core.authorized(pilot) || core.originalRocket()==null || get(source.getServer()).approaches.containsKey(core.id()))
            throw new IllegalStateException("An original ship with a clear approach is required");
        HullTransit.checkExtent(bounds);
        Set<BlockPos> blocks = cabinBlocks(source,bounds);
        if (!blocks.contains(core.getBlockPos())) throw new IllegalStateException("The hull no longer contains this console");
        List<Rider> crew = new ArrayList<>();
        for (ServerPlayer person : source.players()) {
            if (!person.isAlive() || person.isSpectator() || !HullTransit.onDeck(source,bounds,person.position())) continue;
            if (person.isPassenger() || person.isVehicle()) throw new IllegalStateException("nav.starbridge.flight.dismount");
            crew.add(new Rider(person.getUUID(),person.position(),person.getYRot(),person.getXRot()));
        }
        if (crew.stream().noneMatch(r -> r.id().equals(pilot.getUUID()))) throw new IllegalStateException("nav.starbridge.flight.board");
        var sourceKey=source.dimension().location().toString(); var targetKey=destination.dimension().location().toString();
        UUID id=core.id(), original=core.originalRocket(); BlockPos old=core.getBlockPos();
        var entry=new Approach(id,original,pilot.getUUID(),sourceKey,targetKey,old,berth,route,fare,0,crew);
        var data=get(source.getServer()); data.put(entry,source.getServer());
        VsShipBackend.CabinAssembly assembled;
        try { assembled=VS.assembleCabin(source,blocks,old,id); }
        catch (RuntimeException e) { Starbridge.LOGGER.error("Cabin {} assembly interrupted; preserving approach and original hull identity",id,e); throw e; }
        var moved=crew.stream().map(r->new Rider(r.id,r.local.add(assembled.dx(),assembled.dy(),assembled.dz()),r.yaw,r.pitch)).toList();
        entry=new Approach(id,original,pilot.getUUID(),sourceKey,targetKey,old.offset(assembled.dx(),assembled.dy(),assembled.dz()),berth,route,fare,assembled.shipId(),moved);
        data.put(entry,source.getServer());
        for (Rider rider : moved) {
            ServerPlayer person = source.getServer().getPlayerList().getPlayer(rider.id);
            if (person!=null) HullTransit.toWorld(source,assembled.shipId(),rider.local).ifPresent(point -> {
                person.teleportTo(source,point.x,point.y,point.z,rider.yaw,rider.pitch);
                person.setDeltaMovement(Vec3.ZERO); person.fallDistance=0;
            });
        }
    }
    static Set<BlockPos> cabinBlocks(ServerLevel level,StorageBounds bounds) {
        var blocks=new HashSet<BlockPos>();
        for (BlockPos p:BlockPos.betweenClosed(bounds.minX(),bounds.minY(),bounds.minZ(),bounds.maxX(),bounds.maxY(),bounds.maxZ())) {
            if (!level.hasChunkAt(p)) throw new IllegalStateException("nav.starbridge.lift.loading");
            BlockState state=level.getBlockState(p);
            if (state.isAir()) continue;
            if (state.is(BLACKLIST)) throw new IllegalStateException("Remove the unsupported ship block at "+p.toShortString());
            blocks.add(p.immutable());
        }
        return blocks;
    }
    private static void tick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        MinecraftServer server=event.getServer(); CabinApproach data=get(server);
        data.warmStorage(server);
        if (server.getTickCount() % 5 != 0) return;
        for(Approach leg:List.copyOf(data.approaches.values())) {
            try { data.advance(server,leg); }
            catch(RuntimeException | LinkageError e) { if(server.getTickCount()%200==0) Starbridge.LOGGER.warn("Approach {} is waiting: {}",leg.core(),e.toString()); }
        }
    }
    private void warmStorage(MinecraftServer server) {
        warming.values().removeIf(job -> {
            var leg = approaches.get(job.ship);
            var pilot = leg == null ? null : server.getPlayerList().getPlayer(leg.pilot);
            var core = leg == null ? null : CoreRegistry.find(leg.core).orElse(null);
            if (pilot != null && pilot.isAlive() && core != null && core.authorized(pilot)
                    && pilot.serverLevel() == level(server, leg.from)) return false;
            job.release();
            return true;
        });
        if (warming.isEmpty()) return;
        int slice = CHUNK_REQUESTS_PER_TICK / warming.size();
        warming.values().forEach(job -> job.tick(slice));
    }

    private void releaseStorage(UUID ship) {
        var job = warming.remove(ship);
        if (job != null) job.release();
    }

    private static ServerLevel level(MinecraftServer server,String id) {
        return server.getLevel(ResourceKey.create(Registries.DIMENSION,new ResourceLocation(id)));
    }
    private void advance(MinecraftServer server,Approach leg) {
        ServerLevel from=level(server,leg.from), to=level(server,leg.to);
        if(from==null||to==null) return;
        var pilot=server.getPlayerList().getPlayer(leg.pilot);
        if(pilot==null) return;
        if(leg.ship==0) {
            if(from.getBlockEntity(leg.console) instanceof ShipCoreBlockEntity core && core.id().equals(leg.core) && core.originalRocket()!=null) {
                // The pre-assembly world save survived; allow the pilot to request the approach again.
                approaches.remove(leg.core);setDirty();return;
            }
            // If VS moved the blocks before the result was recorded, rediscover the actual moved console.
            ShipCoreBlockEntity moved=CoreRegistry.find(leg.core).orElse(null);
            if(moved==null || moved.getLevel()!=from || !leg.rocket.equals(moved.originalRocket())) return;
            var physical=VS.inspect(from,moved.getBlockPos()); if(physical.isEmpty()) return;
            BlockPos offset=moved.getBlockPos().subtract(leg.console);
            var crew=leg.crew.stream().map(r -> new Rider(r.id,r.local.add(offset.getX(),offset.getY(),offset.getZ()),r.yaw,r.pitch)).toList();
            put(new Approach(leg.core,leg.rocket,leg.pilot,leg.from,leg.to,moved.getBlockPos(),leg.berth,leg.route,leg.fare,physical.get().shipId(),crew),server);
            return;
        }
        if(VS.belongsTo(to,leg.ship)) { releaseStorage(leg.core); dev.starbridge.orbit.OrbitModule.complete(server,leg.core); approaches.remove(leg.core);reportedHandoffs.remove(leg.core);setDirty();return; }
        var consoleChunk = new ChunkPos(leg.console);
        var loaded = from.getChunkSource().getChunkNow(consoleChunk.x, consoleChunk.z);
        if (loaded == null) {
            from.getChunkSource().addRegionTicket(net.minecraft.server.level.TicketType.POST_TELEPORT, consoleChunk, 1, 0);
            reportHandoff(leg, "waiting for the console chunk");
            return;
        }
        if (!(loaded.getBlockEntity(leg.console) instanceof ShipCoreBlockEntity core)
                || !core.id().equals(leg.core) || !leg.rocket.equals(core.originalRocket())) {
            reportHandoff(leg, "waiting for the relocated console");
            return;
        }
        if (VS.inspectById(from, leg.ship).isEmpty()) {
            reportHandoff(leg, "waiting for VS to load the ship");
            return;
        }
        reportHandoff(leg, "VS ship and console ready");
        if(!core.authorized(pilot) || pilot.level()!=from) return;
        List<ServerPlayer> passengers = new ArrayList<>(leg.crew.size());
        for (Rider rider : leg.crew) {
            var passenger = server.getPlayerList().getPlayer(rider.id);
            if (passenger == null || !passenger.isAlive()) {
                releaseStorage(leg.core);
                return;
            }
            passengers.add(passenger);
        }
        var bounds = VS.storageBoundsById(from, leg.ship).orElseThrow();
        int tick = server.getTickCount();
        if (ApproachChunks.helps(bounds)) {
            var job = warming.get(leg.core);
            if (job == null) {
                if (warming.size() >= ACTIVE_WARMERS) return;
                job = new ApproachChunks(leg.core, from, to, bounds, passengers);
                warming.put(leg.core, job);
            }
            if (!job.ready(tick)) return;
        }
        if (lastPassageTick == tick) return;
        lastPassageTick = tick;
        try {
            transferCrewAndHull(server, leg, pilot, core, from, to);
        } finally {
            releaseStorage(leg.core);
        }
    }

    private void transferCrewAndHull(MinecraftServer server, Approach leg, ServerPlayer pilot,
                                     ShipCoreBlockEntity core, ServerLevel from, ServerLevel to) {
        for(Rider r:leg.crew) {
            ServerPlayer person=server.getPlayerList().getPlayer(r.id); if(person==null || !person.isAlive()) return;
            Vec3 pos=HullTransit.toWorld(from,leg.ship,r.local).orElseThrow();
            if(person.level()!=from || person.position().distanceToSqr(pos)>0.04) {
                person.teleportTo(from,pos.x,pos.y,pos.z,r.yaw,r.pitch);person.setDeltaMovement(Vec3.ZERO);person.fallDistance=0;
            }
        }
        HullTransit.begin(core,pilot,to,leg.berth,leg.route,leg.fare);
        dev.starbridge.orbit.OrbitModule.complete(server,leg.core);
        approaches.remove(leg.core);
        reportedHandoffs.remove(leg.core);
        setDirty();
    }

    private void reportHandoff(Approach leg, String status) {
        if (Boolean.getBoolean("starbridge.test.reserveLandingStorage")
                && !status.equals(reportedHandoffs.put(leg.core, status))) {
            Starbridge.LOGGER.info("Cabin handoff for ship {} at {}: {}", leg.ship, leg.console.toShortString(), status);
        }
    }

    private void put(Approach leg,MinecraftServer server){approaches.put(leg.core,leg);setDirty();server.overworld().getDataStorage().save();}
    private static CompoundTag vec(Vec3 value){var t=new CompoundTag();t.putDouble("X",value.x);t.putDouble("Y",value.y);t.putDouble("Z",value.z);return t;}
    private static Vec3 vec(CompoundTag t){return new Vec3(t.getDouble("X"),t.getDouble("Y"),t.getDouble("Z"));}
    @Override public CompoundTag save(CompoundTag root){
        var list=new ListTag();for(Approach a:approaches.values()){
            var t=new CompoundTag();t.putUUID("Core",a.core);t.putUUID("Rocket",a.rocket);t.putUUID("Pilot",a.pilot);t.putString("From",a.from);t.putString("To",a.to);
            t.putLong("Pos",a.console.asLong());t.put("Berth",vec(a.berth));t.putString("Route",a.route);t.putInt("Fare",a.fare);t.putLong("Ship",a.ship);
            var crew=new ListTag();for(Rider r:a.crew){var v=vec(r.local);v.putUUID("Id",r.id);v.putFloat("Yaw",r.yaw);v.putFloat("Pitch",r.pitch);crew.add(v);}t.put("Crew",crew);list.add(t);
        }root.put("Approaches",list);return root;
    }
    private static CabinApproach load(CompoundTag root){var data=new CabinApproach();for(Tag row:root.getList("Approaches",Tag.TAG_COMPOUND)){
        var t=(CompoundTag)row;var crew=new ArrayList<Rider>();for(Tag r:t.getList("Crew",Tag.TAG_COMPOUND)){var v=(CompoundTag)r;crew.add(new Rider(v.getUUID("Id"),vec(v),v.getFloat("Yaw"),v.getFloat("Pitch")));}
        var a=new Approach(t.getUUID("Core"),t.getUUID("Rocket"),t.getUUID("Pilot"),t.getString("From"),t.getString("To"),BlockPos.of(t.getLong("Pos")),vec(t.getCompound("Berth")),t.getString("Route"),t.getInt("Fare"),t.getLong("Ship"),crew);data.approaches.put(a.core,a);
    }return data;}
}
