package dev.starbridge.benchmark;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import dev.starbridge.Starbridge;
import dev.starbridge.block.ShipCoreBlockEntity;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LadderBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkStatus;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.server.ServerLifecycleHooks;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/** Bounded, opt-in generation in a dedicated void dimension. Never deletes or replaces existing blocks. */
public final class BenchmarkModule {
    public static final ResourceKey<Level> SHIPYARD = ResourceKey.create(
            Registries.DIMENSION, new ResourceLocation(Starbridge.ID, "shipyard"));
    private static final int MAX_PLACEMENTS_PER_TICK = 256;
    private static final long TICK_BUDGET_NANOS = 3_000_000L;
    private static final TicketType<ChunkPos> GENERATION_TICKET = TicketType.create(
            "starbridge_benchmark", Comparator.comparingLong((ChunkPos pos) -> pos.toLong()));
    private static BenchmarkModule instance;
    private final Map<UUID, BuildJob> jobs = new LinkedHashMap<>();
    private int roundRobin;
    private double lastGeneratorMillis;
    private double peakGeneratorMillis;
    private long overruns;

    private BenchmarkModule() {}

    public static void register() {
        if (instance == null) {
            instance = new BenchmarkModule();
            MinecraftForge.EVENT_BUS.register(instance);
        }
    }

    public record QueuedBuild(UUID owner, BlockPos origin, BlockPos core) {}
    public record BuildProgress(String phase, int scannedCells, int volume, int placedBlocks) {}

    /** Trusted server-thread API; command/network callers remain responsible for operator authorization. */
    public static QueuedBuild queueFixture(MinecraftServer server, UUID owner, int length, int machines) {
        if (!server.isSameThread()) throw new IllegalStateException("Benchmark requests must run on the server thread");
        if (instance == null) throw new IllegalStateException("Benchmark module is not registered");
        if (instance.jobs.containsKey(owner)) throw new IllegalStateException("A benchmark is already active for this owner");
        ServerLevel yard = server.getLevel(SHIPYARD);
        if (yard == null) throw new IllegalStateException("Shipyard dimension is unavailable");
        ShipBlueprint plan = ShipBlueprint.ship(length, machines);
        BlockPos origin = BenchmarkSavedData.get(server).allocate();
        BuildJob job = new BuildJob(owner, yard, origin, plan, SHIPYARD);
        instance.jobs.put(owner, job);
        return new QueuedBuild(owner, origin, job.worldPos(plan.coreIndex));
    }

    /** Server-thread progress snapshot; no world or chunk is loaded by this query. */
    public static Optional<BuildProgress> progress(UUID owner) {
        BuildJob job = instance == null ? null : instance.jobs.get(owner);
        return job == null ? Optional.empty() : Optional.of(new BuildProgress(job.phase.name(), job.cursor,
                job.plan.volume(), job.positions.size()));
    }

    public static Optional<CompletedBuild> getLastBuild(UUID owner) {
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        return server == null ? Optional.empty() : Optional.ofNullable(BenchmarkSavedData.get(server).completed.get(owner));
    }

