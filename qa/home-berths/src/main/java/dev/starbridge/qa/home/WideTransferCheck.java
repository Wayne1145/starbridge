package dev.starbridge.qa.home;

import dev.starbridge.compat.vs.VsShipBackend;
import dev.starbridge.compat.vs.VsShipBackend.StorageBounds;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.dedicated.DedicatedServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Time the real ship portal and production cargo stages on a closed copy of the returned 300-block ship. */
final class WideTransferCheck {
    static final Path MARKER = Path.of("starbridge-wide-transfer.fixture");
    private final Map<String, Object> evidence = new LinkedHashMap<>();
    private final Map<String, Long> times = new LinkedHashMap<>();
    private WideHullPreload preload;
    private long ship;
    private Vec3 berth;
    private int movedAt = -1;

    boolean tick(DedicatedServer server, VsShipBackend vs, Map<String,Object> proof, boolean spareTime) throws Exception {
        proof.put("wideTransfer", evidence);
        var source = server.overworld();
        var moonKey = ResourceKey.create(Registries.DIMENSION, ResourceLocation.parse("retrofit:legacy_dim_-28"));
        var moon = Objects.requireNonNull(server.getLevel(moonKey), "Original Galacticraft Moon is unavailable");
        if (ship == 0) {
            ship = Long.parseLong(Files.readString(MARKER).trim());
            evidence.put("shipId", ship);
            evidence.put("source", source.dimension().location().toString());
            evidence.put("destination", moon.dimension().location().toString());
            evidence.put("nanoseconds", times);
        }
        if (movedAt < 0) {
            var record = vs.inspectById(source, ship);
            if (record.isEmpty()) {
                // This saved ship is parked at (244.5,220.5,68.5) in the source trial.
                source.getChunk(244 >> 4, 68 >> 4);
                if (server.getTickCount() > 350) throw new IllegalStateException("Saved source ship did not load");
                return false;
            }
            var origin = record.get().worldPosition();
            var box = vs.storageBoundsById(source, ship).orElseThrow();
            evidence.put("bounds", List.of(box.minX(),box.minY(),box.minZ(),box.maxX(),box.maxY(),box.maxZ()));
            if (Files.isRegularFile(WideHullPreload.MARKER)) {
                String mode = Files.readString(WideHullPreload.MARKER).trim();
                if (mode.equals("wait")) {
                    // Match the final departure tick of the first late-inventory trial, without requesting any chunks.
                    if (server.getTickCount() < 289) return false;
                    evidence.put("waitOnlyUntilTick", 289);
                } else {
                    if (preload == null) preload = new WideHullPreload(source, box, mode.equals("chunks"));
                    if (server.getTickCount() > 2600) throw new IllegalStateException("Source preparation timed out");
                    if (!preload.step(spareTime)) return false;
                    evidence.put("sourcePreparation", preload.finish(mode.equals("edit")));
                }
            }
            try { runPortal(source, moon, vs, box, new Vec3(origin.x(),origin.y(),origin.z())); }
            finally { if (preload != null) preload.release(); }
            movedAt = server.getTickCount();
            evidence.put("portalTick", movedAt);
            return false;
        }
        var record = vs.inspectById(moon, ship);
        if (record.isPresent() && vs.belongsTo(moon,ship) && !vs.belongsTo(source,ship)) {
            var at = record.get().worldPosition();
            if (berth.distanceToSqr(at.x(), at.y(), at.z()) > .01) throw new IllegalStateException("Wrong arrival position");
            evidence.put("observedBerth", List.of(at.x(),at.y(),at.z()));
            evidence.put("verifiedArrivalTick", server.getTickCount());
            return true;
        }
        if (server.getTickCount() > movedAt + 300) throw new IllegalStateException("Native ship did not register on the Moon");
        return false;
    }

    private void runPortal(ServerLevel source, ServerLevel moon, VsShipBackend vs, StorageBounds box, Vec3 origin) throws Exception {
        Class<?> manifest = Class.forName("dev.starbridge.transit.HullManifest");
        Method inspect = method(manifest,"inspect",ServerLevel.class,StorageBounds.class);
        Method capture = method(manifest,"capture",ServerLevel.class,StorageBounds.class);
        Method restore = method(manifest,"restoreMovedMachines",ServerLevel.class,ServerLevel.class,Map.class);
        Method seal = method(capture.getReturnType(),"seal");
        Method machines = method(capture.getReturnType(),"machines");
        Method blocks = method(inspect.getReturnType(),"blocks");
        Method digest = method(inspect.getReturnType(),"digest");
        Method find = method(Class.forName("dev.starbridge.transit.BerthPlanner"),"find",ServerLevel.class,ServerLevel.class,long.class,
                StorageBounds.class,Vec3.class,Vec3.class,boolean.class,VsShipBackend.class);
        long start = System.nanoTime();
        berth = timed("planOriginalLunarBerth", () -> (Vec3)find.invoke(null,source,moon,ship,box,origin,new Vec3(0.5,200,0.5),false,vs));
        Object initially = timed("inspectEmptyDestination", () -> inspect.invoke(null,moon,box));
        if ((int)blocks.invoke(initially) != 0) throw new IllegalStateException("Moon storage occupied before transfer");
        Object departure = timed("captureDeparture", () -> capture.invoke(null,source,box));
        Object departureSeal = seal.invoke(departure);
        var inventory = (Map<?,?>) machines.invoke(departure);
        if (inventory.size() != 4530 || (int)blocks.invoke(departureSeal) != 19522) throw new IllegalStateException("Wrong source hull");
        if (!timed("parkShip", () -> vs.setStatic(source,ship,true))) throw new IllegalStateException("Could not park source ship");
        timed("nativePortal", () -> { vs.transfer(source,moon,ship,new VsShipBackend.Vector(berth.x,berth.y,berth.z)); return null; });
        timed("restoreMovedMachines", () -> restore.invoke(null,source,moon,inventory));
        Object after = timed("inspectDestinationAfter", () -> inspect.invoke(null,moon,box));
        Object cleared = timed("inspectSourceAfter", () -> inspect.invoke(null,source,box));
        evidence.put("measuredWindowNs",System.nanoTime()-start);
        evidence.put("departureSeal",digest.invoke(departureSeal));
        evidence.put("arrivalSeal",digest.invoke(after));
        evidence.put("initialDestinationBlocks",blocks.invoke(initially));
        evidence.put("arrivalBlocks",blocks.invoke(after));
        evidence.put("remainingSourceBlocks",blocks.invoke(cleared));
        evidence.put("departureMachines",inventory.size());
        evidence.put("selectedBerth",List.of(berth.x,berth.y,berth.z));
        if (!departureSeal.equals(after) || (int)blocks.invoke(cleared) != 0) throw new IllegalStateException("Cargo differs after native portal");
    }

    private static Method method(Class<?> type,String name,Class<?>... args) throws ReflectiveOperationException {
        var value=type.getDeclaredMethod(name,args);
        value.setAccessible(true);
        return value;
    }
    @FunctionalInterface private interface Stage<T> { T run() throws Exception; }
    private <T> T timed(String name,Stage<T> stage) throws Exception {
        long start=System.nanoTime();
        try { return stage.run(); }
        catch (InvocationTargetException error) { throw new IllegalStateException(name,error.getCause()); }
        finally { times.put(name,System.nanoTime()-start); }
    }
}
