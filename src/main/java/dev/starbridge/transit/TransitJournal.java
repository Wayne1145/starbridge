package dev.starbridge.transit;

import dev.starbridge.block.ShipCoreBlockEntity;
import dev.starbridge.compat.vs.VsShipBackend.StorageBounds;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.*;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.phys.Vec3;

import java.io.*;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.util.*;

/** A small write-ahead journal. A failed write leaves the prior generation authoritative. */
final class TransitJournal {
    private final Path file;
    private Map<UUID, Leg> entries;
    private TransitJournal(Path file, Map<UUID, Leg> entries) { this.file=file; this.entries=entries; }
    record Rider(UUID player, Vec3 local, float yaw, float pitch) {}
    /** Records the exact hull and core state immediately before a fare, including any furnace activity since arrival. */
    record FareProof(String hullDigest, int energyBefore, UUID priorReceipt) {
        FareProof {
            if (!hullDigest.matches("[0-9a-f]{64}") || energyBefore < 0) throw new IllegalArgumentException("Invalid pre-fare hull proof");
        }
        static FareProof capture(CompoundTag core, String hullDigest) {
            if (!core.contains("Energy", Tag.TAG_INT)) throw new IllegalStateException("Missing drive energy");
            return new FareProof(hullDigest, core.getInt("Energy"), core.hasUUID("PaidVoyage") ? core.getUUID("PaidVoyage") : null);
        }
        CompoundTag beforePayment(CompoundTag current, UUID coreId, UUID voyage, int fare) {
            if (!current.hasUUID("CoreId") || !coreId.equals(current.getUUID("CoreId"))
                    || !current.hasUUID("PaidVoyage") || !voyage.equals(current.getUUID("PaidVoyage"))
                    || !current.contains("Energy", Tag.TAG_INT) || current.getInt("Energy") != energyBefore - fare) return current;
            CompoundTag before = current.copy();
            before.putInt("Energy", energyBefore);
            if (priorReceipt == null) before.remove("PaidVoyage");
            else before.putUUID("PaidVoyage", priorReceipt);
            return before;
        }
    }
    record Leg(UUID id, UUID core, long ship, BlockPos corePos, String source, String destination, Vec3 berth,
               String route, int fare, List<Rider> crew, Set<UUID> done, boolean committed, StorageBounds bounds, String digest, FareProof fareProof) {
        Leg {
            crew=List.copyOf(crew); done=Set.copyOf(done);
            if (fareProof != null && (fareProof.energyBefore() < fare || id.equals(fareProof.priorReceipt())))
                throw new IllegalArgumentException("Invalid fare proof for passage");
            if (crew.isEmpty() || fare<=0 || source.equals(destination) || crew.stream().map(Rider::player).distinct().count()!=crew.size()
                    || !crew.stream().map(Rider::player).toList().containsAll(done) || !digest.matches("[0-9a-f]{64}")) throw new IllegalArgumentException("Invalid crew manifest");
        }
        static Leg create(ShipCoreBlockEntity core, long ship, ServerLevel from, ServerLevel to, Vec3 berth, String route,
                          int fare, List<Rider> crew, StorageBounds bounds, String digest) {
            return new Leg(UUID.randomUUID(),core.id(),ship,core.getBlockPos(),from.dimension().location().toString(),
                    to.dimension().location().toString(),berth,route,fare,crew,Set.of(),false,bounds,digest,null);
        }
        Leg with(Set<UUID> delivered, boolean paid) { return new Leg(id,core,ship,corePos,source,destination,berth,route,fare,crew,delivered,paid,bounds,digest,fareProof); }
        Leg withFareProof(FareProof proof) {
            return new Leg(id,core,ship,corePos,source,destination,berth,route,fare,crew,done,committed,bounds,digest,proof);
        }
        String expectedArrivalDigest() { return fareProof == null ? digest : fareProof.hullDigest(); }
        CompoundTag beforePayment(BlockPos pos, CompoundTag current) {
            if (fareProof == null || !corePos.equals(pos)) return current;
            return fareProof.beforePayment(current, core, id, fare);
        }
    }
    static TransitJournal open(MinecraftServer server) { return open(server.getWorldPath(LevelResource.ROOT).resolve("data/starbridge_passages.dat")); }
    static TransitJournal open(Path file) {
        try {
            if (!Files.exists(file)) return new TransitJournal(file,Map.of());
            CompoundTag root=NbtIo.read(file.toFile());
            if (root==null || root.getInt("Format")!=1 || !root.contains("Legs",Tag.TAG_LIST)) throw new IOException("Unsupported passage journal");
            Map<UUID,Leg> legs=new LinkedHashMap<>();
            for (Tag value:root.getList("Legs",Tag.TAG_COMPOUND)) {
                Leg leg=decode((CompoundTag)value);
                if (legs.put(leg.id(),leg)!=null) throw new IOException("Duplicate passage");
            }
            return new TransitJournal(file,legs);
        } catch(IOException|RuntimeException ex) { throw new IllegalStateException("Cannot read passage journal "+file,ex); }
    }
    Collection<Leg> legs() { return entries.values(); }
    boolean locked(long ship) { return entries.values().stream().anyMatch(leg -> leg.ship()==ship); }
    void prepare(Leg leg) {
        if (locked(leg.ship()) || entries.containsKey(leg.id())) throw new IllegalStateException("Ship has an unfinished passage");
        update(leg.id(),leg);
    }
    Leg preparePayment(Leg leg, FareProof proof) {
        if (!leg.equals(entries.get(leg.id())) || leg.committed() || leg.fareProof() != null)
            throw new IllegalStateException("Cannot replace the fare proof");
        Leg next = leg.withFareProof(Objects.requireNonNull(proof));
        update(leg.id(), next);
        return next;
    }
    Leg commit(Leg leg) { Leg next=leg.with(leg.done(),true); update(leg.id(),next); return next; }
    Leg deliver(Leg leg, UUID player) {
        if (!leg.committed()) throw new IllegalStateException("Hull is unconfirmed");
        Set<UUID> done=new HashSet<>(leg.done()); done.add(player); Leg next=leg.with(done,true); update(leg.id(),next); return next;
    }
    void remove(Leg leg) { update(leg.id(),null); }
    private void update(UUID id, Leg next) {
        Map<UUID,Leg> copy=new LinkedHashMap<>(entries); if(next==null) copy.remove(id); else copy.put(id,next);
        ListTag list=new ListTag(); copy.values().forEach(v -> list.add(encode(v))); CompoundTag root=new CompoundTag(); root.putInt("Format",1); root.put("Legs",list);
        try {
            Files.createDirectories(file.getParent());
            Path staging=file.resolveSibling(file.getFileName()+".new");
            try(FileChannel channel=FileChannel.open(staging, StandardOpenOption.WRITE,StandardOpenOption.CREATE,StandardOpenOption.TRUNCATE_EXISTING)) {
                DataOutputStream out=new DataOutputStream(Channels.newOutputStream(channel)); NbtIo.write(root,out); out.flush(); channel.force(true);
            }
            Files.move(staging,file,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);
            entries=copy;
        } catch(IOException ex) { throw new IllegalStateException("Could not atomically persist passage journal",ex); }
    }
    private static CompoundTag encode(Leg v) {
        CompoundTag tag=new CompoundTag(); tag.putUUID("Id",v.id()); tag.putUUID("Core",v.core()); tag.putLong("Ship",v.ship());
        tag.putLong("CorePos",v.corePos().asLong());tag.putString("Source",v.source()); tag.putString("Destination",v.destination()); tag.put("Berth",point(v.berth()));
        tag.putString("Route",v.route()); tag.putInt("Fare",v.fare()); tag.putBoolean("Committed",v.committed()); tag.putString("Hull",v.digest());
        if (v.fareProof() != null) {
            FareProof proof = v.fareProof();
            CompoundTag state = new CompoundTag();
            state.putString("Hull", proof.hullDigest());
            state.putInt("Energy", proof.energyBefore());
            if (proof.priorReceipt() != null) state.putUUID("PaidVoyage", proof.priorReceipt());
            tag.put("FareProof", state);
        }
        StorageBounds b=v.bounds(); tag.putIntArray("Bounds",new int[]{b.minX(),b.minY(),b.minZ(),b.maxX(),b.maxY(),b.maxZ()});
        ListTag crew=new ListTag(); for(Rider rider:v.crew()) {
            CompoundTag r=point(rider.local()); r.putUUID("Player",rider.player()); r.putFloat("Yaw",rider.yaw()); r.putFloat("Pitch",rider.pitch());
            r.putBoolean("Done",v.done().contains(rider.player())); crew.add(r);
        } tag.put("Crew",crew); return tag;
    }
    private static Leg decode(CompoundTag tag) {
        List<Rider> crew=new ArrayList<>(); Set<UUID> done=new HashSet<>();
        for(Tag entry:tag.getList("Crew",Tag.TAG_COMPOUND)) {
            CompoundTag r=(CompoundTag)entry; UUID p=r.getUUID("Player"); crew.add(new Rider(p,point(r),r.getFloat("Yaw"),r.getFloat("Pitch")));
            if(r.getBoolean("Done")) done.add(p);
        }
        FareProof proof = null;
        if (tag.contains("FareProof")) {
            CompoundTag state = tag.getCompound("FareProof");
            if (state.contains("PaidVoyage") && !state.hasUUID("PaidVoyage")) throw new IllegalArgumentException("Invalid prior fare receipt");
            proof = FareProof.capture(state, state.getString("Hull"));
        }
        int[] b=tag.getIntArray("Bounds"); if(b.length!=6) throw new IllegalArgumentException("Missing physical hull bounds");
        return new Leg(tag.getUUID("Id"),tag.getUUID("Core"),tag.getLong("Ship"),BlockPos.of(tag.getLong("CorePos")),tag.getString("Source"),tag.getString("Destination"),
                point(tag.getCompound("Berth")),tag.getString("Route"),tag.getInt("Fare"),crew,done,tag.getBoolean("Committed"),new StorageBounds(b[0],b[1],b[2],b[3],b[4],b[5]),tag.getString("Hull"),proof);
    }
    private static CompoundTag point(Vec3 p) { CompoundTag t=new CompoundTag(); t.putDouble("X",p.x);t.putDouble("Y",p.y);t.putDouble("Z",p.z);return t; }
    private static Vec3 point(CompoundTag t) {
        Vec3 p=new Vec3(t.getDouble("X"),t.getDouble("Y"),t.getDouble("Z"));
        if(!t.contains("X",Tag.TAG_DOUBLE)||!t.contains("Y",Tag.TAG_DOUBLE)||!t.contains("Z",Tag.TAG_DOUBLE)||!Double.isFinite(p.x)||!Double.isFinite(p.y)||!Double.isFinite(p.z)) throw new IllegalArgumentException("Invalid manifest coordinates");
        return p;
    }
}
