package dev.starbridge.testing;

import dev.starbridge.Starbridge;
import dev.starbridge.block.ShipCoreBlockEntity;
import dev.starbridge.compat.vs.VsShipBackend;
import dev.starbridge.flight.Vec3d;
import dev.starbridge.server.CoreRegistry;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.GameTestServer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.level.ChunkPos;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.util.HashSet;
import java.util.Comparator;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.LockSupport;

/** Real native-physics test; explicitly reports a skip when the optional runtime is absent. */
@GameTestHolder(Starbridge.ID)
@PrefixGameTestTemplate(false)
public final class ShipIntegrationGameTests {
    private static final TicketType<ChunkPos> FIXTURE_TICKET = TicketType.create("starbridge_physics_test",
            Comparator.comparingLong((ChunkPos pos) -> pos.toLong()));
    @GameTest(template = "empty", batch = "starbridge_vs", timeoutTicks = 1200)
    public static void assembledCoreMovesRealShip(GameTestHelper helper) {
        helper.assertTrue(helper.getLevel().getServer() instanceof GameTestServer, "Run only in the disposable GameTest server");
        if (!ModList.get().isLoaded("valkyrienskies")) {
            Starbridge.LOGGER.info("STARBRIDGE_TEST_SKIP assembledCoreMovesRealShip: optional VS runtime absent");
            helper.succeed();
            return;
        }
        if (!Boolean.getBoolean("starbridge.nativeGameTest")) {
            Starbridge.LOGGER.info("STARBRIDGE_TEST_SKIP assembledCoreMovesRealShip: native flight is verified by the ordinary dedicated-server observer test; use -PnativeGameTest to reproduce the unresolved no-player physics behavior");
            helper.succeed();
            return;
        }
        var level = helper.getLevel();
        VsShipBackend physics = new VsShipBackend();
        var probe = physics.probe();
        helper.assertTrue(probe.available(), "Real Valkyrien Skies backend must resolve: " + probe.detail());
        // Keep the fixture inside the normal spawn loading neighborhood, while
        // well above GameTest structures. A ship may be fully loaded while its
        // surrounding terrain collision chunks are not; native physics can
        // then legitimately hold it still even though force callbacks run.
        BlockPos origin = new BlockPos(128, 220, 128);
        ChunkPos fixtureChunk = new ChunkPos(origin);
        HashSet<ChunkPos> storageTickets = new HashSet<>();
        level.getChunkSource().addRegionTicket(FIXTURE_TICKET, fixtureChunk, 9, fixtureChunk);
        Runnable releaseTickets = () -> {
            level.getChunkSource().removeRegionTicket(FIXTURE_TICKET, fixtureChunk, 9, fixtureChunk);
            for (ChunkPos chunk : storageTickets) level.getChunkSource().removeRegionTicket(FIXTURE_TICKET, chunk, 2, chunk);
            physics.close();
        };
        // On failure the disposable GameTest server shuts down and discards its
        // ephemeral tickets. Do not unload the ship just before timeout: doing
        // so would replace the actual last failure with a missing-core error.
        HashSet<BlockPos> positions = new HashSet<>();
        for (int x = 0; x < 7; x++) for (int z = 0; z < 7; z++) {
            BlockPos pos = origin.offset(x, 0, z);
            helper.assertTrue(level.getBlockState(pos).isAir(), "Physics fixture must not overwrite existing blocks");
            level.setBlockAndUpdate(pos, Blocks.IRON_BLOCK.defaultBlockState());
            positions.add(pos);
        }
        BlockPos corePosition = origin.offset(3, 1, 3);
        helper.assertTrue(level.getBlockState(corePosition).isAir(), "Physics core fixture must not overwrite an existing block");
        level.setBlockAndUpdate(corePosition, Starbridge.SHIP_CORE.get().defaultBlockState());
        positions.add(corePosition);
        ShipCoreBlockEntity sourceCore = (ShipCoreBlockEntity) level.getBlockEntity(corePosition);
        UUID owner = UUID.randomUUID();
        sourceCore.setOwner(owner);
        for (int i = 0; i < 10; i++) sourceCore.addFuel(new ItemStack(Items.COAL_BLOCK), false);
        UUID coreId = sourceCore.id();
        long shipId = physics.assemble(level, positions, 1);
        AtomicBoolean started = new AtomicBoolean();
        AtomicReference<Vec3d> initial = new AtomicReference<>();
        AtomicReference<Double> baselineHeight = new AtomicReference<>();
        AtomicBoolean gravityObserved = new AtomicBoolean();
        AtomicLong nextWallTick = new AtomicLong(System.nanoTime());
        AtomicInteger polls = new AtomicInteger();
        Starbridge.LOGGER.info("STARBRIDGE_PHYSICS_START ship={} sourceCore={} sourceRemoved={} coreNBT={}",
                shipId, coreId, sourceCore.isRemoved(), sourceCore.saveWithFullMetadata());
        helper.succeedWhen(() -> {
            // GameTestServer removes Minecraft's normal 50ms wait. VS has a
            // real physics thread, so this one native-physics test explicitly
            // preserves a normal game-tick cadence instead of consuming its
            // entire timeout before physics has had time to advance.
            long now = System.nanoTime();
            long wait = nextWallTick.get() - now;
            if (wait > 0) LockSupport.parkNanos(Math.min(wait, 50_000_000L));
            nextWallTick.set(System.nanoTime() + 50_000_000L);
            var storage = physics.storageBoundsById(level, shipId).orElse(null);
            helper.assertTrue(storage != null, "Assembled ship must expose storage voxel bounds");
            helper.assertTrue((long) storage.maxX() - storage.minX() <= 16
                    && (long) storage.maxZ() - storage.minZ() <= 16, "Tiny fixture must not claim an unexpectedly large storage region");
            for (int x = storage.minX() >> 4; x <= (storage.maxX() >> 4); x++) {
                for (int z = storage.minZ() >> 4; z <= (storage.maxZ() >> 4); z++) {
                    ChunkPos chunk = new ChunkPos(x, z);
                    if (storageTickets.add(chunk)) level.getChunkSource().addRegionTicket(FIXTURE_TICKET, chunk, 2, chunk);
                }
            }
            var snapshot = physics.inspectById(level, shipId).orElse(null);
            if (polls.getAndIncrement() % 100 == 0) {
                var foundCore = CoreRegistry.find(coreId).orElse(null);
                Starbridge.LOGGER.info("STARBRIDGE_PHYSICS_DIAG ship={} poll={} started={} storage={} snapshot={} core={} sourceRemoved={} native={} pipeline={} registry={}",
                        shipId, polls.get(), started.get(), storage, snapshot,
                        foundCore == null ? "absent" : foundCore.status(), sourceCore.isRemoved(), VsShipBackend.diagnostics(),
                        physics.runtimeDiagnostics(level.getServer(), level, shipId),
                        CoreRegistry.all().stream().map(entry -> entry.id() + "@" + entry.getBlockPos()
                                + " removed=" + entry.isRemoved() + " loaded=" + entry.loadedForControl()).toList());
            }
            helper.assertTrue(snapshot != null, "Assembled body must enter the loaded ship collection");
            ShipCoreBlockEntity core = CoreRegistry.find(coreId).orElse(null);
            helper.assertTrue(core != null && core != sourceCore && !core.isRemoved(), "Moved core must load with its stable ID");
            helper.assertTrue(owner.equals(core.owner()), "Assembly must preserve the core owner");
            if (baselineHeight.get() == null) baselineHeight.set(snapshot.worldPosition().y());
            int loadedTerrain = 0;
            for (int x = fixtureChunk.x - 8; x <= fixtureChunk.x + 8; x++) {
                for (int z = fixtureChunk.z - 8; z <= fixtureChunk.z + 8; z++) {
                    if (level.getChunkSource().getChunkNow(x, z) != null) loadedTerrain++;
                }
            }
            helper.assertTrue(loadedTerrain == 289, "Native physics fixture needs its 17 x 17 terrain neighborhood loaded ("
                    + loadedTerrain + "/289)");
            if (!gravityObserved.get()) {
                helper.assertTrue(baselineHeight.get() - snapshot.worldPosition().y() >= 0.5,
                        "Unpowered native ship must first fall at least half a block under gravity before flight control is tested");
                gravityObserved.set(true);
                Starbridge.LOGGER.info("STARBRIDGE_PHYSICS_GRAVITY_PASS ship={} drop={} velocity={}", shipId,
                        baselineHeight.get() - snapshot.worldPosition().y(), snapshot.velocity());
            }
            if (!started.get()) {
                initial.set(new Vec3d(snapshot.worldPosition().x(), snapshot.worldPosition().y(), snapshot.worldPosition().z()));
                core.navigate(initial.get().add(new Vec3d(8, 0, 0)));
                started.set(true);
            }
            helper.assertTrue(String.valueOf(core.status().get("lastError")).isEmpty(), "Drive error: " + core.status().get("lastError"));
            double travelled = snapshot.worldPosition().x() - initial.get().x();
            helper.assertTrue(travelled >= 5, "Physical ship should travel at least five blocks toward its waypoint");
            helper.assertTrue(Math.abs(snapshot.worldPosition().y() - initial.get().y()) < 8, "Gravity compensation must keep the ship near its initial altitude");
            helper.assertTrue(core.energy() < 2_000_000 && core.energy() > 0, "Real movement must consume finite energy");
            core.hold();
            releaseTickets.run();
            Starbridge.LOGGER.info("STARBRIDGE_PHYSICS_PASS ship={} travelled={} energy={} position={}", shipId, travelled, core.energy(), snapshot.worldPosition());
        });
    }
}
