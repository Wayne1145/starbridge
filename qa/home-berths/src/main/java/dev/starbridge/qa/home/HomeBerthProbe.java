package dev.starbridge.qa.home;

import dev.starbridge.orbit.OrbitWire;

import com.google.gson.GsonBuilder;
import dev.starbridge.compat.vs.VsShipBackend;
import dev.starbridge.voyage.Voyages;
import net.minecraft.core.BlockPos;
import net.minecraft.server.dedicated.DedicatedServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.fml.common.Mod;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/** Drives production clearance and exact return placement on an isolated Forge dedicated server. */
@Mod("starbridgeportqa")
public final class HomeBerthProbe {
    private static final UUID ID = UUID.fromString("34a6d3d7-fbd4-473d-8a35-56a048579226");
    private static final Vec3 PORT = new Vec3(-40.875, 198.25, 59.625);
    private static final int EAST = 63, NORTH = -38;
    private final VsShipBackend vs = new VsShipBackend();
    private final Map<String,Object> proof = new LinkedHashMap<>();
    private Survey survey;
    private WideTransferCheck transfer;
    private final BlockPos cargo = new BlockPos(40, 190, 41);
    private long shipId;
    private BlockPos storedCargo;
    private int step, last;
    private boolean complete;

    public HomeBerthProbe() { MinecraftForge.EVENT_BUS.addListener(this::tick); }

    private void tick(TickEvent.ServerTickEvent e) {
        if (e.phase != TickEvent.Phase.END || complete || !(e.getServer() instanceof DedicatedServer server)
                || server.getTickCount() < 50) return;
        boolean wideTrial = Files.isRegularFile(WideCaptureCheck.MARKER);
        boolean transferTrial = Files.isRegularFile(WideTransferCheck.MARKER);
        if (!wideTrial && !transferTrial && !Files.isRegularFile(Path.of("starbridge-home-berth.fixture"))) return;
        try {
            if (transferTrial) {
                if (transfer == null) transfer = new WideTransferCheck();
                if (transfer.tick(server, vs, proof)) finish(server, true);
                return;
            }
            if (wideTrial) {
                proof.put("wideCapture", WideCaptureCheck.run(server.overworld(), vs));
                finish(server, true);
                return;
            }
            if (server.getTickCount() > 1800) throw new IllegalStateException("Timed out at phase " + step);
            ServerLevel home = server.overworld();
            ServerLevel away = server.getLevel(Level.NETHER);
            if (step == 0) {
                require(away != null && vs.probe().available(), "VS and test source dimension");
                proof.put("departureCargo", ManifestCheck.run(home, away));
                away.getChunk(cargo.getX() >> 4, cargo.getZ() >> 4);
                away.setBlockAndUpdate(cargo, Blocks.DIAMOND_BLOCK.defaultBlockState());
                away.setBlockAndUpdate(cargo.east(), Blocks.IRON_BLOCK.defaultBlockState());
                away.setBlockAndUpdate(cargo.south(), Blocks.IRON_BLOCK.defaultBlockState());
                shipId = vs.assemble(away, java.util.Set.of(cargo,cargo.east(),cargo.south()), 1.0, true);
                proof.put("nativeShip", shipId);
                step++; last = server.getTickCount();
            } else if (step == 1) {
                if (server.getTickCount() < last + 10) return;
                var bounds = vs.storageBoundsById(away, shipId).orElse(null);
                if (bounds == null) return;
                for (var block : BlockPos.betweenClosed(bounds.minX(), bounds.minY(), bounds.minZ(), bounds.maxX(), bounds.maxY(), bounds.maxZ())) {
                    if (away.getBlockState(block).is(Blocks.DIAMOND_BLOCK)) storedCargo = block.immutable();
                }
                require(storedCargo != null, "Real cargo in native storage");
                var occupied = BlockPos.containing(PORT);
                home.setBlockAndUpdate(occupied, Blocks.OBSIDIAN.defaultBlockState());
                survey = new Survey(ID, 0, 0, bounds, home, PORT, server.getTickCount());
                step++;
            } else if (step == 2) {
                survey.work();
                if (!survey.finished()) return;
                var reply = survey.packet();
                require(reply.clearance() == OrbitWire.Clearance.OCCUPIED && reply.markX() == (int) Math.floor(PORT.x)
                        && reply.markZ() == (int) Math.floor(PORT.z), "Original obstruction appears in the map reply");
                proof.put("originalObstruction", java.util.List.of(reply.markX(),reply.markZ()));
                var hull = survey.hull;
                survey.release();
                survey = new Survey(ID, EAST, NORTH, hull, home, PORT, server.getTickCount());
                step++;
            } else if (step == 3 || step == 4) {
                survey.work();
                if (!survey.finished()) return;
                require(survey.packet().clearance() == OrbitWire.Clearance.CLEAR, "Alternate port passes the survey");
                var reply = survey.packet();
                require(reply.x() == EAST && reply.z() == NORTH && reply.centerX() == (int)Math.floor(PORT.x) + EAST
                        && reply.centerZ() == (int)Math.floor(PORT.z) + NORTH, "Map reply uses the selected berth");
                if (step++ == 3) { survey.reset(true); return; }
                Vec3 anchor = survey.anchor();
                require(anchor.equals(PORT.add(EAST,0,NORTH)), "Original altitude and sub-block mass center");
                proof.put("selectedAnchor", java.util.List.of(anchor.x,anchor.y,anchor.z));
                var position = Survey.place(away, home, shipId, survey.hull, anchor, vs);
                require(position.equals(anchor), "Physical return uses the surveyed position");
                last = server.getTickCount();
            } else if (step == 5 && server.getTickCount() >= last + 15) {
                var pose = vs.inspectById(home,shipId).orElse(null);
                if (pose == null || !home.getBlockState(storedCargo).is(Blocks.DIAMOND_BLOCK)) return;
                var p=pose.worldPosition();
                var returned = new Vec3(p.x(),p.y(),p.z());
                require(returned.distanceTo(survey.anchor()) < .02, "Native ship position matches the selected site");
                require(home.getBlockState(BlockPos.containing(PORT)).is(Blocks.OBSIDIAN), "Obstruction preserved");
                require(!away.getBlockState(storedCargo).is(Blocks.DIAMOND_BLOCK), "Cargo moved from source storage");
                proof.put("observedNativePosition", java.util.List.of(p.x(),p.y(),p.z()));
                proof.put("cargo", "minecraft:diamond_block");
                proof.put("storage", java.util.List.of(storedCargo.getX(),storedCargo.getY(),storedCargo.getZ()));
                proof.put("sourceCargoRemoved", true);
                proof.put("originalPortIntact", true);
                finish(server, true);
            }
        } catch (Exception | LinkageError failure) {
            proof.put("error", failure.toString());
            if (failure.getCause() != null) proof.put("cause", failure.getCause().toString());
            finish(server, false);
        }
    }

    private void finish(DedicatedServer server, boolean passed) {
        complete = true;
        if (survey != null) { try { survey.release(); } catch (ReflectiveOperationException failure) { throw new IllegalStateException(failure); } }
        proof.put("players",server.getPlayerCount());
        proof.put("ticks",server.getTickCount());
        proof.put("pass",passed);
        try { Files.writeString(Path.of("home-berth-result.json"),new GsonBuilder().setPrettyPrinting().create().toJson(proof)); }
        catch (Exception failure) { throw new IllegalStateException(failure); }
        finally { server.halt(false); }
    }

    private static void require(boolean passed,String name) { if (!passed) throw new IllegalStateException(name); }
}
