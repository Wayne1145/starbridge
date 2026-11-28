package dev.starbridge.assembly;

import com.mojang.brigadier.exceptions.CommandSyntaxException;
import dev.starbridge.Starbridge;
import dev.starbridge.benchmark.BenchmarkModule;
import dev.starbridge.benchmark.CompletedBuild;
import dev.starbridge.block.ShipCoreBlockEntity;
import dev.starbridge.command.StarbridgeCommands;
import dev.starbridge.compat.vs.VsShipBackend;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.coordinates.BlockPosArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.TicketType;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkStatus;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.loading.FMLPaths;

import java.io.IOException;
import java.io.OutputStream;
import java.io.DataOutputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Explicit scan/confirm assembly. Nothing resumes or assembles automatically after restart. */
public final class AssemblyModule {
    private static final int CELLS_PER_TICK = 2048;
    private static final long BUDGET_NANOS = 3_000_000L;
    private static final long READY_TTL_NANOS = 300_000_000_000L;
    private static final long MAX_NBT_BYTES = 64L * 1024 * 1024;
    private static final long MAX_BLOCK_ENTITY_NBT_BYTES = 1024L * 1024;
    private static final TagKey<Block> BLACKLIST = TagKey.create(Registries.BLOCK,
            new ResourceLocation("valkyrienskies", "assemble_blacklist"));
    private static final TicketType<ChunkPos> TICKET = TicketType.create("starbridge_assembly",
            Comparator.comparingLong((ChunkPos pos) -> pos.toLong()));
    private final VsShipBackend physics = new VsShipBackend();
    private final Map<UUID, Job> jobs = new LinkedHashMap<>();
    private final Map<UUID, String> lastResults = new HashMap<>();
    private ExecutorService backupWriter;
    private int nextJob;

    private static AssemblyModule instance;

    private AssemblyModule() { }
    public static void register() {
        instance = new AssemblyModule();
        MinecraftForge.EVENT_BUS.register(instance);
    }

    /**
     * Survival entry point used by the hull marker: the first use on an owned core scans the marked
     * box, the next use confirms a completed scan. The caller has already checked ownership.
     */
    public static int useMarker(ServerPlayer player, ShipCoreBlockEntity core, BlockPos from, BlockPos to) {
        if (instance == null) return 0;
        CommandSourceStack source = player.createCommandSourceStack();
        try {
            Job job = instance.jobs.get(player.getUUID());
            if (job != null && job.phase == Phase.READY) return instance.confirm(source);
            if (job != null) return instance.status(source);
            core.register();
            player.getPersistentData().putUUID("starbridge:selected_core", core.id());
            return instance.prepare(source, player, core, from, to, false, null);
        } catch (CommandSyntaxException failure) {
            player.sendSystemMessage(Component.literal(failure.getMessage()));
            return 0;
        }
    }