    /** Call after successful assembly if the integration no longer needs the source-coordinate snapshot. */
    public static void forgetLastBuild(UUID owner) {
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server == null) return;
        BenchmarkSavedData data = BenchmarkSavedData.get(server);
        if (data.completed.remove(owner) != null) data.setDirty();
    }

    @SubscribeEvent
    public void commands(RegisterCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("starbridge_benchmark")
                .requires(source -> source.hasPermission(2))
                .then(Commands.literal("enter").executes(context -> enter(context.getSource())))
                .then(Commands.literal("return").executes(context -> returnFromYard(context.getSource())))
                .then(Commands.literal("status").executes(context -> status(context.getSource())))
                .then(Commands.literal("cancel").executes(context -> cancel(context.getSource())))
                .then(Commands.literal("create")
                        .then(Commands.argument("length", IntegerArgumentType.integer(100, 300))
                                .executes(context -> create(context.getSource(), IntegerArgumentType.getInteger(context, "length"), 32))
                                .then(Commands.argument("machines", IntegerArgumentType.integer(0, 200))
                                        .executes(context -> create(context.getSource(),
                                                IntegerArgumentType.getInteger(context, "length"),
                                                IntegerArgumentType.getInteger(context, "machines")))))));
    }

    private int enter(CommandSourceStack source) throws CommandSyntaxException {
        ServerPlayer player = source.getPlayerOrException();
        if (jobs.containsKey(player.getUUID())) return fail(source, "A build is already queued; use status or cancel first.");
        ServerLevel yard = source.getServer().getLevel(SHIPYARD);
        if (yard == null) return fail(source, "Shipyard dimension is unavailable. Check datapack loading errors.");
        BenchmarkSavedData data = BenchmarkSavedData.get(source.getServer());
        if (!player.level().dimension().equals(SHIPYARD)) {
            data.returns.put(player.getUUID(), BenchmarkSavedData.ReturnPoint.of(player));
            data.setDirty();
        }
        BlockPos origin = data.allocate();
        BuildJob job = new BuildJob(player.getUUID(), yard, origin, ShipBlueprint.platform(), player.level().dimension());
        jobs.put(player.getUUID(), job);
        source.sendSuccess(() -> Component.literal("Preparing an isolated shipyard arrival platform; teleport follows completion."
                + " This is an operator-only development dimension."), false);
        return 1;
    }

    private int create(CommandSourceStack source, int length, int machines) throws CommandSyntaxException {
        ServerPlayer player = source.getPlayerOrException();
        if (!player.level().dimension().equals(SHIPYARD)) return fail(source, "Run enter first. Benchmarks are allowed only in starbridge:shipyard.");
        if (jobs.containsKey(player.getUUID())) return fail(source, "A build is already queued; use status or cancel first.");
        if (length != 100 && length != 200 && length != 300) return fail(source, "Choose an exact benchmark length: 100, 200 or 300.");
        QueuedBuild queued = queueFixture(source.getServer(), player.getUUID(), length, machines);
        BuildJob job = jobs.get(player.getUUID());
        ShipBlueprint plan = job.plan;
        BlockPos origin = queued.origin();
        source.sendSuccess(() -> Component.literal("Queued " + length + " x " + plan.width + " x " + plan.height
                + " benchmark, " + machines + " machines, at " + coordinates(origin)
                + ". Preflight checks every planned block for air before placement. You will move to its bridge on completion."), false);
        return 1;
    }

    private int returnFromYard(CommandSourceStack source) throws CommandSyntaxException {
        ServerPlayer player = source.getPlayerOrException();
        if (!player.level().dimension().equals(SHIPYARD)) return fail(source, "You are not in the shipyard.");
        BenchmarkSavedData data = BenchmarkSavedData.get(source.getServer());
        BenchmarkSavedData.ReturnPoint point = data.returns.get(player.getUUID());
        if (point == null) return fail(source, "No development return point is recorded; use an operator teleport.");
        ResourceLocation id = ResourceLocation.tryParse(point.dimension());
        ServerLevel destination = id == null ? null : source.getServer().getLevel(ResourceKey.create(Registries.DIMENSION, id));
        if (destination == null) return fail(source, "The recorded dimension is unavailable; no teleport performed.");
        BlockPos feet = BlockPos.containing(point.x(), point.y(), point.z());
        if (!destination.getBlockState(feet).getCollisionShape(destination, feet).isEmpty()
                || !destination.getBlockState(feet.above()).getCollisionShape(destination, feet.above()).isEmpty())
            return fail(source, "The recorded return position is obstructed. No blocks were changed; use an operator teleport.");
        cancelJob(player.getUUID(), "Return requested; partial construction was left in its reserved plot.");
        player.teleportTo(destination, point.x(), point.y(), point.z(), point.yaw(), point.pitch());
        player.fallDistance = 0;
        data.returns.remove(player.getUUID());
        data.setDirty();
        return 1;
    }

    private int cancel(CommandSourceStack source) throws CommandSyntaxException {
        UUID owner = source.getPlayerOrException().getUUID();
        if (!jobs.containsKey(owner)) return fail(source, "No active benchmark build.");
        cancelJob(owner, "Build canceled. Already placed blocks remain; the plot will never be reused automatically.");
        return 1;
    }

    private void cancelJob(UUID owner, String reason) {
        BuildJob job = jobs.remove(owner);
        if (job == null) return;
        job.releaseTickets();
        tell(job.level.getServer(), owner, reason + " Placed " + job.positions.size() + " blocks.");
    }

    private int status(CommandSourceStack source) throws CommandSyntaxException {
        UUID owner = source.getPlayerOrException().getUUID();
        BuildJob job = jobs.get(owner);
        String result;
        if (job != null) result = job.phase + ": " + job.cursor + "/" + job.plan.volume() + " scanned cells, "
                + job.positions.size() + " placed blocks, " + job.blockEntities + " block entities; plot " + coordinates(job.origin);
        else {
            CompletedBuild build = BenchmarkSavedData.get(source.getServer()).completed.get(owner);
            result = build == null ? "No active or completed ship benchmark." : "Last build: " + build.length() + " x " + build.width() + " x " + build.height()
                    + ", " + build.blockCount() + " actual blocks, " + build.blockEntityCount() + " block entities, "
                    + build.elapsedMillis() + " ms wall time; core " + coordinates(build.core()) + ". VS assembly has not been performed by this generator.";
        }
        String message = result + String.format(java.util.Locale.ROOT,
                " Generator last/peak %.3f/%.3f ms, cooperative-budget overruns %d; %d active jobs. Use /forge tps for server tick data.",
                lastGeneratorMillis, peakGeneratorMillis, overruns, jobs.size());
        source.sendSuccess(() -> Component.literal(message), false);
        return 1;
    }

    @SubscribeEvent
    public void tick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || jobs.isEmpty()) return;
        long started = System.nanoTime();
        long deadline = started + TICK_BUDGET_NANOS;
        int remaining = MAX_PLACEMENTS_PER_TICK;
        List<BuildJob> snapshot = new ArrayList<>(jobs.values());
        int start = Math.floorMod(roundRobin++, snapshot.size());
        for (int i = 0; i < snapshot.size() && remaining > 0 && System.nanoTime() < deadline; i++) {
            BuildJob job = snapshot.get((start + i) % snapshot.size());
            remaining -= job.advance(deadline, remaining);
            if (job.phase == Phase.DONE || job.phase == Phase.FAILED) {
                jobs.remove(job.owner);
                finish(job);
            }
        }
        lastGeneratorMillis = (System.nanoTime() - started) / 1_000_000.0;
        peakGeneratorMillis = Math.max(peakGeneratorMillis, lastGeneratorMillis);
        if (lastGeneratorMillis > 3.0) overruns++;
    }

    private void finish(BuildJob job) {
        job.releaseTickets();
        MinecraftServer server = job.level.getServer();
        if (job.phase == Phase.FAILED) {
            tell(server, job.owner, "Benchmark stopped: " + job.failure + ". " + job.positions.size()
                    + " newly placed blocks remain in the reserved plot. Existing blocks were not replaced.");
            return;
        }
        ServerPlayer player = server.getPlayerList().getPlayer(job.owner);
        if (job.plan.platform) {
            if (player != null && player.level().dimension().equals(job.requestDimension)) {
                player.teleportTo(job.level, job.origin.getX() + 4.5, job.origin.getY() + 1,
                        job.origin.getZ() + 4.5, -90, 0);
                player.fallDistance = 0;
            }
            tell(server, job.owner, "Shipyard platform ready. Use /starbridge_benchmark create 100|200|300 [machines 0..200].");
            return;
        }
        BlockPos core = job.worldPos(job.plan.coreIndex);
        CompletedBuild build = new CompletedBuild(job.owner, job.origin, core, job.plan.length, job.plan.width,
                job.plan.height, job.plan.machineCount, job.blockEntities, job.positions.toLongArray(),
                (System.nanoTime() - job.started) / 1_000_000L);
        BenchmarkSavedData data = BenchmarkSavedData.get(server);
        data.completed.put(job.owner, build);
        data.setDirty();
        if (player != null && player.level().dimension().equals(SHIPYARD)) {
            player.teleportTo(job.level, core.getX() - 1.5, core.getY(), core.getZ() + 0.5, -90, 0);
            player.fallDistance = 0;
        }
        tell(server, job.owner, "Benchmark complete: " + build.blockCount() + " blocks, " + build.blockEntityCount()
                + " block entities (" + build.machineCount() + " requested machines + ship core). Core " + coordinates(core)
                + ". Static construction only: VS assembly is a separate step, and this is not a flight performance result.");
        Starbridge.LOGGER.info("Ship benchmark completed owner={} size={}x{}x{} blocks={} blockEntities={} millis={}",
                job.owner, build.length(), build.width(), build.height(), build.blockCount(), build.blockEntityCount(), build.elapsedMillis());
    }

    @SubscribeEvent
    public void stopping(ServerStoppingEvent event) {
        jobs.values().forEach(BuildJob::releaseTickets);
        jobs.clear();
        roundRobin = 0;
        lastGeneratorMillis = peakGeneratorMillis = 0;
        overruns = 0;
    }

    private static int fail(CommandSourceStack source, String message) {
        source.sendFailure(Component.literal(message));
        return 0;
    }

    private static void tell(MinecraftServer server, UUID playerId, String message) {
        ServerPlayer player = server.getPlayerList().getPlayer(playerId);
        if (player != null) player.sendSystemMessage(Component.literal(message));
    }

    private static String coordinates(BlockPos pos) { return pos.getX() + " " + pos.getY() + " " + pos.getZ(); }
    private enum Phase { PREFLIGHT, BUILDING, DONE, FAILED }

    private static final class BuildJob {
        final UUID owner;
        final ServerLevel level;
        final BlockPos origin;
        final ShipBlueprint plan;
        final ResourceKey<Level> requestDimension;
        final LongArrayList positions = new LongArrayList();
        final Set<ChunkPos> tickets = new HashSet<>();
        final long started = System.nanoTime();
        Phase phase = Phase.PREFLIGHT;
        int cursor;
        int blockEntities;
        CompletableFuture<?> chunkFuture;
        String failure = "";

        BuildJob(UUID owner, ServerLevel level, BlockPos origin, ShipBlueprint plan, ResourceKey<Level> requestDimension) {
            if (!level.dimension().equals(SHIPYARD)) throw new IllegalArgumentException("Generation outside shipyard is forbidden");
            this.owner = owner;
            this.level = level;
            this.origin = origin;
            this.plan = plan;
            this.requestDimension = requestDimension;
        }

        BlockPos worldPos(int index) { return origin.offset(plan.x(index), plan.y(index), plan.z(index)); }

        int advance(long deadline, int maxWrites) {
            int writes = 0, checks = 0;
            try {
                while (cursor < plan.volume() && checks++ < 4096 && writes < maxWrites && System.nanoTime() < deadline) {
                    ShipBlueprint.Cell cell = plan.cell(cursor);
                    if (cell == null) { cursor++; continue; }
                    BlockPos pos = worldPos(cursor);
                    if (!level.getWorldBorder().isWithinBounds(pos)) { stop("Reserved plot is outside the world border"); break; }
                    if (!chunkReady(pos)) break;
                    if (!level.getBlockState(pos).isAir()) { stop("Occupied block at " + coordinates(pos)); break; }
                    if (phase == Phase.BUILDING) {
                        if (!level.setBlock(pos, state(cell), 2)) { stop("Placement rejected at " + coordinates(pos)); break; }
                        positions.add(pos.asLong());
                        writes++;
                        BlockEntity blockEntity = level.getBlockEntity(pos);
                        if (blockEntity != null) blockEntities++;
                        initialize(cell, blockEntity);
                    }
                    cursor++;
                }
                if (cursor == plan.volume()) {
                    if (phase == Phase.PREFLIGHT) { phase = Phase.BUILDING; cursor = 0; }
                    else if (phase == Phase.BUILDING) phase = Phase.DONE;
                }
            } catch (RuntimeException exception) {
                stop(exception.getClass().getSimpleName() + ": " + exception.getMessage());
                Starbridge.LOGGER.error("Shipyard generation failed at {}", origin, exception);
            }
            return writes;
        }

        private boolean chunkReady(BlockPos pos) {
            ChunkPos chunk = new ChunkPos(pos);
            if (tickets.add(chunk)) level.getChunkSource().addRegionTicket(GENERATION_TICKET, chunk, 2, chunk);
            if (chunkFuture != null) {
                if (!chunkFuture.isDone()) return false;
                chunkFuture.join();
                chunkFuture = null;
            }
            if (level.hasChunkAt(pos)) return true;
            chunkFuture = level.getChunkSource().getChunkFuture(chunk.x, chunk.z, ChunkStatus.FULL, true);
            return false;
        }

        private void initialize(ShipBlueprint.Cell cell, BlockEntity blockEntity) {
            if (cell == ShipBlueprint.Cell.CORE) {
                if (!(blockEntity instanceof ShipCoreBlockEntity core)) throw new IllegalStateException("Ship core block entity missing");
                core.setOwner(owner);
            } else if (blockEntity instanceof Container inventory) {
                if (cell == ShipBlueprint.Cell.FURNACE) {
                    inventory.setItem(0, new ItemStack(Items.RAW_IRON, 64));
                    inventory.setItem(1, new ItemStack(Items.COAL, 64));
                } else if (cell == ShipBlueprint.Cell.CHEST) {
                    inventory.setItem(0, new ItemStack(Items.COBBLESTONE, 64));
                }
                blockEntity.setChanged();
            }
        }

        private void stop(String reason) { phase = Phase.FAILED; failure = reason; }

        void releaseTickets() {
            for (ChunkPos chunk : tickets) level.getChunkSource().removeRegionTicket(GENERATION_TICKET, chunk, 2, chunk);
            tickets.clear();
        }

        private static BlockState state(ShipBlueprint.Cell cell) {
            return switch (cell) {
                case HULL -> Blocks.LIGHT_GRAY_CONCRETE.defaultBlockState();
                case ACCENT -> Blocks.CYAN_CONCRETE.defaultBlockState();
                case DECK -> Blocks.SMOOTH_STONE.defaultBlockState();
                case GLASS -> Blocks.LIGHT_BLUE_STAINED_GLASS.defaultBlockState();
                case LIGHT -> Blocks.SEA_LANTERN.defaultBlockState();
                case LADDER -> Blocks.LADDER.defaultBlockState().setValue(LadderBlock.FACING, Direction.SOUTH);
                case CORE -> Starbridge.SHIP_CORE.get().defaultBlockState();
                case CHEST -> Blocks.CHEST.defaultBlockState();
                case FURNACE -> Blocks.FURNACE.defaultBlockState();
                case RAIL -> Blocks.IRON_BARS.defaultBlockState();
            };
        }
    }
}
