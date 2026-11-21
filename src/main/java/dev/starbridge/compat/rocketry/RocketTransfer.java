package dev.starbridge.compat.rocketry;

import dev.starbridge.Starbridge;
import dev.starbridge.block.ShipCoreBlockEntity;
import dev.starbridge.voyage.VoyageLog;
import dev.starbridge.voyage.VoyageNetwork;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.*;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.EntityJoinLevelEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.*;

/**
 * Server-thread journal for the original machine, its one native rocket and its one physical cabin.
 */
final class RocketTransfer extends SavedData {
    enum Stage {
        BUILD,
        CUTTING,
        PACKED,
        ASCENT,
        TRANSFER,
        INSPECT
    }

    static final class Job {
        final BlockPos console;
        final UUID owner;
        Stage stage = Stage.BUILD;
        ShipyardHull hull;
        UUID rocket;
        double launchY;
        BlockPos offset;
        int cabinSlot = -1;
        String problem = "";
        final Map<UUID, Vec3> riders = new LinkedHashMap<>();
        final Map<UUID, UUID> seats = new HashMap<>();

        Job(BlockPos console, UUID owner) {
            this.console = console.immutable();
            this.owner = owner;
        }

        boolean ready() {
            return stage == Stage.PACKED;
        }

        int fuel(ServerLevel level) {
            try {
                var entity = rocket == null ? null : level.getEntity(rocket);
                return entity == null
                        ? 0
                        : OriginalRocketApi.number(
                                OriginalRocketApi.rocket(entity), "getFuelAmount");
            } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
                return 0;
            }
        }

        CompoundTag write() {
            var t = new CompoundTag();
            t.putLong("Console", console.asLong());
            t.putUUID("Owner", owner);
            t.putString("Stage", stage.name());
            t.putString("Problem", problem);
            if (hull != null) t.put("Hull", hull.save());
            if (rocket != null) t.putUUID("Rocket", rocket);
            t.putDouble("LaunchY", launchY);
            t.putInt("CabinSlot", cabinSlot);
            if (offset != null) t.putLong("Offset", offset.asLong());
            var passengers = new ListTag();
            riders.forEach(
                    (id, p) -> {
                        var r = new CompoundTag();
                        r.putUUID("Id", id);
                        if (seats.containsKey(id)) r.putUUID("Seat", seats.get(id));
                        r.putDouble("X", p.x);
                        r.putDouble("Y", p.y);
                        r.putDouble("Z", p.z);
                        passengers.add(r);
                    });
            t.put("Riders", passengers);
            return t;
        }

