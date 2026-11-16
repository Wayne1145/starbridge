package dev.starbridge.testing;

import dev.starbridge.Starbridge;
import dev.starbridge.benchmark.BenchmarkModule;
import dev.starbridge.benchmark.CompletedBuild;
import dev.starbridge.block.ShipCoreBlockEntity;
import dev.starbridge.content.ContentModule;
import dev.starbridge.content.ExpeditionSavedData;
import dev.starbridge.content.ExpeditionService;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.GameTestServer;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.entity.FurnaceBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

/** Runs only in Forge's disposable GameTest server, never from ordinary gameplay ticks. */
@GameTestHolder(Starbridge.ID)
@PrefixGameTestTemplate(false)
public final class ContentGameTests {
    private ContentGameTests() {}

    @GameTest(template = "empty", batch = "starbridge_content", timeoutTicks = 400)
    public static void moonGeneratesSolidRollingTerrain(GameTestHelper helper) {
        MinecraftServer server = dedicatedTestServer(helper);
        ServerLevel moon = server.getLevel(ContentModule.MOON);
        helper.assertTrue(moon != null, "Survey Moon dimension must be available");
        int lowest = Integer.MAX_VALUE, highest = Integer.MIN_VALUE;
        for (int sample = 0; sample < 8; sample++) {
            int x = 1024 + sample * 32;
            int z = 1024;
            moon.getChunk(x >> 4, z >> 4);
            int surface = moon.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z) - 1;
            helper.assertTrue(surface > 30 && surface < 110,
                    "Moon terrain must generate around its configured elevation; sample " + x + "," + z + " surface=" + surface);
            helper.assertTrue(moon.getBlockState(new BlockPos(x, surface, z)).is(Blocks.CALCITE),
                    "Actual generated moon surface must use calcite");
            helper.assertTrue(moon.getBlockState(new BlockPos(x, surface - 8, z)).is(Blocks.TUFF),
                    "Generated terrain must have a solid tuff subsurface");
            helper.assertTrue(moon.getBlockState(new BlockPos(x, 0, z)).is(Blocks.BEDROCK),
                    "Moon floor must have bedrock");
            lowest = Math.min(lowest, surface);
            highest = Math.max(highest, surface);
        }
        helper.assertTrue(highest > lowest, "Configured relief must produce real height variation, not a flat test plane; min="
                + lowest + ", max=" + highest);
        helper.succeed();
    }

    @GameTest(template = "empty", batch = "starbridge_content", timeoutTicks = 400)
    public static void surveySitesDoNotOverwriteLaterConstruction(GameTestHelper helper) {
        MinecraftServer server = dedicatedTestServer(helper);
        ServerLevel moon = server.getLevel(ContentModule.MOON);
        helper.assertTrue(moon != null, "Survey Moon dimension must be available");
        ExpeditionSavedData original = ExpeditionService.ensureSites(moon);
        CompoundTag initial = original.save(new CompoundTag());
        BlockPos outpost = BlockPos.of(initial.getLong("Outpost"));
        BlockPos vault = BlockPos.of(initial.getLong("Vault"));
        helper.assertTrue(moon.getBlockState(outpost).is(Blocks.BEACON), "Outpost must contain the actual oxygen beacon");
        helper.assertTrue(moon.getBlockState(vault).is(Blocks.LODESTONE), "Vault must contain its interaction terminal");
        BlockPos outpostEdit = outpost.offset(2, 0, 2), vaultEdit = vault.offset(2, 0, 2);
        BlockState oldOutpost = moon.getBlockState(outpostEdit), oldVault = moon.getBlockState(vaultEdit);
        try {
            moon.setBlockAndUpdate(outpostEdit, Blocks.GOLD_BLOCK.defaultBlockState());
            moon.setBlockAndUpdate(vaultEdit, Blocks.DIAMOND_BLOCK.defaultBlockState());
            ExpeditionSavedData again = ExpeditionService.ensureSites(moon);
            helper.assertTrue(again == original, "Site initialization must reuse the world's persisted mission state");
            helper.assertTrue(moon.getBlockState(outpostEdit).is(Blocks.GOLD_BLOCK), "Re-entry must preserve a player's outpost edit");
            helper.assertTrue(moon.getBlockState(vaultEdit).is(Blocks.DIAMOND_BLOCK), "Re-entry must preserve a player's vault edit");
            CompoundTag repeated = again.save(new CompoundTag());
            helper.assertTrue(repeated.getLong("Outpost") == initial.getLong("Outpost")
                    && repeated.getLong("Vault") == initial.getLong("Vault"), "Re-entry must not relocate generated sites");
        } finally {
            moon.setBlockAndUpdate(outpostEdit, oldOutpost);
            moon.setBlockAndUpdate(vaultEdit, oldVault);
        }
        helper.succeed();
    }

    @GameTest(template = "empty", batch = "starbridge_content")
    public static void rewardDeliverySurvivesSerializationWithoutDuplicateClaims(GameTestHelper helper) {
        dedicatedTestServer(helper);
        UUID player = UUID.randomUUID(), boss = UUID.randomUUID();
        CompoundTag completed = new CompoundTag();
        completed.putBoolean("Defeated", true);
        completed.putBoolean("Reinforcements", true);
        completed.putUUID("Custodian", boss);
        ExpeditionSavedData mission = ExpeditionSavedData.load(completed);
        SimpleContainer inventory = new SimpleContainer(1);
        inventory.setItem(0, new ItemStack(Items.COBBLESTONE, 64));
        AtomicInteger deliveries = new AtomicInteger();
        java.util.function.BooleanSupplier deliver = () -> {
            deliveries.incrementAndGet();
            return inventory.addItem(new ItemStack(ContentModule.SURVEY_CORE.get())).isEmpty();
        };
        helper.assertTrue(!mission.deliverReward(player, deliver), "A full real inventory must reject delivery");
        helper.assertTrue(!mission.hasClaimed(player), "A failed delivery must retain reward eligibility");
        helper.assertTrue(inventory.getItem(0).is(Items.COBBLESTONE) && inventory.getItem(0).getCount() == 64,
                "Failed reward delivery must not replace existing inventory contents");
        inventory.removeItemNoUpdate(0);
        helper.assertTrue(mission.deliverReward(player, deliver), "A freed inventory slot must accept the reward");
        helper.assertTrue(inventory.getItem(0).is(ContentModule.SURVEY_CORE.get()) && inventory.getItem(0).getCount() == 1,
                "Successful delivery must produce exactly one survey core");
        CompoundTag saved = mission.save(new CompoundTag());
        ExpeditionSavedData reloaded = ExpeditionSavedData.load(saved);
        helper.assertTrue(reloaded.hasClaimed(player), "The claim must survive NBT reload");
        helper.assertTrue(!reloaded.deliverReward(player, deliver), "Reloading must not allow a second reward");
        helper.assertTrue(deliveries.get() == 2 && inventory.getItem(0).getCount() == 1,
                "Duplicate claims must be rejected before invoking inventory delivery");
        CompoundTag roundTrip = reloaded.save(new CompoundTag());
        helper.assertTrue(roundTrip.getBoolean("Defeated") && roundTrip.getBoolean("Reinforcements")
                && boss.equals(roundTrip.getUUID("Custodian")), "Encounter completion and boss identity must persist");
        ExpeditionSavedData locked = ExpeditionSavedData.load(new CompoundTag());
        helper.assertTrue(!locked.deliverReward(UUID.randomUUID(), deliver) && deliveries.get() == 2,
                "A dormant encounter must never issue a reward");
        helper.succeed();
    }

    @GameTest(template = "empty", batch = "starbridge_benchmark", timeoutTicks = 3600)
    public static void benchmarkBuildsRealShipsWithinTheSharedWriteBudget(GameTestHelper helper) {
        MinecraftServer server = dedicatedTestServer(helper);
        ServerLevel yard = server.getLevel(BenchmarkModule.SHIPYARD);
        helper.assertTrue(yard != null, "Shipyard dimension must be available");
        UUID firstOwner = UUID.randomUUID(), secondOwner = UUID.randomUUID();
        BenchmarkModule.QueuedBuild first = BenchmarkModule.queueFixture(server, firstOwner, 100, 24);
        BenchmarkModule.QueuedBuild second = BenchmarkModule.queueFixture(server, secondOwner, 100, 0);
        helper.assertTrue(first.origin().distSqr(second.origin()) >= 1024.0 * 1024.0,
                "Concurrent owners must receive separate reserved plots");
        int[] previousTotal = {0};
        boolean[] finished = {false};
        helper.onEachTick(() -> {
            if (finished[0]) return;
            int total = placed(firstOwner) + placed(secondOwner);
            helper.assertTrue(total >= previousTotal[0], "Build progress must not disappear or move backwards");
            helper.assertTrue(total - previousTotal[0] <= 256, "All simultaneous jobs must share the 256-write tick limit");
            previousTotal[0] = total;
            var firstDone = BenchmarkModule.getLastBuild(firstOwner);
            var secondDone = BenchmarkModule.getLastBuild(secondOwner);
            if (firstDone.isEmpty() || secondDone.isEmpty()) {
                helper.assertTrue(firstDone.isPresent() || BenchmarkModule.progress(firstOwner).isPresent(), "First build unexpectedly aborted");
                helper.assertTrue(secondDone.isPresent() || BenchmarkModule.progress(secondOwner).isPresent(), "Second build unexpectedly aborted");
                return;
            }
            CompletedBuild build = firstDone.get();
            helper.assertTrue(build.length() == 100 && build.blockCount() > 10_000, "Generator must place a real hundred-block hull");
            helper.assertTrue(build.positions().size() == build.blockCount(), "Assembly snapshot must contain the actual placed blocks");
            int chests = 0, furnaces = 0, blockEntities = 0;
            for (BlockPos pos : build.positions()) {
                helper.assertTrue(!yard.getBlockState(pos).isAir(), "Completed snapshots must not include unplaced air cells");
                BlockEntity entity = yard.getBlockEntity(pos);
                if (entity != null) blockEntities++;
                if (entity instanceof ChestBlockEntity chest) {
                    chests++;
                    helper.assertTrue(chest.getItem(0).is(Items.COBBLESTONE), "Benchmark chest inventory must be populated");
                }
                if (entity instanceof FurnaceBlockEntity furnace) {
                    furnaces++;
                    int remainingRaw = furnace.getItem(0).is(Items.RAW_IRON) ? furnace.getItem(0).getCount() : 0;
                    int output = furnace.getItem(2).is(Items.IRON_INGOT) ? furnace.getItem(2).getCount() : 0;
                    helper.assertTrue(remainingRaw + output == 64, "A real furnace must retain or process its supplied input");
                }
            }
            helper.assertTrue(chests == 12 && furnaces == 12 && blockEntities == 25,
                    "24 requested machines plus one core must exist as real block entities");
            helper.assertTrue(build.blockEntityCount() == blockEntities, "Reported block entities must match the world");
            BlockEntity core = yard.getBlockEntity(build.core());
            helper.assertTrue(core instanceof ShipCoreBlockEntity && firstOwner.equals(((ShipCoreBlockEntity) core).owner()),
                    "Generated flight core must belong to the requesting owner");
            helper.assertTrue(secondDone.get().blockEntityCount() == 1, "Zero-machine fixture must contain only its core block entity");
            finished[0] = true;
            helper.succeed();
        });
    }

    @GameTest(template = "empty", batch = "starbridge_benchmark", timeoutTicks = 2400)
    public static void benchmarkPreflightPreservesAnExistingBlock(GameTestHelper helper) {
        MinecraftServer server = dedicatedTestServer(helper);
        ServerLevel yard = server.getLevel(BenchmarkModule.SHIPYARD);
        helper.assertTrue(yard != null, "Shipyard dimension must be available");
        UUID owner = UUID.randomUUID();
        BenchmarkModule.QueuedBuild queued = BenchmarkModule.queueFixture(server, owner, 100, 0);
        yard.setBlockAndUpdate(queued.core(), Blocks.GOLD_BLOCK.defaultBlockState());
        helper.onEachTick(() -> {
            helper.assertTrue(yard.getBlockState(queued.core()).is(Blocks.GOLD_BLOCK), "Generator must never replace the obstruction");
            var progress = BenchmarkModule.progress(owner);
            if (progress.isPresent()) {
                helper.assertTrue(progress.get().placedBlocks() == 0, "No placement may begin before the full preflight passes");
                return;
            }
            helper.assertTrue(BenchmarkModule.getLastBuild(owner).isEmpty(), "An obstructed plot must not produce a completed assembly snapshot");
            BlockPos hullStart = new BlockPos(queued.origin().getX(), queued.origin().getY(), queued.core().getZ());
            helper.assertTrue(yard.getBlockState(hullStart).isAir(), "Preflight failure must leave the unbuilt hull untouched");
            helper.succeed();
        });
    }

    private static int placed(UUID owner) {
        return BenchmarkModule.getLastBuild(owner).map(CompletedBuild::blockCount)
                .orElseGet(() -> BenchmarkModule.progress(owner).map(BenchmarkModule.BuildProgress::placedBlocks).orElse(0));
    }

    private static MinecraftServer dedicatedTestServer(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        helper.assertTrue(server instanceof GameTestServer,
                "These destructive fixture tests are restricted to the disposable runGameTestServer, not ordinary gameplay worlds");
        return server;
    }
}
