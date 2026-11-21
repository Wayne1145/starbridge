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

/** Fingerprint every occupied voxel and block entity while the source and destination are in the same server turn. */
final class HullManifest {
    private HullManifest() { }
    record Seal(String digest, int blocks) { }
    static Seal inspect(ServerLevel level, StorageBounds b) {
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
                                byte[] bytes=new StringTagVisitor().visit(tag).getBytes(StandardCharsets.UTF_8);
                                put(digest,bytes.length);digest.update(bytes);
                            }
                        }
                    }
                }
            }
        }
        return new Seal(HexFormat.of().formatHex(digest.digest()),blocks);
    }
    private static void put(MessageDigest digest,int value) { digest.update((byte)(value>>>24));digest.update((byte)(value>>>16));digest.update((byte)(value>>>8));digest.update((byte)value); }
}