        static Job read(CompoundTag t) {
            var j = new Job(BlockPos.of(t.getLong("Console")), t.getUUID("Owner"));
            j.stage = Stage.valueOf(t.getString("Stage"));
            j.problem = t.getString("Problem");
            if (t.contains("Hull")) j.hull = ShipyardHull.load(t.getCompound("Hull"));
            if (t.hasUUID("Rocket")) j.rocket = t.getUUID("Rocket");
            j.launchY = t.getDouble("LaunchY");
            j.cabinSlot = t.contains("CabinSlot", Tag.TAG_INT) ? t.getInt("CabinSlot") : -1;
            if (t.contains("Offset")) j.offset = BlockPos.of(t.getLong("Offset"));
            for (Tag a : t.getList("Riders", Tag.TAG_COMPOUND)) {
                var r = (CompoundTag) a;
                if (r.hasUUID("Seat")) j.seats.put(r.getUUID("Id"), r.getUUID("Seat"));
                j.riders.put(
                        r.getUUID("Id"),
                        new Vec3(r.getDouble("X"), r.getDouble("Y"), r.getDouble("Z")));
            }
            return j;
        }
    }

    private final Map<BlockPos, Job> docks = new LinkedHashMap<>();
    private final Set<UUID> retired = new HashSet<>();
    private final Map<UUID, CompoundTag> arrivals = new HashMap<>();
    private int berthSlot;

    private RocketTransfer() {}

    private static RocketTransfer get(ServerLevel level) {
        return level.getDataStorage()
                .computeIfAbsent(
                        RocketTransfer::load, RocketTransfer::new, "starbridge_original_shipyard");
    }

    static Job job(ServerLevel level, BlockPos pos) {
        return get(level).docks.get(pos);
    }

    static boolean busy(ServerLevel level, BlockPos pos) {
        return job(level, pos) != null;
    }

    static boolean riding(ServerPlayer player, BlockPos pos) {
        var j = job(player.serverLevel(), pos);
        return j != null && CrewSeats.riding(j, player);
    }

    static void register() {
        MinecraftForge.EVENT_BUS.addListener(RocketTransfer::tick);
        MinecraftForge.EVENT_BUS.addListener(
                (EntityJoinLevelEvent e) -> {
                    if (e.getLevel() instanceof ServerLevel level
                            && CrewSeats.parent(e.getEntity()) != null
                            && get(level).retired.contains(CrewSeats.parent(e.getEntity()))) {
                        e.setCanceled(true);
                        return;
                    }
                    if (e.getLevel() instanceof ServerLevel l
                            && OriginalRocketApi.CARRIER.equals(
                                    ForgeRegistries.ENTITY_TYPES.getKey(e.getEntity().getType()))
                            && get(l).retired.contains(e.getEntity().getUUID()))
                        e.setCanceled(true);
                });
        MinecraftForge.EVENT_BUS.addListener(
                (PlayerEvent.PlayerLoggedInEvent e) -> {
                    if (e.getEntity() instanceof ServerPlayer p)
                        for (ServerLevel l : p.server.getAllLevels()) get(l).arrive(l, p);
                });
    }

    static void build(ServerPlayer p, BlockPos pos, Object tile, ShipyardHull.Box box)
            throws ReflectiveOperationException {
        var l = p.serverLevel();
        var d = get(l);
        if (d.docks.containsKey(pos)) throw new IllegalStateException("busy");
        if (!((Enum<?>) OriginalRocketApi.call(tile, "getStatus")).name().equals("SUCCESS"))
            throw new IllegalStateException("scan_first");
        var hull =
                ShipyardHull.capture(
                        l,
                        box,
                        p.getUUID()); // ownership, size and one flight core before starting a cut
        var j = new Job(pos, p.getUUID());
        j.hull = hull;
        j.cabinSlot = d.berthSlot++;
        CabinChunks.prepare(l, hull, j.cabinSlot);
        d.docks.put(pos, j);
        d.persist(l);
        try {
            OriginalRocketApi.press(tile, p, true);
            if (!(boolean) OriginalRocketApi.call(tile, "isScanning"))
                throw new IllegalStateException("too_small");
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            d.docks.remove(pos);
            d.persist(l);
            throw e;
        }
    }

    static void retry(ServerPlayer player, BlockPos pos) {
        var level = player.serverLevel();
        var d = get(level);
        var j = d.docks.get(pos);
        if (j == null || j.stage != Stage.INSPECT || !j.owner.equals(player.getUUID()))
            throw new IllegalStateException("busy");
        j.problem = "";
        j.stage =
                j.offset != null ? Stage.TRANSFER : j.rocket != null ? Stage.PACKED : Stage.CUTTING;
        d.persist(level);
    }

    private static void tick(TickEvent.LevelTickEvent event) {
        if (event.phase != TickEvent.Phase.START
                || !(event.level instanceof ServerLevel level)
                || !ForgeRegistries.BLOCKS.containsKey(OriginalRocketApi.ASSEMBLER)) return;
        var d = get(level);
        for (var j : List.copyOf(d.docks.values())) {
            if (j.stage == Stage.INSPECT || !level.isLoaded(j.console)) continue;
            try {
                switch (j.stage) {
                    case BUILD -> d.beforeCut(level, j);
                    case CUTTING -> d.reconcileCut(level, j);
                    case PACKED, ASCENT -> d.follow(level, j);
                    case TRANSFER -> d.materialize(level, j);
                    default -> {}
                }
            } catch (Exception | LinkageError ex) {
                j.problem =
                        ex.getMessage() != null && ex.getMessage().matches("[a-z_]{1,40}")
                                ? ex.getMessage()
                                : "inspect";
                j.stage = Stage.INSPECT;
                try {
                    d.persist(level);
                } catch (IllegalStateException disk) {
                    Starbridge.LOGGER.error(
                            "Cannot write shipyard journal; preserving last committed state", disk);
                }
                Starbridge.LOGGER.error(
                        "Original rocket at {} stopped with its saved hull intact: {}",
                        j.console,
                        OriginalRocketApi.error(ex));
            }
        }
    }

    private void beforeCut(ServerLevel l, Job j) throws ReflectiveOperationException {
        Object tile = OriginalRocketApi.builder(l.getBlockEntity(j.console)).orElse(null);
        if (tile == null
                || !(boolean) OriginalRocketApi.call(tile, "isBuilding")
                || !(boolean) OriginalRocketApi.call(tile, "isScanning")) {
            docks.remove(j.console);
            persist(l);
            return;
        }
        if (OriginalRocketApi.number(tile, "getProgress")
                        < 10L * OriginalRocketApi.number(tile, "getTotalProgress")
                || OriginalRocketApi.number(tile, "getEnergyStored")
                        < OriginalRocketApi.number(tile, "getPowerPerOperation")) return;
        try {
            var box = ShipyardHull.pad(OriginalRocketApi.call(tile, "getBBCache"));
            var current = ShipyardHull.capture(l, box, j.owner);
            if (!current.core.equals(j.hull.core)) throw new IllegalStateException("core");
            j.hull = current;
        } catch (RuntimeException | ReflectiveOperationException e) {
            tile.getClass().getMethod("setBuilding", boolean.class).invoke(tile, false);
            docks.remove(j.console);
            persist(l);
            return;
        }
        // Suspend this already-earned native build tick until the atomic manifest exists on disk.
        tile.getClass().getMethod("setBuilding", boolean.class).invoke(tile, false);
        j.stage = Stage.CUTTING;
        persist(l);
        try {
            j.hull.wrap(l);
            Object world =
                    OriginalRocketApi.host(
                            "LegacyContent", "legacyWorld", new Class<?>[] {Level.class}, l);
            j.hull.signature = ShipyardHull.fingerprint(world, j.hull.box);
            persist(l);
            // Run the genuine powered completion tick now. No player or hopper can change the
            // manifest between the snapshot and cut.
            tile.getClass().getMethod("setBuilding", boolean.class).invoke(tile, true);
            OriginalRocketApi.call(tile, "func_145845_h");
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            tile.getClass().getMethod("setBuilding", boolean.class).invoke(tile, false);
            if (j.hull.intactForUnwrap(l)) {
                j.hull.restore(l, BlockPos.ZERO, true);
                docks.remove(j.console);
                persist(l);
            }
            throw e;
        }
        reconcileCut(l, j);
    }

    private void reconcileCut(ServerLevel l, Job j) throws ReflectiveOperationException {
        Object tile = OriginalRocketApi.builder(l.getBlockEntity(j.console)).orElse(null);
        if (tile != null && (boolean) OriginalRocketApi.call(tile, "isBuilding"))
            tile.getClass().getMethod("setBuilding", boolean.class).invoke(tile, false);
        List<Entity> matches = new ArrayList<>();
        for (Entity e :
                l.getEntities(
                        (Entity) null,
                        j.hull.box.area().inflate(3),
                        e ->
                                OriginalRocketApi.CARRIER.equals(
                                        ForgeRegistries.ENTITY_TYPES.getKey(e.getType())))) {
            Object nativeShip = OriginalRocketApi.rocket(e);
            if (nativeShip != null
                    && j.hull.matches(OriginalRocketApi.field(nativeShip, "storage")))
                matches.add(e);
        }
        if (matches.size() == 1 && j.hull.clear(l, BlockPos.ZERO)) {
            Entity carrier = matches.get(0);
            j.rocket = carrier.getUUID();
            j.launchY = carrier.getY();
            j.stage = Stage.PACKED;
            persist(l);
            return;
        }
        if (matches.isEmpty() && j.hull.intactForUnwrap(l)) {
            j.hull.restore(l, BlockPos.ZERO, true);
            docks.remove(j.console);
            persist(l);
            return;
        }
        throw new IllegalStateException("manifest");
    }

    private static boolean alongside(ServerPlayer player, Entity rocket, Job job) {
        if (rocket == null) return false;
        if (rocket.distanceToSqr(player) <= 144) return true;
        // AR reports the centre of a long hull as its entity position. The dock can stand at its
        // bow.
        var hull = job.hull.box.area();
        var centre = hull.getCenter();
        return job.ready()
                && hull.inflate(6).contains(player.position())
                && Math.abs(rocket.getX() - centre.x) <= 4
                && Math.abs(rocket.getZ() - centre.z) <= 4
                && Math.abs(rocket.getY() - job.launchY) <= 2;
    }

    static void board(ServerPlayer p, BlockPos pos) throws ReflectiveOperationException {
        var j = job(p.serverLevel(), pos);
        if (j == null || !j.ready()) throw new IllegalStateException("busy");
        Entity ship = p.serverLevel().getEntity(j.rocket);
        if (!alongside(p, ship, j) || p.isPassenger()) throw new IllegalStateException("approach");
        if (j.owner.equals(p.getUUID())) {
            if (!p.getMainHandItem().isEmpty()) throw new IllegalStateException("hand");
            OriginalRocketApi.passengerAction(OriginalRocketApi.rocket(ship), p, "SENDINTERACT");
            if (!riding(p, pos)) throw new IllegalStateException("seat");
        }
        Vec3 berth = j.owner.equals(p.getUUID()) ? j.hull.crewSeat(0) : CrewSeats.board(j, ship, p);
        j.riders.put(p.getUUID(), berth);
        get(p.serverLevel()).persist(p.serverLevel());
    }

    static void fuel(ServerPlayer p, BlockPos pos) throws ReflectiveOperationException {
        var j = job(p.serverLevel(), pos);
        if (j == null || !j.ready() || !j.owner.equals(p.getUUID()))
            throw new IllegalStateException("busy");
        var ship = p.serverLevel().getEntity(j.rocket);
        if (!alongside(p, ship, j)) throw new IllegalStateException("approach");
        if (p.getMainHandItem().isEmpty()) throw new IllegalStateException("propellant");
        Object original = OriginalRocketApi.rocket(ship);
        int before = OriginalRocketApi.number(original, "getFuelAmount");
        if (before >= OriginalRocketApi.number(original, "getFuelCapacity"))
            throw new IllegalStateException("full");
        OriginalRocketApi.passengerAction(original, p, "SENDINTERACT");
        int after = OriginalRocketApi.number(original, "getFuelAmount");
        if (after <= before) throw new IllegalStateException("propellant");
        if (Boolean.getBoolean("starbridge.shipyardGuiFixture"))
            Starbridge.LOGGER.info("SHIPYARD GUI native propellant {} -> {}", before, after);
    }

    static void launch(ServerPlayer p, BlockPos pos) throws ReflectiveOperationException {
        var l = p.serverLevel();
        var j = job(l, pos);
        if (j == null || !j.ready() || !j.owner.equals(p.getUUID()) || !riding(p, pos))
            throw new IllegalStateException("seat");
        var carrier = l.getEntity(j.rocket);
        Object original = OriginalRocketApi.rocket(carrier);
        if (OriginalRocketApi.number(original, "getFuelAmount") <= 0)
            throw new IllegalStateException("fuel");
        if (!j.hull.matches(OriginalRocketApi.field(original, "storage")))
            throw new IllegalStateException("manifest");
        j.riders.putIfAbsent(j.owner, j.hull.crewSeat(0));
        j.launchY = carrier.getY();
        if (j.cabinSlot >= 0) CabinChunks.prepare(l, j.hull, j.cabinSlot);
        get(l).persist(l);
        OriginalRocketApi.planLocalOrbit(original, p);
        OriginalRocketApi.passengerAction(original, p, "LAUNCH");
        if (!(boolean) OriginalRocketApi.call(original, "isInFlight"))
            throw new IllegalStateException("navigation");
        j.stage = Stage.ASCENT;
        get(l).persist(l);
        if (Boolean.getBoolean("starbridge.shipyardGuiFixture"))
            Starbridge.LOGGER.info(
                    "SHIPYARD GUI witnessed powered launch {} by {}", j.rocket, p.getUUID());
    }

    private void follow(ServerLevel l, Job j) throws Exception {
        var e = l.getEntity(j.rocket);
        if (e == null) return;
        CrewSeats.follow(l, j, e);
        Object nativeShip = OriginalRocketApi.rocket(e);
        if (nativeShip == null) throw new IllegalStateException("manifest");
        if (Boolean.getBoolean("starbridge.shipyardGuiFixture")
                && j.stage == Stage.ASCENT
                && l.getGameTime() % 40 == 0)
            Starbridge.LOGGER.info(
                    "SHIPYARD GUI ascent y={} from {} fuel={} aboard={}",
                    e.getY(),
                    j.launchY,
                    OriginalRocketApi.number(nativeShip, "getFuelAmount"),
                    e.getPassengers().size());
        if (!(boolean) OriginalRocketApi.call(nativeShip, "isInFlight")) return;
        var pilot =
                e.getPassengers().stream()
                        .filter(ServerPlayer.class::isInstance)
                        .map(ServerPlayer.class::cast)
                        .filter(p -> j.owner.equals(p.getUUID()))
                        .findFirst()
                        .orElse(null);
        if (j.stage == Stage.PACKED) {
            if (pilot == null) return;
            j.riders.putIfAbsent(j.owner, j.hull.crewSeat(0));
            j.stage = Stage.ASCENT;
            persist(l);
        }
        if (!j.riders.containsKey(j.owner) || e.getY() < j.launchY + 12) return;
        Object storage = OriginalRocketApi.field(nativeShip, "storage");
        if (!j.hull.matches(storage)) throw new IllegalStateException("manifest");
        j.hull.refreshNativeTiles(storage);
        j.riders.putIfAbsent(j.owner, j.hull.crewSeat(0));
        j.riders
                .entrySet()
                .removeIf(
                        row -> {
                            var crew = l.getServer().getPlayerList().getPlayer(row.getKey());
                            return !row.getKey().equals(j.owner)
                                    && (crew == null
                                            ? !j.seats.containsKey(row.getKey())
                                            : crew.level() != l || !CrewSeats.riding(j, crew));
                        });
        j.offset = reserve(l, j);
        j.stage = Stage.TRANSFER;
        retired.add(j.rocket);
        j.hull.rememberFuel(
                OriginalRocketApi.number(nativeShip, "getFuelAmount"),
                OriginalRocketApi.number(nativeShip, "getFuelCapacity"));
        atomicallyWrite(
                dataDirectory(l).resolve("starbridge-original-ships").resolve(j.rocket + ".nbt"),
                j.write());
        VoyageLog.get(l.getServer())
                .rememberHome(
                        j.hull.core,
                        new Vec3(
                                j.hull.bridge.getX() + .5,
                                Math.min(l.getMaxBuildHeight() - 4, j.hull.box.max().getY() + 24.5),
                                j.hull.bridge.getZ() + .5));
        persist(l);
        materialize(l, j);
    }

    private BlockPos reserve(ServerLevel l, Job job) {
        ShipyardHull hull = job.hull;
        for (int n = 0; n < 8; n++) {
            int slot = n == 0 && job.cabinSlot >= 0 ? job.cabinSlot : berthSlot++;
            BlockPos min = CabinChunks.berthOrigin(l, hull, slot);
            BlockPos off = min.subtract(hull.box.min());
            if (!l.getWorldBorder().isWithinBounds(min)
                    || !l.getWorldBorder().isWithinBounds(hull.box.max().offset(off))) continue;
            if (hull.clear(l, off)
                    && l.getEntities((Entity) null, hull.box.area().move(off), e -> true).isEmpty())
                return off;
        }
        throw new IllegalStateException("occupied");
    }

    private void materialize(ServerLevel l, Job j) {
        var e = l.getEntity(j.rocket);
        var aboardNow = new HashMap<UUID, ServerPlayer>();
        CrewSeats.release(l, j, aboardNow);
        if (e != null) {
            for (Entity rider : e.getPassengers())
                if (rider instanceof ServerPlayer person) aboardNow.put(person.getUUID(), person);
            e.ejectPassengers();
            e.discard();
        }
        ShipyardHull hull = j.hull;
        BlockPos off = j.offset;
        if (hull.clear(l, off)) {
            hull.restore(l, off, false);
        } else if (!hull.matchesRestored(l, off)) throw new IllegalStateException("occupied");
        var core = l.getBlockEntity(hull.bridge.offset(off));
        if (!(core instanceof ShipCoreBlockEntity c)
                || !c.id().equals(hull.core)
                || !j.owner.equals(c.owner())) throw new IllegalStateException("manifest");
        c.bindOriginalRocket(j.rocket);
        c.register();
        OriginalShipyard.bind(c, hull.box.min().offset(off), hull.box.max().offset(off));
        j.riders.forEach(
                (id, point) -> {
                    var t = new CompoundTag();
                    t.putUUID("Core", c.id());
                    t.putUUID("Rocket", j.rocket);
                    t.putLong("Console", c.getBlockPos().asLong());
                    t.putDouble("X", point.x + off.getX());
                    t.putDouble("Y", point.y + off.getY());
                    t.putDouble("Z", point.z + off.getZ());
                    arrivals.put(id, t);
                });
        // Persist chunks and core before forgetting the original manifest. The receipt is replayed
        // only for its own ship.
        CabinChunks.checkpoint(l, hull.box, off);
        docks.remove(j.console);
        persist(l);
        for (UUID id : j.riders.keySet()) {
            var p = aboardNow.getOrDefault(id, l.getServer().getPlayerList().getPlayer(id));
            if (p != null) arrive(l, p);
        }
    }

    private void arrive(ServerLevel l, ServerPlayer p) {
        var t = arrivals.get(p.getUUID());
        if (t == null) return;
        var pos = BlockPos.of(t.getLong("Console"));
        if (!(l.getBlockEntity(pos) instanceof ShipCoreBlockEntity c)
                || !c.id().equals(t.getUUID("Core"))
                || !t.getUUID("Rocket").equals(c.originalRocket())) return;
        p.stopRiding();
        var seat = new Vec3(t.getDouble("X"), t.getDouble("Y"), t.getDouble("Z"));
        if (p.level() == l) p.moveTo(seat.x, seat.y, seat.z, p.getYRot(), p.getXRot());
        p.teleportTo(l, seat.x, seat.y, seat.z, p.getYRot(), p.getXRot());
        if (p.level() != l || p.position().distanceToSqr(seat) > .01) return;
        p.setDeltaMovement(Vec3.ZERO);
        p.fallDistance = 0;
        ShipyardWire.leaveDock(p);
        if (p.getUUID().equals(c.owner())) {
            p.getPersistentData().putUUID("starbridge:selected_core", c.id());
            VoyageNetwork.open(p, c);
        }
        l.getServer().getPlayerList().saveAll();
        arrivals.remove(p.getUUID());
        persist(l);
    }

    private static java.nio.file.Path dataDirectory(ServerLevel l) {
        var root = l.getServer().getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT);
        return net.minecraft.world.level.dimension.DimensionType.getStorageFolder(
                        l.dimension(), root)
                .resolve("data");
    }

    private void persist(ServerLevel level) {
        var envelope = new CompoundTag();
        envelope.put("data", save(new CompoundTag()));
        envelope.putInt(
                "DataVersion",
                net.minecraft.SharedConstants.getCurrentVersion().getDataVersion().getVersion());
        atomicallyWrite(dataDirectory(level).resolve("starbridge_original_shipyard.dat"), envelope);
        setDirty(false);
    }

    private static void atomicallyWrite(java.nio.file.Path target, CompoundTag contents) {
        java.nio.file.Path pending = null;
        try {
            java.nio.file.Files.createDirectories(target.getParent());
            pending =
                    java.nio.file.Files.createTempFile(target.getParent(), "sb-cabin-", ".pending");
            NbtIo.writeCompressed(contents, pending.toFile());
            try (var ch =
                    java.nio.channels.FileChannel.open(
                            pending, java.nio.file.StandardOpenOption.WRITE)) {
                ch.force(true);
            }
            java.nio.file.Files.move(
                    pending,
                    target,
                    java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } catch (java.io.IOException fail) {
            throw new IllegalStateException("disk", fail);
        } finally {
            if (pending != null)
                try {
                    java.nio.file.Files.deleteIfExists(pending);
                } catch (java.io.IOException ex) {
                    Starbridge.LOGGER.warn("Uncommitted shipyard temporary file {}", pending);
                }
        }
    }

    @Override
    public CompoundTag save(CompoundTag t) {
        var a = new ListTag();
        for (Job j : docks.values()) a.add(j.write());
        t.put("Docks", a);
        t.putInt("Next", berthSlot);
        a = new ListTag();
        for (UUID id : retired) a.add(NbtUtils.createUUID(id));
        t.put("Retired", a);
        var players = new CompoundTag();
        arrivals.forEach((id, n) -> players.put(id.toString(), n));
        t.put("Arrivals", players);
        return t;
    }

    private static RocketTransfer load(CompoundTag t) {
        var d = new RocketTransfer();
        for (Tag r : t.getList("Docks", Tag.TAG_COMPOUND)) {
            Job j = Job.read((CompoundTag) r);
            d.docks.put(j.console, j);
        }
        d.berthSlot = t.getInt("Next");
        for (Tag a : t.getList("Retired", Tag.TAG_INT_ARRAY)) d.retired.add(NbtUtils.loadUUID(a));
        var players = t.getCompound("Arrivals");
        for (String key : players.getAllKeys())
            d.arrivals.put(UUID.fromString(key), players.getCompound(key));
        return d;
    }
}
