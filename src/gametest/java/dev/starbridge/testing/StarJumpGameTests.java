package dev.starbridge.testing;

import dev.starbridge.Starbridge;
import dev.starbridge.block.ShipCoreBlockEntity;
import dev.starbridge.compat.vs.VsShipBackend;
import dev.starbridge.content.ContentModule;
import dev.starbridge.server.CoreRegistry;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.GameTestServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.util.Comparator;
import java.util.HashSet;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Exercises the real Valkyrien Skies cross-dimension teleport used by star jumps: a parked ship
 * is assembled in the Overworld, teleported to Survey Moon, and its core must reload there with
 * the same identity, owner and upgrades. No physics motion is required, so no observer is needed.
 */
@GameTestHolder(Starbridge.ID)
@PrefixGameTestTemplate(false)
public final class StarJumpGameTests {
    private static final TicketType<ChunkPos> TICKET = TicketType.create("starbridge_jump_test",
            Comparator.comparingLong((ChunkPos pos) -> pos.toLong()));

    @GameTest(template = "empty", batch = "starbridge_vs_jump", timeoutTicks = 1200)
    public static void parkedShipTeleportsToMoonWithItsCore(GameTestHelper helper) {
        helper.assertTrue(helper.getLevel().getServer() instanceof GameTestServer, "Run only in the disposable GameTest server");
        if (!ModList.get().isLoaded("valkyrienskies")) {
            Starbridge.LOGGER.info("STARBRIDGE_TEST_SKIP parkedShipTeleportsToMoonWithItsCore: optional VS runtime absent");
            helper.succeed();
            return;
        }
        ServerLevel overworld = helper.getLevel();
        ServerLevel moon = overworld.getServer().getLevel(ContentModule.MOON);
        helper.assertTrue(moon != null, "Survey Moon must be loaded");
        VsShipBackend physics = new VsShipBackend();
        helper.assertTrue(physics.probe().available(), "Real Valkyrien Skies backend must resolve: " + physics.probe().detail());

        BlockPos origin = new BlockPos(320, 200, 320);
        ChunkPos fixtureChunk = new ChunkPos(origin);
        overworld.getChunkSource().addRegionTicket(TICKET, fixtureChunk, 3, fixtureChunk);
        ChunkPos arrivalChunk = new ChunkPos(new BlockPos(-48, 170, 0));
        moon.getChunkSource().addRegionTicket(TICKET, arrivalChunk, 3, arrivalChunk);

        HashSet<BlockPos> positions = new HashSet<>();
        for (int x = 0; x < 5; x++) for (int z = 0; z < 5; z++) {
            BlockPos pos = origin.offset(x, 0, z);
            helper.assertTrue(overworld.getBlockState(pos).isAir(), "Fixture must not overwrite existing blocks");
            overworld.setBlockAndUpdate(pos, Blocks.IRON_BLOCK.defaultBlockState());
            positions.add(pos);
        }
        BlockPos corePos = origin.offset(2, 1, 2);
        overworld.setBlockAndUpdate(corePos, Starbridge.SHIP_CORE.get().defaultBlockState());
        positions.add(corePos);
        ShipCoreBlockEntity sourceCore = (ShipCoreBlockEntity) overworld.getBlockEntity(corePos);
        UUID owner = UUID.randomUUID();
        sourceCore.setOwner(owner);
        sourceCore.installUpgrade(dev.starbridge.block.ShipUpgrade.CAPACITOR);
        sourceCore.charge(500_000);
        UUID coreId = sourceCore.id();
        long shipId = physics.assemble(overworld, positions, 1, true);
        String moonId = physics.vsDimensionId(moon);
        AtomicBoolean teleported = new AtomicBoolean();
        AtomicInteger polls = new AtomicInteger();
        HashSet<ChunkPos> storageTickets = new HashSet<>();

        java.util.concurrent.atomic.AtomicLong nextWallTick = new java.util.concurrent.atomic.AtomicLong(System.nanoTime());
        helper.succeedWhen(() -> {
            // GameTestServer skips the normal 50 ms tick wait; VS loads ships on wall-clock time.
            long wait = nextWallTick.get() - System.nanoTime();
            if (wait > 0) java.util.concurrent.locks.LockSupport.parkNanos(Math.min(wait, 50_000_000L));
            nextWallTick.set(System.nanoTime() + 50_000_000L);
            if (!teleported.get()) {
                var snapshot = physics.inspectById(overworld, shipId).orElse(null);
                helper.assertTrue(snapshot != null, "Assembled ship must load in the Overworld first");
                var bounds = physics.worldBounds(overworld, shipId).orElse(null);
                helper.assertTrue(bounds != null && bounds.contains(origin.getX() + 2.5, origin.getY() + 1.5, origin.getZ() + 2.5, 0.5),
                        "World bounds must contain the hull: " + bounds);
                helper.assertTrue(physics.teleportShip(overworld, shipId, moon, -48, 170, 0), "VS must accept the cross-dimension teleport");
                teleported.set(true);
                Starbridge.LOGGER.info("STARBRIDGE_JUMP_TELEPORT_SENT ship={} bounds={}", shipId, bounds);
            }
            String dimension = physics.shipDimension(overworld, shipId).orElse("missing");
            if (polls.getAndIncrement() % 40 == 0)
                Starbridge.LOGGER.info("STARBRIDGE_JUMP_POLL ship={} dimension={} core={}", shipId, dimension,
                        CoreRegistry.find(coreId).map(core -> core.getLevel() == null ? "no level" : core.getLevel().dimension().location() + "@" + core.getBlockPos()).orElse("absent"));
            helper.assertTrue(moonId.equals(dimension), "Ship storage must move to Survey Moon, currently " + dimension);
            // In play the carried passengers load the ship; this player-less test loads its storage chunks.
            physics.storageBoundsById(moon, shipId).ifPresent(storage -> {
                for (int x = storage.minX() >> 4; x <= storage.maxX() >> 4; x++)
                    for (int z = storage.minZ() >> 4; z <= storage.maxZ() >> 4; z++) {
                        ChunkPos chunk = new ChunkPos(x, z);
                        if (storageTickets.add(chunk)) moon.getChunkSource().addRegionTicket(TICKET, chunk, 2, chunk);
                    }
            });
            if (polls.get() % 40 == 1) {
                var storage = physics.storageBoundsById(moon, shipId).orElse(null);
                StringBuilder found = new StringBuilder();
                if (storage != null) {
                    for (int x = storage.minX(); x <= storage.maxX(); x++)
                        for (int y = storage.minY(); y <= storage.maxY(); y++)
                            for (int z = storage.minZ(); z <= storage.maxZ(); z++) {
                                BlockPos pos = new BlockPos(x, y, z);
                                for (ServerLevel candidate : new ServerLevel[]{moon, overworld}) {
                                    if (!candidate.hasChunkAt(pos)) continue;
                                    if (candidate.getBlockState(pos).is(Starbridge.SHIP_CORE.get()))
                                        found.append(candidate.dimension().location()).append('@').append(pos)
                                                .append(" be=").append(candidate.getBlockEntity(pos)).append(' ');
                                }
                            }
                }
                Starbridge.LOGGER.info("STARBRIDGE_JUMP_DIAG storage={} moonLoaded={} cores={} tickets={}", storage,
                        storage == null ? "?" : moon.hasChunkAt(new BlockPos(storage.minX(), storage.minY(), storage.minZ())),
                        found, storageTickets.size());
            }
            ShipCoreBlockEntity core = CoreRegistry.find(coreId).orElse(null);
            helper.assertTrue(core != null && core.getLevel() == moon, "The core must reload on Survey Moon with its stable ID");
            helper.assertTrue(owner.equals(core.owner()), "The jump must preserve the owner");
            helper.assertTrue(core.upgradeLevel(dev.starbridge.block.ShipUpgrade.CAPACITOR) == 1 && core.energy() >= 500_000,
                    "The jump must preserve modules and energy");
            var arrived = physics.inspectById(moon, shipId).orElse(null);
            helper.assertTrue(arrived != null && Math.abs(arrived.worldPosition().y() - 170) < 4,
                    "Ship must arrive near Y 170 on the moon: " + arrived);
            overworld.getChunkSource().removeRegionTicket(TICKET, fixtureChunk, 3, fixtureChunk);
            moon.getChunkSource().removeRegionTicket(TICKET, arrivalChunk, 3, arrivalChunk);
            for (ChunkPos chunk : storageTickets) moon.getChunkSource().removeRegionTicket(TICKET, chunk, 2, chunk);
            physics.close();
            Starbridge.LOGGER.info("STARBRIDGE_JUMP_PASS ship={} position={}", shipId, arrived.worldPosition());
        });
    }
}