    @SubscribeEvent
    public void commands(RegisterCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("starbridge_assemble")
                .then(Commands.literal("scan")
                        .then(Commands.argument("from", BlockPosArgument.blockPos())
                                .then(Commands.argument("to", BlockPosArgument.blockPos())
                                        .executes(context -> scan(context.getSource(),
                                                BlockPosArgument.getBlockPos(context, "from"),
                                                BlockPosArgument.getBlockPos(context, "to"))))))
                .then(Commands.literal("benchmark").requires(source -> source.hasPermission(2))
                        .executes(context -> benchmark(context.getSource())))
                .then(Commands.literal("confirm").executes(context -> confirm(context.getSource())))
                .then(Commands.literal("cancel").executes(context -> cancel(context.getSource())))
                .then(Commands.literal("status").executes(context -> status(context.getSource()))));
    }

    private int scan(CommandSourceStack source, BlockPos from, BlockPos to) throws CommandSyntaxException {
        ServerPlayer player = source.getPlayerOrException();
        ShipCoreBlockEntity core = StarbridgeCommands.selected(source);
        return prepare(source, player, core, from, to, false, null);
    }

    private int benchmark(CommandSourceStack source) throws CommandSyntaxException {
        ServerPlayer player = source.getPlayerOrException();
        if (!player.level().dimension().equals(BenchmarkModule.SHIPYARD)) return fail(source, "Enter the benchmark shipyard first.");
        CompletedBuild build = BenchmarkModule.getLastBuild(player.getUUID()).orElse(null);
        if (build == null) return fail(source, "No completed benchmark belongs to you.");
        if (!player.serverLevel().hasChunkAt(build.core())) return fail(source, "Benchmark core chunk is not loaded.");
        if (!(player.serverLevel().getBlockEntity(build.core()) instanceof ShipCoreBlockEntity core)
                || !core.authorized(player)) return fail(source, "The benchmark core is missing or no longer authorized.");
        core.register();
        player.getPersistentData().putUUID("starbridge:selected_core", core.id());
        BlockPos maximum = build.origin().offset(build.length() - 1, build.height() - 1, build.width() - 1);
        Set<Long> originalPositions = new HashSet<>();
        for (BlockPos position : build.positions()) originalPositions.add(position.asLong());
        return prepare(source, player, core, build.origin(), maximum, true, originalPositions);
    }

    private int prepare(CommandSourceStack source, ServerPlayer player, ShipCoreBlockEntity core,
                        BlockPos from, BlockPos to, boolean benchmark, Set<Long> expectedPositions) {
        if (jobs.containsKey(player.getUUID())) return fail(source, "A selection already exists. Use status or cancel first.");
        if (jobs.size() >= 4) return fail(source, "Four assembly jobs are already active; try again after one completes.");
        if (core.getLevel() != player.serverLevel()) return fail(source, "The selected core must be in your current dimension.");
        if (core.owner() == null) return fail(source, "The selected core must have a recorded owner before assembly.");
        var availability = physics.probe();
        if (!availability.available()) return fail(source, availability.detail());
        try {
            SelectionBounds bounds = SelectionBounds.between(from.getX(), from.getY(), from.getZ(),
                    to.getX(), to.getY(), to.getZ());
            BlockPos corePos = core.getBlockPos();
            if (!bounds.contains(corePos.getX(), corePos.getY(), corePos.getZ())) return fail(source, "Selection must include your selected core.");
            ServerLevel level = player.serverLevel();
            if (bounds.minY() < level.getMinBuildHeight() || bounds.maxY() >= level.getMaxBuildHeight())
                return fail(source, "Selection exceeds this dimension's build height.");
            Job job = new Job(player.getUUID(), level, core.id(), core.owner(), corePos.immutable(), bounds, benchmark, expectedPositions);
            jobs.put(player.getUUID(), job);
            lastResults.remove(player.getUUID());
            source.sendSuccess(() -> Component.translatable("starbridge.message.assembly_scanning",
                    bounds.width(), bounds.height(), bounds.depth(), bounds.volume()), false);
            return 1;
        } catch (RuntimeException exception) { return fail(source, exception.getMessage()); }
    }

    private int confirm(CommandSourceStack source) throws CommandSyntaxException {
        UUID owner = source.getPlayerOrException().getUUID();
        Job job = jobs.get(owner);
        if (job == null || job.phase != Phase.READY) return fail(source, "No completed scan is ready. Use scan or status first.");
        try {
            job.requireAuthorized();
            if (System.nanoTime() - job.readyAt >= READY_TTL_NANOS) throw new IllegalStateException("Scan expired. Cancel and scan again.");
            job.phase = Phase.VERIFYING;
            job.cursor = 0;
            source.sendSuccess(() -> Component.translatable("starbridge.message.assembly_confirmed", job.blocks.size()), false);
            return 1;
        } catch (RuntimeException exception) { stop(job, exception.getMessage()); return 0; }
    }

    private int cancel(CommandSourceStack source) throws CommandSyntaxException {
        Job job = jobs.get(source.getPlayerOrException().getUUID());
        if (job == null) return fail(source, "No pending assembly.");
        stop(job, "Canceled. No automatic assembly will occur; a completed or partial backup file may remain.");
        return 1;
    }

    private int status(CommandSourceStack source) throws CommandSyntaxException {
        UUID player = source.getPlayerOrException().getUUID();
        Job job = jobs.get(player);
        String message = job == null ? lastResults.getOrDefault(player, "No pending assembly.")
                : job.phase + " · " + job.cursor + "/" + job.bounds.volume() + " cells · " + job.blocks.size()
                + " actual blocks · " + job.blockEntities + " block entities · " + coordinates(job.minimum()) + " → " + coordinates(job.maximum());
        source.sendSuccess(() -> Component.literal(message), false);
        return 1;
    }

    @SubscribeEvent
    public void tick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || jobs.isEmpty()) return;
        long deadline = System.nanoTime() + BUDGET_NANOS;
        int budget = CELLS_PER_TICK;
        var pending = new ArrayList<>(jobs.values());
        int start = Math.floorMod(nextJob++, pending.size());
        for (int index = 0; index < pending.size() && budget > 0 && System.nanoTime() < deadline; index++) {
            Job job = pending.get((start + index) % pending.size());
            try {
                job.requireAuthorized();
                if (job.phase == Phase.READY) {
                    if (System.nanoTime() - job.readyAt >= READY_TTL_NANOS) stop(job, "Scan expired after five minutes. Scan again before confirming.");
                    continue;
                }
                if (job.phase == Phase.SAVING) {
                    if (!job.backup.isDone()) continue;
                    job.backupPath = job.backup.join();
                    commit(job); // VS assembly is synchronous; execute no second assembly this tick.
                    break;
                }
                while (budget > 0 && job.cursor < job.bounds.volume() && System.nanoTime() < deadline) {
                    BlockPos pos = job.position(job.cursor);
                    if (!job.chunkReady(pos)) break;
                    if (job.phase == Phase.SCANNING) job.capture(pos);
                    else job.verify(pos);
                    job.cursor++;
                    budget--;
                }
                if (job.cursor == job.bounds.volume()) {
                    if (job.phase == Phase.SCANNING) {
                        if (!job.blocks.containsKey(job.corePosition.asLong())) throw new IllegalStateException("Selected core disappeared during scan.");
                        job.phase = Phase.READY;
                        job.readyAt = System.nanoTime();
                        tell(job, Component.translatable("starbridge.message.assembly_ready", job.blocks.size(), job.blockEntities,
                                coordinates(job.minimum()), coordinates(job.maximum())));
                    } else if (job.phase == Phase.VERIFYING) {
                        job.phase = Phase.SAVING;
                        job.backup = CompletableFuture.supplyAsync(() -> writeBackup(job), writer());
                    }
                }
            } catch (RuntimeException exception) {
                stop(job, exception.getMessage() == null ? exception.getClass().getSimpleName() : exception.getMessage());
            }
        }
    }

    private void commit(Job job) {
        job.requireAuthorized();
        long started = System.nanoTime();
        // Final validation and movement occur in one server-thread turn. Nobody
        // can edit the region between this validation and the synchronous call.
        // This pass and VS's own assembly are intentionally NOT advertised as
        // bounded to the cooperative 3ms scan budget.
        for (int index = 0; index < job.bounds.volume(); index++) {
            BlockPos pos = job.position(index);
            if (!job.level.hasChunkAt(pos)) throw new IllegalStateException("A selected chunk unloaded before final validation.");
            job.verify(pos);
        }
        job.requireAuthorized();
        Set<BlockPos> selected = new HashSet<>(job.blocks.size());
        for (long position : job.blocks.keySet()) selected.add(BlockPos.of(position));
        tell(job, Component.translatable("starbridge.message.assembly_backup"));
        long ship;
        try { ship = physics.assemble(job.level, selected, 1.0, true); }
        catch (RuntimeException exception) {
            long failedMillis = (System.nanoTime() - started) / 1_000_000L;
            Starbridge.LOGGER.error("Assembly failed owner={} core={} blocks={} millis={} backup={}",
                    job.owner, job.coreId, selected.size(), failedMillis, job.backupPath, exception);
            throw new IllegalStateException("VS assembly failed. The region may be partially moved; no automatic rollback was attempted. Backup: "
                    + job.backupPath + ". " + exception.getMessage(), exception);
        }
        long millis = (System.nanoTime() - started) / 1_000_000L;
        String result = "Assembled and parked ship " + ship + " from " + selected.size() + " blocks in " + millis
                + " ms (final validation + synchronous assembly). Backup: " + job.backupPath
                + ". Core selection retains UUID " + job.coreId + "; supply energy and use /starbridge launch when ready.";
        if (job.benchmark) BenchmarkModule.forgetLastBuild(job.owner);
        complete(job, result);
        tell(job, Component.translatable("starbridge.message.assembly_done", selected.size()));
        Starbridge.LOGGER.info("Assembly completed owner={} core={} ship={} blocks={} millis={} backup={}",
                job.owner, job.coreId, ship, selected.size(), millis, job.backupPath);
    }

    private synchronized ExecutorService writer() {
        if (backupWriter == null) backupWriter = Executors.newSingleThreadExecutor(task -> {
            Thread thread = new Thread(task, "starbridge-assembly-backup"); thread.setDaemon(true); return thread;
        });
        return backupWriter;
    }

    private static Path writeBackup(Job job) {
        try {
            Path folder = FMLPaths.GAMEDIR.get().toAbsolutePath().normalize().resolve("starbridge-backups");
            Files.createDirectories(folder);
            String name = "assembly-" + System.currentTimeMillis() + "-" + UUID.randomUUID() + ".nbt";
            Path partial = folder.resolve(name + ".partial");
            Path target = folder.resolve(name);
            CompoundTag root = new CompoundTag();
            root.putInt("StarbridgeBackupVersion", 1);
            root.putString("Dimension", job.dimensionName);
            root.putUUID("RequestedBy", job.owner);
            root.putUUID("CoreId", job.coreId);
            root.putLong("Minimum", job.minimum().asLong());
            root.putLong("Maximum", job.maximum().asLong());
            root.putString("RestorePolicy", "Manual recovery only. Original coordinates; verify no live duplicate ship before restoring inventories.");
            ListTag blocks = new ListTag();
            // Job.blocks is immutable after the server-thread scan completes.
            // The writer touches only immutable snapshots, never the live world.
            for (var entry : job.blocks.entrySet()) {
                CompoundTag block = new CompoundTag();
                block.putLong("Position", entry.getKey());
                block.put("State", NbtUtils.writeBlockState(entry.getValue().state()));
                if (entry.getValue().nbt() != null) block.put("BlockEntity", entry.getValue().nbt().copy());
                blocks.add(block);
            }
            root.put("Blocks", blocks);
            try (OutputStream stream = Files.newOutputStream(partial, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
                NbtIo.writeCompressed(root, stream);
            }
            try { Files.move(partial, target, StandardCopyOption.ATOMIC_MOVE); }
            catch (AtomicMoveNotSupportedException exception) { Files.move(partial, target); }
            return target;
        } catch (IOException exception) { throw new IllegalStateException("Backup failed; assembly was not started: " + exception.getMessage(), exception); }
    }

    private void stop(Job job, String reason) {
        complete(job, "Assembly stopped: " + reason);
    }

    private void complete(Job job, String result) {
        jobs.remove(job.owner, job);
        job.releaseTickets();
        lastResults.put(job.owner, result);
        tell(job, result);
    }

    @SubscribeEvent
    public void stopping(ServerStoppingEvent event) {
        jobs.values().forEach(Job::releaseTickets);
        jobs.clear();
        lastResults.clear();
        if (backupWriter != null) { backupWriter.shutdownNow(); backupWriter = null; }
        physics.close();
    }

    private static int fail(CommandSourceStack source, String reason) {
        source.sendFailure(Component.literal(reason == null ? "Assembly request failed." : reason)); return 0;
    }
    private static String coordinates(BlockPos pos) { return pos.getX() + " " + pos.getY() + " " + pos.getZ(); }
    private static void tell(Job job, String message) { tell(job, Component.literal(message)); }
    private static void tell(Job job, Component message) {
        ServerPlayer player = job.level.getServer().getPlayerList().getPlayer(job.owner);
        if (player != null) player.sendSystemMessage(message);
    }
    private enum Phase { SCANNING, READY, VERIFYING, SAVING }
    private record BlockSnapshot(BlockState state, CompoundTag nbt) { }

    private final class Job {
        final UUID owner, coreId, coreOwner;
        final ServerLevel level;
        final String dimensionName;
        final BlockPos corePosition;
        final SelectionBounds bounds;
        final boolean benchmark;
        final Set<Long> expectedPositions;
        final Map<Long, BlockSnapshot> blocks = new LinkedHashMap<>();
        final Set<ChunkPos> tickets = new HashSet<>();
        Phase phase = Phase.SCANNING;
        int cursor, blockEntities;
        long nbtBytes;
        long readyAt;
        CompletableFuture<?> chunkFuture;
        CompletableFuture<Path> backup;
        Path backupPath;

        Job(UUID owner, ServerLevel level, UUID coreId, UUID coreOwner, BlockPos corePosition,
            SelectionBounds bounds, boolean benchmark, Set<Long> expectedPositions) {
            this.owner = owner; this.level = level; this.coreId = coreId; this.coreOwner = coreOwner;
            this.corePosition = corePosition; this.bounds = bounds; this.benchmark = benchmark;
            this.expectedPositions = expectedPositions;
            dimensionName = level.dimension().location().toString();
        }

        BlockPos minimum() { return new BlockPos(bounds.minX(), bounds.minY(), bounds.minZ()); }
        BlockPos maximum() { return new BlockPos(bounds.maxX(), bounds.maxY(), bounds.maxZ()); }
        BlockPos position(int index) { return new BlockPos(bounds.x(index), bounds.y(index), bounds.z(index)); }

        ServerPlayer requireAuthorized() {
            ServerPlayer player = level.getServer().getPlayerList().getPlayer(owner);
            if (player == null || player.serverLevel() != level) throw new IllegalStateException("Requester left the server or dimension.");
            if (!player.getPersistentData().hasUUID("starbridge:selected_core")
                    || !player.getPersistentData().getUUID("starbridge:selected_core").equals(coreId))
                throw new IllegalStateException("Selected core changed; scan must be confirmed for the same core.");
            if (!level.hasChunkAt(corePosition)) throw new IllegalStateException("Selected core is not loaded.");
            if (!(level.getBlockEntity(corePosition) instanceof ShipCoreBlockEntity core)
                    || !core.id().equals(coreId) || !Objects.equals(core.owner(), coreOwner) || !core.authorized(player))
                throw new IllegalStateException("Selected core was removed, replaced, or changed owner.");
            return player;
        }

        boolean chunkReady(BlockPos pos) {
            ChunkPos chunk = new ChunkPos(pos);
            if (!tickets.contains(chunk)) {
                if (physics.isShipyardChunk(level, chunk.x, chunk.z)) throw new IllegalStateException("Selection intersects an existing VS ship-storage region.");
                tickets.add(chunk);
                level.getChunkSource().addRegionTicket(TICKET, chunk, 2, chunk);
            }
            if (chunkFuture != null) {
                if (!chunkFuture.isDone()) return false;
                chunkFuture.join(); chunkFuture = null;
            }
            if (level.hasChunkAt(pos)) return true;
            chunkFuture = level.getChunkSource().getChunkFuture(chunk.x, chunk.z, ChunkStatus.FULL, true);
            return false;
        }

        void capture(BlockPos pos) {
            if (!level.getWorldBorder().isWithinBounds(pos)) throw new IllegalStateException("Selection crosses the world border.");
            ServerPlayer player = level.getServer().getPlayerList().getPlayer(owner);
            if (player == null || !level.mayInteract(player, pos)) throw new IllegalStateException("Selection contains a protected block at " + coordinates(pos));
            BlockState state = level.getBlockState(pos);
            if (state.isAir()) {
                if (expectedPositions != null && expectedPositions.contains(pos.asLong())) throw new IllegalStateException("A generated benchmark block was removed at " + coordinates(pos));
                return;
            }
            if (expectedPositions != null && !expectedPositions.contains(pos.asLong())) throw new IllegalStateException("An extra block was added inside the benchmark selection at " + coordinates(pos));
            if (state.is(Blocks.BEDROCK) || state.getDestroySpeed(level, pos) < 0 || state.is(BLACKLIST))
                throw new IllegalStateException("Unbreakable or assembly-blacklisted block at " + coordinates(pos));
            if (!state.getFluidState().isEmpty()) throw new IllegalStateException("Drain fluids and waterlogged blocks before assembly: " + coordinates(pos));
            BlockEntity entity = level.getBlockEntity(pos);
            if (entity instanceof ShipCoreBlockEntity other && !Objects.equals(other.owner(), coreOwner))
                throw new IllegalStateException("Selection contains a core owned by somebody else at " + coordinates(pos));
            if (blocks.size() >= SelectionBounds.MAX_BLOCKS) throw new IllegalStateException("Selection exceeds 150,000 non-air blocks.");
            CompoundTag nbt = entity == null ? null : entity.saveWithFullMetadata().copy();
            if (nbt != null) {
                long size = measureNbt(nbt);
                if (size > MAX_BLOCK_ENTITY_NBT_BYTES || nbtBytes + size > MAX_NBT_BYTES)
                    throw new IllegalStateException("Block entity backup data exceeds the 1 MiB per-block or 64 MiB total safety limit.");
                nbtBytes += size;
            }
            blocks.put(pos.asLong(), new BlockSnapshot(state, nbt));
            if (entity != null) blockEntities++;
        }

        void verify(BlockPos pos) {
            if (!level.getWorldBorder().isWithinBounds(pos)) throw new IllegalStateException("World border changed since scan.");
            BlockSnapshot expected = blocks.get(pos.asLong());
            BlockState current = level.getBlockState(pos);
            if (expected == null) {
                if (!current.isAir()) throw new IllegalStateException("Previously empty cell changed at " + coordinates(pos) + "; scan again.");
                return;
            }
            ServerPlayer player = level.getServer().getPlayerList().getPlayer(owner);
            if (player == null || !level.mayInteract(player, pos))
                throw new IllegalStateException("Selected block is no longer authorized at " + coordinates(pos));
            if (current.is(BLACKLIST) || current.getDestroySpeed(level, pos) < 0)
                throw new IllegalStateException("Assembly blacklist or block protection changed at " + coordinates(pos));
            if (!current.equals(expected.state())) throw new IllegalStateException("Block changed at " + coordinates(pos) + "; scan again.");
            BlockEntity entity = level.getBlockEntity(pos);
            CompoundTag nbt = entity == null ? null : entity.saveWithFullMetadata();
            if (!Objects.equals(expected.nbt(), nbt)) throw new IllegalStateException("Machine, inventory or ownership data changed at "
                    + coordinates(pos) + "; stop machines/transfers and scan again.");
        }

        void releaseTickets() {
            for (ChunkPos chunk : tickets) level.getChunkSource().removeRegionTicket(TICKET, chunk, 2, chunk);
            tickets.clear();
        }
    }

    private static long measureNbt(CompoundTag tag) {
        CountingOutputStream counter = new CountingOutputStream();
        try (DataOutputStream data = new DataOutputStream(counter)) { NbtIo.write(tag, data); }
        catch (IOException exception) { throw new IllegalStateException("Could not measure block entity backup data.", exception); }
        return counter.count;
    }

    private static final class CountingOutputStream extends OutputStream {
        long count;
        @Override public void write(int value) throws IOException { add(1); }
        @Override public void write(byte[] bytes, int offset, int length) throws IOException { add(length); }
        private void add(int length) throws IOException {
            count += length;
            if (count > MAX_BLOCK_ENTITY_NBT_BYTES) throw new IOException("Block entity NBT exceeds 1 MiB.");
        }
    }
}
