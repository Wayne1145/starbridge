package dev.starbridge.flightqa;

import com.google.gson.JsonObject;
import com.mojang.serialization.JsonOps;
import java.nio.file.*;
import java.util.TreeMap;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.*;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.storage.LevelResource;

/** Diagnostic witness from the test world, with no block tickets and no extra save. */
public final class MachineWitness {
    public static void capture(MinecraftServer server, String label) throws Exception {
        if (!MissionSignals.JOINED || !Files.isRegularFile(Path.of("starbridge-crash-restart.fixture"))) return;
        Path out=Path.of("restart-machines-"+label+".json"),file=server.getWorldPath(LevelResource.ROOT).resolve("data/starbridge_passages.dat");
        if (Files.exists(out)||!Files.exists(file)) return;
        var list=NbtIo.read(file.toFile()).getList("Legs",Tag.TAG_COMPOUND);
        if(list.size()!=1)return;var leg=list.getCompound(0);if(!leg.getString("Route").equals("home"))return;
        var b=leg.getIntArray("Bounds");var level=server.overworld();var cells=new TreeMap<String,Object>();
        for (var p:BlockPos.betweenClosed(b[0],b[1],b[2],b[3],b[4],b[5])){
            var chunk=level.getChunkSource().getChunkNow(p.getX()>>4,p.getZ()>>4);if(chunk==null)return;
            var state=chunk.getBlockState(p);if(state.isAir())continue;
            var row=new JsonObject();row.addProperty("state",state.toString());row.addProperty("runtimeId",Block.getId(state));var machine=chunk.getBlockEntity(p);
            if(machine!=null){var data=machine.saveWithFullMetadata();row.add("data",NbtOps.INSTANCE.convertTo(JsonOps.INSTANCE,data));row.addProperty("snbt",new StringTagVisitor().visit(data));}
            cells.put(p.toShortString(),row);
        }
        if(!cells.isEmpty())MissionSignals.write(out,cells);
    }
}
