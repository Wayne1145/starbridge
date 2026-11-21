package dev.starbridge.compat.rocketry;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonParser;
import com.mojang.authlib.GameProfile;

import dev.starbridge.Starbridge;
import dev.starbridge.block.ShipCoreBlockEntity;
import dev.starbridge.orbit.OrbitModule;
import dev.starbridge.server.CoreRegistry;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.common.util.FakePlayer;
import net.minecraftforge.common.util.FakePlayerFactory;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.registries.ForgeRegistries;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/** Exercises a full length, two-deck original rocket on a disposable ordinary server. */
final class LoadedShipyardFixture {
    private static final BlockPos CONSOLE = new BlockPos(21, 191, 17);
    private static final BlockPos CORE = new BlockPos(20, 195, 20);
    private static final UUID OWNER = UUID.fromString("865a1aa9-21d7-487e-bf26-62cd734e414d");
    private static final int WIDTH = 7;
    private static final int LENGTH = Integer.getInteger("starbridge.loadedDockLength", 0);
    private static final Path RECORD = Path.of("loaded-dock-result.json");
    private static final Map<String, Object> results = new LinkedHashMap<>();
    private static final Map<String, List<Double>> timings = new LinkedHashMap<>();
    private static final List<BlockPos> cargoStations = new ArrayList<>();
    private static final Map<String, Block> originalBlocks = new HashMap<>();
    private static String phase = "prepare", measuring;
    private static long started;
    private static int ticks, requiredFuel;
    private static FakePlayer pilot;
    private static UUID identity, rocketId;
    private static Object tile;
    private static boolean observedAscent;

    private LoadedShipyardFixture() {}

    static void register() {
        if (LENGTH == 100 || LENGTH == 200 || LENGTH == 300)
            MinecraftForge.EVENT_BUS.addListener(LoadedShipyardFixture::tick);
    }

