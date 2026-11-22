package dev.starbridge.transit;

import dev.starbridge.compat.vs.VsShipBackend.StorageBounds;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.StringTagVisitor;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
import java.util.LinkedHashMap;

/** Fingerprint every occupied voxel and block entity while the source and destination are in the same server turn. */
final class HullManifest {
    private HullManifest() { }
    record Seal(String digest, int blocks) { }
    static Seal inspect(ServerLevel level, StorageBounds b) { return inspect(level, b, null); }
    static Seal inspect(ServerLevel level, StorageBounds b, TransitJournal.Leg passage) {
        return scan(level, b, passage, null);
    }

    record Machine(BlockState state, CompoundTag data) {}
    record Departure(Seal seal, Map<BlockPos, Machine> machines) {}

    /** Keep the exact machine tags that were hashed, ready to restore after native transfer. */
    static Departure capture(ServerLevel level, StorageBounds bounds) {
        Map<BlockPos, Machine> machines = new LinkedHashMap<>();
        Seal seal = scan(level, bounds, null, machines);
        return new Departure(seal, machines);
    }

    private static Seal scan(ServerLevel level, StorageBounds b, TransitJournal.Leg passage,
                             Map<BlockPos, Machine> machines) {
        MessageDigest digest;
        try { digest=MessageDigest.getInstance("SHA-256"); } catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
        int blocks=0;
        BlockPos.MutableBlockPos pos=new BlockPos.MutableBlockPos();
        for(int xChunk=b.minX()>>4; xChunk<=b.maxX()>>4; xChunk++) {
            for(int zChunk=b.minZ()>>4; zChunk<=b.maxZ()>>4; zChunk++) {
                LevelChunk chunk=level.getChunk(xChunk,zChunk);
                for(int y=b.minY(); y<=b.maxY(); y++) {
                    if (level.isOutsideBuildHeight(y)) continue;
                    var section=chunk.getSection(level.getSectionIndex(y));
                    if(section.hasOnlyAir()) { y=Math.min(b.maxY(),y|15);continue; }
                    for(int z=Math.max(zChunk<<4,b.minZ());z<=Math.min((zChunk<<4)+15,b.maxZ());z++) {
                        for(int x=Math.max(xChunk<<4,b.minX());x<=Math.min((xChunk<<4)+15,b.maxX());x++) {
                            BlockState state=section.getBlockState(x&15,y&15,z&15);
                            if(state.isAir()) continue;
                            blocks++; put(digest,x-b.minX());put(digest,y-b.minY());put(digest,z-b.minZ());put(digest,Block.getId(state));
                            if(state.hasBlockEntity()) {
                                var entity=chunk.getBlockEntity(pos.set(x,y,z));
                                if(entity==null) throw new IllegalStateException("Missing block entity at "+pos);
                                CompoundTag tag=entity.saveWithFullMetadata();
                                appendEntity(digest, pos, tag, passage);
                                if (machines != null) machines.put(pos.immutable(), new Machine(state, tag));
                            }
                        }
                    }
                }
            }
        }
        return new Seal(HexFormat.of().formatHex(digest.digest()),blocks);
    }
    static void appendEntity(MessageDigest digest, BlockPos pos, CompoundTag tag, TransitJournal.Leg passage) {
        // Translate only the exact debit on the billed core. Every other byte of the manifest is compared as before.
        CompoundTag before = passage == null ? tag : passage.beforePayment(pos, tag);
        byte[] bytes = new StringTagVisitor().visit(before).getBytes(StandardCharsets.UTF_8);
        put(digest, bytes.length);
        digest.update(bytes);
    }
    static void restoreMovedMachines(ServerLevel source,ServerLevel destination,Map<BlockPos,Machine> original) {
        for(var entry:original.entrySet()) {
            BlockPos pos=entry.getKey(); var saved=entry.getValue();
            if(!source.getBlockState(pos).isAir() || source.getBlockEntity(pos)!=null)continue;
            if(!destination.getBlockState(pos).equals(saved.state()))continue;
            var arrived=destination.getBlockEntity(pos);
            if(arrived==null)continue;
            var live=arrived.saveWithFullMetadata();
            if(live.equals(saved.data()))continue;
            var keys=new java.util.TreeSet<String>(live.getAllKeys());keys.addAll(saved.data().getAllKeys());
            keys.removeIf(k->java.util.Objects.equals(live.get(k),saved.data().get(k)));
            dev.starbridge.Starbridge.LOGGER.info("Restoring native passage state for {} at {}. Changed fields {}",saved.data().getString("id"),pos,keys);
            arrived.load(saved.data().copy());arrived.setChanged();
            if(arrived instanceof dev.starbridge.block.ShipCoreBlockEntity core)core.register();
        }
    }
    private static void put(MessageDigest digest,int value) { digest.update((byte)(value>>>24));digest.update((byte)(value>>>16));digest.update((byte)(value>>>8));digest.update((byte)value); }
}
