package dev.starbridge.qa.home.landing;

import com.google.gson.GsonBuilder;
import dev.starbridge.compat.vs.VsShipBackend;
import dev.starbridge.compat.vs.VsShipBackend.StorageBounds;
import dev.starbridge.block.ShipCoreBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.world.level.ServerLevelAccessor;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.server.ServerLifecycleHooks;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Only stores timestamps and reads existing chunk references. It never requests a chunk for the measurements. */
public final class ApproachTiming {
    private static final boolean ENABLED = Files.isRegularFile(Path.of("starbridge-wide-approach.fixture")) || Files.isRegularFile(Path.of("remote-home.fixture")) || Files.isRegularFile(Path.of("remote-wide-berths.fixture"));
    private static final Map<String,Long> starts = new HashMap<>();
    private static final List<Map<String,Object>> events = new ArrayList<>();
    private static final List<Map<String,Object>> footprint = new ArrayList<>();
    private static boolean active;
    private static Thread placementThread;
    private static int placementDepth;
    private static int chunkLookupDepth;
    private static int chunkLookups;
    private static long chunkLookupStart;
    private static long chunkLookupNs;
    private static int managedDepth;
    private static int managedCalls;
    private static long managedStart;
    private static long managedNs;

    public static void enterFixed(ServerLevel source, StorageBounds bounds) {
        if (!ENABLED || !source.getServer().isSameThread()) return;
        active = true;
        readiness("fixedCabin",source,bounds);
        start("assembleAndRecordApproach");
    }
    public static void enterNative(ShipCoreBlockEntity core, ServerPlayer captain, ServerLevel target) {
        if (!active) return;
        var source = captain.serverLevel();
        var vs = new VsShipBackend();
        vs.inspect(source,core.getBlockPos()).flatMap(s -> vs.storageBoundsById(source,s.shipId())).ifPresent(bounds -> {
            readiness("nativeSource",source,bounds); readiness("nativeDestination",target,bounds);
        });
        start("beginPassage");
    }
    private static void readiness(String phase, ServerLevel level, StorageBounds box) {
        int count=0, full=0;
        for (int x=box.minX()>>4;x<=box.maxX()>>4;x++) for (int z=box.minZ()>>4;z<=box.maxZ()>>4;z++) {
            count++; if (level.getChunkSource().getChunkNow(x,z)!=null) full++;
        }
        footprint.add(Map.of("phase",phase,"tick",level.getServer().getTickCount(),"world",level.dimension().location().toString(),
                "bounds",List.of(box.minX(),box.minY(),box.minZ(),box.maxX(),box.maxY(),box.maxZ()),"totalChunks",count,"readyChunks",full));
    }
    public static void start(String name) { if(active && ServerLifecycleHooks.getCurrentServer().isSameThread()) starts.put(name,System.nanoTime()); }
    public static void end(String name) {
        if (!active || !ServerLifecycleHooks.getCurrentServer().isSameThread()) return;
        Long start=starts.remove(name);
        if (start!=null) events.add(Map.of("phase", name, "tick",ServerLifecycleHooks.getCurrentServer().getTickCount(),"ns",System.nanoTime()-start));
    }
    public static void enterTemplate(ServerLevelAccessor level, BlockPos origin, Vec3i size) {
        var server = ServerLifecycleHooks.getCurrentServer();
        if (server == null || !server.isSameThread() || !starts.containsKey("nativeAssembly")) return;
        if (placementDepth++ != 0) return;
        placementThread = Thread.currentThread();
        if (level instanceof ServerLevel world && size.getX() > 0 && size.getY() > 0 && size.getZ() > 0) {
            var end = origin.offset(size.getX() - 1, size.getY() - 1, size.getZ() - 1);
            readiness("templateDestination", world, new StorageBounds(origin.getX(), origin.getY(), origin.getZ(),
                    end.getX(), end.getY(), end.getZ()));
        }
        chunkLookupDepth = 0;
        chunkLookups = 0;
        chunkLookupNs = 0;
        managedDepth = 0;
        managedCalls = 0;
        managedNs = 0;
        start("vsTemplatePlacement");
    }

    public static void leaveTemplate() {
        if (Thread.currentThread() != placementThread || --placementDepth != 0) return;
        end("vsTemplatePlacement");
        events.add(Map.of("phase", "vsTemplateChunkLookups", "tick", ServerLifecycleHooks.getCurrentServer().getTickCount(),
                "ns", chunkLookupNs, "count", chunkLookups));
        events.add(Map.of("phase", "vsTemplateManagedChunkTasks", "tick", ServerLifecycleHooks.getCurrentServer().getTickCount(),
                "ns", managedNs, "count", managedCalls));
        placementThread = null;
    }

    public static void enterChunkLookup() {
        if (Thread.currentThread() != placementThread || chunkLookupDepth++ != 0) return;
        chunkLookupStart = System.nanoTime();
    }

    public static void leaveChunkLookup() {
        if (Thread.currentThread() != placementThread || --chunkLookupDepth != 0) return;
        chunkLookupNs += System.nanoTime() - chunkLookupStart;
        chunkLookups++;
    }

    public static void enterManagedChunkTasks() {
        if (Thread.currentThread() != placementThread || managedDepth++ != 0) return;
        managedStart = System.nanoTime();
    }

    public static void leaveManagedChunkTasks() {
        if (Thread.currentThread() != placementThread || --managedDepth != 0) return;
        managedNs += System.nanoTime() - managedStart;
        managedCalls++;
    }

    public static void checkpoint() {
        if (!active) return;
        Map<String,Object> result=new LinkedHashMap<>(); result.put("footprints",List.copyOf(footprint)); result.put("intervals",List.copyOf(events));
        try {Files.writeString(Path.of("landing-profile.json"),new GsonBuilder().setPrettyPrinting().create().toJson(result));}
        catch(java.io.IOException error){throw new IllegalStateException(error);}
    }
    public static void stop() { checkpoint(); active = false; }
    private ApproachTiming() {}
}