    private static void tick(TickEvent.ServerTickEvent event) {
        var server = event.getServer();
        if (!server.isDedicatedServer()
                || server.getTickCount() < 45
                || phase.equals("done")
                || !Files.isRegularFile(Path.of("starbridge-disposable-shipyard.fixture"))) return;
        if (event.phase == TickEvent.Phase.START) {
            started = System.nanoTime();
            measuring = phase;
            return;
        }
        if (started == 0) return;
        var level = server.overworld();
        try {
            if (++ticks > 4500) throw new IllegalStateException("Timed out during " + phase);
            switch (phase) {
                case "prepare" -> {
                    for (int dz = 1; dz < LENGTH; dz += 25)
                        cargoStations.add(new BlockPos(20, 192, 18 + dz));
                    cargoStations.add(new BlockPos(20, 192, LENGTH + 16));
                    if (Boolean.getBoolean("starbridge.loadedDockCheckSave")) {
                        checkSaved(level);
                        return;
                    }
                    require(!Files.exists(RECORD), "Use a fresh test instance");
                    results.put("length", LENGTH);
                    results.put("width", WIDTH);
                    results.put("height", 6);
                    results.put(
                            "topology",
                            "Dedicated Forge server, one simulated pilot, no connected clients");
                    results.put(
                            "profile",
                            "Original AR assembler and monitoring-station upgrade; prepared power,"
                                + " propellant buckets, two loaded decks and warm source chunks");
                    prepare(level);
                    var bounds = ShipyardHull.pad(OriginalRocketApi.bounds(tile, pilot, CONSOLE));
                    require(
                            bounds != null && bounds.sx() == WIDTH && bounds.sz() == LENGTH,
                            "Upgraded pad extent: " + bounds);
                    OriginalRocketApi.press(tile, pilot, false);
                    require(
                            (boolean) OriginalRocketApi.call(tile, "isScanning"),
                            "Native scan did not start");
                    phase = "scan";
                }
                case "scan" -> {
                    supply();
                    if ((boolean) OriginalRocketApi.call(tile, "isScanning")) break;
                    String status = ((Enum<?>) OriginalRocketApi.call(tile, "getStatus")).name();
                    results.put("nativeStatus", status);
                    results.put("occupiedBlocks", OriginalRocketApi.number(tile, "getWeight"));
                    results.put("thrust", OriginalRocketApi.number(tile, "getThrust"));
                    results.put("tankCapacity", OriginalRocketApi.number(tile, "getFuel"));
                    requiredFuel =
                            Math.max(
                                    1000,
                                    (int)
                                            Math.ceil(
                                                    ((Number)
                                                                    OriginalRocketApi.call(
                                                                            tile, "getNeededFuel"))
                                                            .doubleValue()));
                    results.put("nativeRequiredPropellant", requiredFuel);
                    require(status.equals("SUCCESS"), "Original scan: " + status);
                    var bounds = ShipyardHull.pad(OriginalRocketApi.bounds(tile, pilot, CONSOLE));
                    RocketTransfer.build(pilot, CONSOLE, tile, bounds);
                    phase = "build";
                }
                case "build" -> {
                    supply();
                    var job = RocketTransfer.job(level, CONSOLE);
                    require(
                            job != null && job.stage != RocketTransfer.Stage.INSPECT,
                            "Build stopped: " + (job == null ? "no job" : job.problem));
                    if (!job.ready()) break;
                    var rocket = level.getEntity(job.rocket);
                    require(
                            rocket != null
                                    && job.hull.matches(
                                            OriginalRocketApi.field(
                                                    OriginalRocketApi.rocket(rocket), "storage")),
                            "Original storage mismatch");
                    require(
                            level.getBlockState(CORE).isAir()
                                    && level.getBlockState(
                                                    cargoStations.get(cargoStations.size() - 1))
                                            .isAir(),
                            "Hull not cut");
                    rocketId = rocket.getUUID();
                    results.put("core", identity.toString());
                    results.put("rocket", rocketId.toString());
                    results.put(
                            "packedBlockEntities",
                            job.hull.cells.stream()
                                    .filter(
                                            row ->
                                                    ((net.minecraft.nbt.CompoundTag) row)
                                                            .contains("Tile"))
                                    .count());
                    results.put("occupiedManifestBlocks", job.hull.cells.size());
                    results.put(
                            "distanceFromConsoleToRocketCentre",
                            Math.sqrt(rocket.distanceToSqr(pilot)));
                    phase = "refuel";
                }
                case "refuel" -> {
                    var job = RocketTransfer.job(level, CONSOLE);
                    for (int i = 0; i < 4 && job.fuel(level) < requiredFuel; i++) {
                        var propellant =
                                Objects.requireNonNull(
                                        ForgeRegistries.ITEMS.getValue(
                                                new ResourceLocation(
                                                        "advancedrocketry",
                                                        "item.bucketrocketfuel")));
                        pilot.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(propellant));
                        RocketTransfer.fuel(pilot, CONSOLE);
                    }
                    if (job.fuel(level) < requiredFuel) break;
                    results.put("launchPropellant", job.fuel(level));
                    results.put("fuelGoal", requiredFuel);
                    pilot.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
                    RocketTransfer.board(pilot, CONSOLE);
                    require(
                            RocketTransfer.riding(pilot, CONSOLE),
                            "Boarding failed from the bow-side dock");
                    RocketTransfer.launch(pilot, CONSOLE);
                    phase = "ascent";
                }
                case "ascent" -> {
                    var job = RocketTransfer.job(level, CONSOLE);
                    if (job != null) {
                        require(
                                job.stage != RocketTransfer.Stage.INSPECT,
                                "Ascent paused: " + job.problem);
                        var ship = level.getEntity(rocketId);
                        if (ship != null
                                && ship.getY() > job.launchY + 2
                                && RocketTransfer.riding(pilot, CONSOLE)) observedAscent = true;
                        break;
                    }
                    require(observedAscent, "Native powered ascent was not witnessed");
                    var core = CoreRegistry.find(identity).orElseThrow();
                    require(OrbitModule.aboard(pilot, core), "Pilot missed the restored cabin");
                    checkCabin(level, core);
                    results.put("cabinCorePos", core.getBlockPos().asLong());
                    results.put("pilotAboard", true);
                    results.put("passed", true);
                    sample();
                    results.put("tickMsByPhase", timingReport());
                    Files.writeString(
                            RECORD, new GsonBuilder().setPrettyPrinting().create().toJson(results));
                    phase = "done";
                    server.halt(false);
                    return;
                }
                default -> throw new IllegalStateException(phase);
            }
            sample();
        } catch (Exception | LinkageError error) {
            results.put("passed", false);
            results.put("phase", phase);
            results.put("error", error.toString());
            results.put("tickMsByPhase", timingReport());
            Starbridge.LOGGER.error("Loaded original shipyard failed during " + phase, error);
            try {
                Path output =
                        Boolean.getBoolean("starbridge.loadedDockCheckSave")
                                ? Path.of("loaded-dock-restart.json")
                                : RECORD;
                Files.writeString(
                        output, new GsonBuilder().setPrettyPrinting().create().toJson(results));
            } catch (java.io.IOException writing) {
                error.addSuppressed(writing);
            }
            phase = "done";
            server.halt(false);
        }
    }

    private static void prepare(ServerLevel level) throws ReflectiveOperationException {
        // Far-end machinery is deliberately beyond an ordinary player's simulation radius at the
        // bow.
        for (int cz = 1; cz <= (17 + LENGTH) >> 4; cz++) {
            level.setChunkForced(1, cz, true);
            level.getChunk(1, cz);
        }
        var source = new BlockPos.MutableBlockPos();
        for (int z = 18; z < 18 + LENGTH; z++) {
            for (int x = 18; x < 18 + WIDTH; x++) {
                place(level, source.set(x, 190, z), block("launchpad"));
                place(level, source.set(x, 191, z), block(x % 2 == 0 ? "rocketmotor" : "fueltank"));
                place(level, source.set(x, 194, z), Blocks.IRON_BLOCK);
            }
            for (int edge : new int[] {18, 24}) {
                place(level, source.set(edge, 192, z), block("fueltank"));
                for (int y : new int[] {193, 195, 196})
                    place(level, source.set(edge, y, z), Blocks.GRAY_STAINED_GLASS);
            }
        }
        for (int y = 190; y <= 196; y++)
            place(level, source.set(17, y, 19), block("structuretower"));
        var builder = block("rocketbuilder");
        var meta = (IntegerProperty) builder.getStateDefinition().getProperty("meta");
        level.setBlock(CONSOLE, builder.defaultBlockState().setValue(meta, 2), 3);
        place(level, CONSOLE.above(), block("blockmonitoringstation"));
        place(level, new BlockPos(21, 195, 19), block("seat"));
        place(level, new BlockPos(21, 195, 20), block("tile.guidancecomputer"));
        place(level, CORE, Starbridge.SHIP_CORE.get());
        var core = (ShipCoreBlockEntity) level.getBlockEntity(CORE);
        core.setOwner(OWNER);
        identity = core.id();
        for (BlockPos station : cargoStations) {
            place(level, station, Blocks.CHEST);
            ((ChestBlockEntity) level.getBlockEntity(station))
                    .setItem(0, new ItemStack(Items.DIAMOND, 17));
            var hot = station.offset(2, 0, 0);
            place(level, hot, Blocks.FURNACE);
            var furnace = (AbstractFurnaceBlockEntity) level.getBlockEntity(hot);
            furnace.setItem(0, new ItemStack(Items.IRON_ORE, 8));
            furnace.setItem(1, new ItemStack(Items.COAL, 3));
        }
        tile = OriginalRocketApi.builder(level.getBlockEntity(CONSOLE)).orElseThrow();
        supply();
        pilot = FakePlayerFactory.get(level, new GameProfile(OWNER, "LoadedDockPilot"));
        pilot.setPos(CONSOLE.getX() + .5, CONSOLE.getY(), CONSOLE.getZ() - .5);
    }

    private static void checkCabin(ServerLevel level, ShipCoreBlockEntity core) {
        require(
                identity.equals(core.id()) && rocketId.equals(core.originalRocket()),
                "Ship identity changed");
        var bounds = OriginalShipyard.cabinBounds(core).orElseThrow();
        require(
                bounds.maxZ() - bounds.minZ() + 1 == LENGTH
                        && bounds.maxX() - bounds.minX() + 1 == WIDTH,
                "Cabin extent changed");
        require(
                level.getEntity(rocketId) == null && level.getBlockState(CORE).isAir(),
                "Duplicate source");
        var delta = core.getBlockPos().subtract(CORE);
        int items = 0, diamonds = 0;
        for (var original : cargoStations) {
            var pos = original.offset(delta);
            var chest = (ChestBlockEntity) level.getBlockEntity(pos);
            var furnace = (AbstractFurnaceBlockEntity) level.getBlockEntity(pos.offset(2, 0, 0));
            require(
                    chest != null
                            && furnace != null
                            && chest.getItem(0).is(Items.DIAMOND)
                            && chest.getItem(0).getCount() == 17,
                    "Lost cargo near " + original);
            int ore = furnace.getItem(0).getCount() + furnace.getItem(2).getCount();
            require(ore == 8, "Far-end furnace inventory changed near " + original);
            diamonds += chest.getItem(0).getCount();
            items += ore;
        }
        require(
                level.getBlockState(new BlockPos(24, 196, LENGTH + 17).offset(delta))
                        .is(Blocks.GRAY_STAINED_GLASS),
                "Stern wall lost");
        results.put("cargoStations", cargoStations.size());
        results.put("diamonds", diamonds);
        results.put("furnaceInputsAndOutputs", items);
    }

    private static void checkSaved(ServerLevel level) throws Exception {
        var record = JsonParser.parseString(Files.readString(RECORD)).getAsJsonObject();
        require(
                record.get("passed").getAsBoolean() && record.get("length").getAsInt() == LENGTH,
                "No matching completed run for restart");
        identity = UUID.fromString(record.get("core").getAsString());
        rocketId = UUID.fromString(record.get("rocket").getAsString());
        var pos = BlockPos.of(record.get("cabinCorePos").getAsLong());
        level.getChunkAt(CORE);
        level.getChunkAt(pos);
        checkCabin(level, (ShipCoreBlockEntity) level.getBlockEntity(pos));
        results.put("core", identity.toString());
        results.put("rocket", rocketId.toString());
        results.put("passed", true);
        Files.writeString(
                Path.of("loaded-dock-restart.json"),
                new GsonBuilder().setPrettyPrinting().create().toJson(results));
        phase = "done";
        level.getServer().halt(false);
    }

    private static void sample() {
        timings.computeIfAbsent(measuring, unused -> new ArrayList<>())
                .add((System.nanoTime() - started) / 1_000_000d);
    }

    private static Map<String, Object> timingReport() {
        var report = new LinkedHashMap<String, Object>();
        timings.forEach(
                (part, values) -> {
                    var sorted = values.stream().sorted().toList();
                    double p95 = sorted.get((int) Math.ceil(sorted.size() * .95) - 1);
                    report.put(
                            part,
                            Map.of(
                                    "samples",
                                    sorted.size(),
                                    "mean",
                                    values.stream().mapToDouble(v -> v).average().orElseThrow(),
                                    "p95",
                                    p95,
                                    "maximum",
                                    sorted.get(sorted.size() - 1)));
                });
        return report;
    }

    private static void supply() throws ReflectiveOperationException {
        tile.getClass().getMethod("setEnergyStored", int.class).invoke(tile, 100000);
    }

    private static Block block(String name) {
        return originalBlocks.computeIfAbsent(
                name,
                key ->
                        Objects.requireNonNull(
                                ForgeRegistries.BLOCKS.getValue(
                                        new ResourceLocation("advancedrocketry", key))));
    }

    private static void place(ServerLevel level, BlockPos pos, Block block) {
        level.setBlock(pos, block.defaultBlockState(), Block.UPDATE_CLIENTS);
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }
}
