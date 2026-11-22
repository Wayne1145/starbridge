package dev.starbridge.transit;

import dev.starbridge.block.ShipCoreBlockEntity;
import dev.starbridge.compat.vs.VsShipBackend;
import dev.starbridge.compat.vs.VsShipBackend.StorageBounds;
import dev.starbridge.orbit.OrbitModule;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.*;

/** Lift off from an original world by moving the same cells out of physics storage. Empty reserved areas have no game blocks. */
public final class SurfaceDeparture extends SavedData {
    private static final VsShipBackend VS = new VsShipBackend();
    private record Deck(UUID core, net.minecraft.resources.ResourceKey<net.minecraft.world.level.Level> world, Vec3 feet, long until) {}
    private static final Map<UUID,Deck> arrivals=new HashMap<>();
    public static void register() {
        SurfaceLiftChunks.register();
        net.minecraftforge.common.MinecraftForge.EVENT_BUS.addListener((net.minecraftforge.event.TickEvent.ServerTickEvent event)->{
            if(event.phase!=net.minecraftforge.event.TickEvent.Phase.END || arrivals.isEmpty())return;
            var server=event.getServer();
            for(var entry:List.copyOf(arrivals.entrySet())) {
                var player=server.getPlayerList().getPlayer(entry.getKey());var deck=entry.getValue();
                if(player==null || player.level().dimension()!=deck.world || server.getTickCount()>deck.until) {arrivals.remove(entry.getKey());continue;}
                var core=dev.starbridge.server.CoreRegistry.find(deck.core).orElse(null);
                if(core==null || OrbitModule.cabinBounds(core).isEmpty()){arrivals.remove(entry.getKey());continue;}
                if(player.getY()<deck.feet.y-3 || player.position().distanceToSqr(deck.feet)>32*32) {
                    player.teleportTo(player.serverLevel(),deck.feet.x,deck.feet.y,deck.feet.z,player.getYRot(),player.getXRot());
                    player.setDeltaMovement(Vec3.ZERO);player.fallDistance=0;
                }
            }
        });
        net.minecraftforge.common.MinecraftForge.EVENT_BUS.addListener((net.minecraftforge.event.server.ServerStoppedEvent event)->arrivals.clear());
    }
    private static final String CREW_RETURN = "starbridge:surface_handoff";
    private final Map<UUID, Integer> berths = new HashMap<>();
    private int nextSlot;
    private SurfaceDeparture() {}
    private static SurfaceDeparture get(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(SurfaceDeparture::load, SurfaceDeparture::new,"starbridge_orbital_berths");
    }
    private record Rider(ServerPlayer player, Vec3 local) {}
    public static ShipCoreBlockEntity lift(ServerPlayer pilot, ShipCoreBlockEntity core) {
        ServerLevel level=pilot.serverLevel();
        if(!pilot.isAlive() || pilot.isSpectator() || !core.authorized(pilot) || core.originalRocket()==null || !HullTransit.isAboard(pilot,core))
            throw new IllegalStateException("nav.starbridge.flight.board");
        var ship=VS.inspect(level,core.getBlockPos()).orElseThrow(() -> new IllegalStateException("Surface ship is loading"));
        var box=VS.storageBoundsById(level,ship.shipId()).orElseThrow();
        HullTransit.checkExtent(box);
        int width=box.maxX()-box.minX()+1, height=box.maxY()-box.minY()+1, depth=box.maxZ()-box.minZ()+1;
        var cells=CabinApproach.cabinBlocks(level,box);
        var destination=get(level).chooseBerth(level,core.id(),width,height,depth);
        BlockPos corner=new BlockPos(destination.getX()-(width/2),destination.getY()-(height/2),destination.getZ()-(depth/2));
        BlockPos offset=corner.subtract(new BlockPos(box.minX(),box.minY(),box.minZ()));
        List<Rider> crew=new ArrayList<>();
        for (ServerPlayer player:level.players()) {
            if (player.isSpectator() || !player.isAlive()) continue;
            Vec3 local=HullTransit.toShipLocal(level,ship.shipId(),player.position()).orElseThrow();
            if (!HullTransit.onDeck(level,box,local)) continue;
            if (player.isPassenger() || player.isVehicle()) throw new IllegalStateException("nav.starbridge.flight.dismount");
            crew.add(new Rider(player,local));
        }
        if (crew.stream().noneMatch(r->r.player==pilot)) throw new IllegalStateException("nav.starbridge.flight.board");
        // Keep exact rider offsets before making any change to the cabin. The login hook can finish an interrupted move.
        for (Rider r:crew) remember(r,core,level,offset);
        level.getServer().getPlayerList().saveAll();
        core.release();
        VS.setStatic(level,ship.shipId(),true);
        var result=VS.detachCabin(level,ship.shipId(),cells,box,destination);
        if(result.dx()!=offset.getX()||result.dy()!=offset.getY()||result.dz()!=offset.getZ()) throw new IllegalStateException("Surface transfer offsets differ from the reserved berth");
        BlockPos movedPos=core.getBlockPos().offset(offset);
        if (!(level.getBlockEntity(movedPos) instanceof ShipCoreBlockEntity moved) || !moved.id().equals(core.id())) throw new IllegalStateException("Cabin console has not reached the reserved berth");
        for (BlockPos old:cells) {
            if(!level.getBlockState(old).isAir() || level.getBlockState(old.offset(offset)).isAir()) throw new IllegalStateException("Cabin verification interrupted during surface launch");
        }
        moved.register();
        BlockPos last=corner.offset(width-1,height-1,depth-1);
        OrbitModule.bindCabin(moved,corner,last);
        VS.retireEmptyShip(level,ship.shipId());
        for (Rider r:crew) restoreCrew(r.player);
        return moved;
    }
    private BlockPos chooseBerth(ServerLevel level,UUID ship,int width,int height,int depth) {
        for(int attempt=0;attempt<8;attempt++) {
            int slot=berths.computeIfAbsent(ship,ignored -> nextSlot++);setDirty();
            // The reservation follows this ship across journeys. Never replace terrain or another occupied berth.
            int x=20000+Math.floorMod(slot,256)*384,z=20000+Math.floorDiv(slot,256)*384;
            int y=level.getMaxBuildHeight()-5-height/2;
            int minX=x-width/2,minY=y-height/2,minZ=z-depth/2;
            var bounds=new AABB(minX,minY,minZ,minX+width,minY+height,minZ+depth);
            if (!level.getWorldBorder().isWithinBounds(minX,minZ) || !level.getWorldBorder().isWithinBounds(minX+width,minZ+depth) || minY<level.getMinBuildHeight()+1)
                throw new IllegalStateException("nav.starbridge.flight.cabin_space");
            boolean clear=!VS.intersectsShip(level,bounds)&&level.getEntities((net.minecraft.world.entity.Entity)null,bounds,e->true).isEmpty();
            for(int cx=minX>>4;clear && cx<=(minX+width-1)>>4;cx++) {
                for(int cz=minZ>>4;clear && cz<=(minZ+depth-1)>>4;cz++) {
                    var chunk=level.getChunk(cx,cz);
                    for(int bx=Math.max(minX,cx<<4);clear && bx<Math.min(minX+width,(cx+1)<<4);bx++)
                        for(int bz=Math.max(minZ,cz<<4);clear && bz<Math.min(minZ+depth,(cz+1)<<4);bz++)
                            clear=chunk.getHeight(Heightmap.Types.WORLD_SURFACE,bx&15,bz&15)<minY;
                }
            }
            if(clear){level.getDataStorage().save();return new BlockPos(x,y,z);}
            berths.remove(ship);setDirty();
        }
        throw new IllegalStateException("nav.starbridge.flight.cabin_space");
    }
    private static void remember(Rider r,ShipCoreBlockEntity core,ServerLevel level,BlockPos offset) {
        var tag=new CompoundTag();tag.putUUID("Id",core.id());tag.putLong("Core",core.getBlockPos().offset(offset).asLong());
        tag.putString("World",level.dimension().location().toString());tag.putDouble("X",r.local.x+offset.getX());tag.putDouble("Y",r.local.y+offset.getY());tag.putDouble("Z",r.local.z+offset.getZ());
        r.player.getPersistentData().put(CREW_RETURN,tag);
    }
    public static void restoreCrew(ServerPlayer p) {
        var data=p.getPersistentData(); if(!data.contains(CREW_RETURN,Tag.TAG_COMPOUND)) return;
        var tag=data.getCompound(CREW_RETURN);var level=p.serverLevel();
        if(!level.dimension().location().toString().equals(tag.getString("World"))) return;
        var console=BlockPos.of(tag.getLong("Core"));
        if (!(level.getBlockEntity(console) instanceof ShipCoreBlockEntity core)|| !core.id().equals(tag.getUUID("Id"))) return;
        Vec3 feet=new Vec3(tag.getDouble("X"),tag.getDouble("Y"),tag.getDouble("Z"));
        var box=OrbitModule.cabinBounds(core).orElse(null);
        if(box==null)return;
        feet=supportedSeat(level,box,feet);
        p.stopRiding();p.teleportTo(level,feet.x,feet.y,feet.z,p.getYRot(),p.getXRot());p.setDeltaMovement(Vec3.ZERO);p.fallDistance=0;
        arrivals.put(p.getUUID(),new Deck(core.id(),level.dimension(),feet,level.getServer().getTickCount()+120));
        data.remove(CREW_RETURN);
    }
    /** Keep the measured seat when there is headroom. Furnace, wall or stair collisions can need a neighbouring standing spot. */
    private static Vec3 supportedSeat(ServerLevel level,StorageBounds bounds,Vec3 requested) {
        BlockPos foot=BlockPos.containing(requested);
        Vec3 best=null;double bestDistance=Double.POSITIVE_INFINITY;
        for(BlockPos cell:BlockPos.betweenClosed(foot.offset(-3,-2,-3),foot.offset(3,2,3))) {
            var floor=cell.below();
            if (floor.getX()<bounds.minX()||floor.getX()>bounds.maxX()||floor.getY()<bounds.minY()||floor.getY()>bounds.maxY()||floor.getZ()<bounds.minZ()||floor.getZ()>bounds.maxZ()) continue;
            var shape=level.getBlockState(floor).getCollisionShape(level,floor);
            if(shape.isEmpty())continue;
            double x=cell.equals(foot)?requested.x:cell.getX()+.5,z=cell.equals(foot)?requested.z:cell.getZ()+.5;
            double y=floor.getY()+shape.max(net.minecraft.core.Direction.Axis.Y)+.01;
            Vec3 test=new Vec3(x,y,z);double d=test.distanceToSqr(requested);
            if(d<bestDistance && level.noCollision(new AABB(x-.30,y,z-.30,x+.30,y+1.8,z+.30))) {best=test;bestDistance=d;}
        }
        if(best==null)throw new IllegalStateException("No supported standing space in orbital cabin");
        return best;
    }
    private static SurfaceDeparture load(CompoundTag state){var data=new SurfaceDeparture();data.nextSlot=state.getInt("Next");
        for(Tag entry:state.getList("Berths",Tag.TAG_COMPOUND)){var row=(CompoundTag)entry;if(row.hasUUID("Ship"))data.berths.put(row.getUUID("Ship"),row.getInt("Slot"));}return data;}
    @Override public CompoundTag save(CompoundTag state){var positions=new ListTag();berths.forEach((id,slot)->{var row=new CompoundTag();row.putUUID("Ship",id);row.putInt("Slot",slot);positions.add(row);});state.putInt("Next",nextSlot);state.put("Berths",positions);return state;}
}
