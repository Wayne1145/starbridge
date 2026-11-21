package dev.starbridge.compat.rocketry;

import dev.starbridge.block.ShipCoreBlockEntity;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.*;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;

import java.lang.reflect.Method;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.UUID;

/**
 * The native machine cuts a tight bounding box. Preserve the host states its 1.7 inventory proxies
 * cannot serialize.
 */
final class ShipyardHull {
    record Box(BlockPos min, BlockPos max) {
        Box {
            int x = max.getX() - min.getX() + 1,
                    y = max.getY() - min.getY() + 1,
                    z = max.getZ() - min.getZ() + 1;
            if (x < 1
                    || x > 300
                    || z < 1
                    || z > 300
                    || y < 1
                    || y > 64
                    || (long) x * y * z > 2_025_000) throw new IllegalArgumentException("bounds");
        }

        int sx() {
            return max.getX() - min.getX() + 1;
        }

        int sy() {
            return max.getY() - min.getY() + 1;
        }

        int sz() {
            return max.getZ() - min.getZ() + 1;
        }

        Iterable<BlockPos> cells() {
            return BlockPos.betweenClosed(min, max);
        }

        AABB area() {
            return new AABB(
                    min.getX(),
                    min.getY(),
                    min.getZ(),
                    max.getX() + 1,
                    max.getY() + 1,
                    max.getZ() + 1);
        }
    }

    final Box box;
    final ListTag cells;
    final BlockPos bridge;
    final UUID core;
    byte[] signature;

    ShipyardHull(Box box, ListTag cells, BlockPos bridge, UUID core, byte[] signature) {
        this.box = box;
        this.cells = cells;
        this.bridge = bridge;
        this.core = core;
        this.signature = signature;
    }

    static Box pad(Object a) throws ReflectiveOperationException {
        if (a == null) return null;
        return new Box(
                new BlockPos(
                        axis(a, "field_72340_a", "minX"),
                        axis(a, "field_72338_b", "minY"),
                        axis(a, "field_72339_c", "minZ")),
                new BlockPos(
                        axis(a, "field_72336_d", "maxX"),
                        axis(a, "field_72337_e", "maxY"),
                        axis(a, "field_72334_f", "maxZ")));
    }

    private static int axis(Object a, String runtime, String mapped)
            throws ReflectiveOperationException {
        try {
            return ((Number) OriginalRocketApi.field(a, runtime)).intValue();
        } catch (NoSuchFieldException e) {
            return ((Number) OriginalRocketApi.field(a, mapped)).intValue();
        }
    }

    static ShipyardHull capture(ServerLevel level, Box pad, UUID owner) {
        if (pad == null) throw new IllegalStateException("pad");
        ListTag blocks = new ListTag();
        BlockPos min = pad.max(), max = pad.min(), bridge = null;
        UUID core = null;
        long bytes = 0;
        for (BlockPos p : pad.cells()) {
            if (!level.isLoaded(p)) throw new IllegalStateException("chunks");
            BlockState state = level.getBlockState(p);
            if (state.isAir()) continue;
            min =
                    new BlockPos(
                            Math.min(min.getX(), p.getX()),
                            Math.min(min.getY(), p.getY()),
                            Math.min(min.getZ(), p.getZ()));
            max =
                    new BlockPos(
                            Math.max(max.getX(), p.getX()),
                            Math.max(max.getY(), p.getY()),
                            Math.max(max.getZ(), p.getZ()));
            var row = new CompoundTag();
            row.putLong("P", p.asLong());
            row.put("State", NbtUtils.writeBlockState(state));
            var tile = level.getBlockEntity(p);
            if (tile instanceof ShipCoreBlockEntity console) {
                if (bridge != null || !owner.equals(console.owner()))
                    throw new IllegalStateException("owner");
                bridge = p.immutable();
                core = console.id();
            }
            if (tile != null) {
                row.put("Tile", tile.saveWithFullMetadata());
                if (!tile.getClass().getName().equals("retrofit.forge.world.LegacyBlockEntity"))
                    row.putBoolean("Host", true);
            }
            bytes += row.sizeInBytes();
            if (bytes > 64 * 1024 * 1024L) throw new IllegalStateException("size");
            blocks.add(row);
        }
        if (bridge == null) throw new IllegalStateException("core");
        return new ShipyardHull(new Box(min, max), blocks, bridge, core, new byte[0]);
    }

    private static final int FLAGS =
            Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE | Block.UPDATE_SUPPRESS_DROPS;

    void wrap(ServerLevel level) {
        for (Tag entry : cells) {
            CompoundTag row = (CompoundTag) entry;
            if (!row.getBoolean("Host")) continue;
            var pos = BlockPos.of(row.getLong("P"));
            level.removeBlockEntity(pos);
            if (!level.setBlock(pos, Blocks.IRON_BLOCK.defaultBlockState(), FLAGS))
                throw new IllegalStateException("packing");
        }
    }

    boolean clear(ServerLevel level, BlockPos off) {
        for (BlockPos original : box.cells())
            if (!level.getBlockState(original.offset(off)).isAir()) return false;
        return true;
    }
    /** Re-entry only fills cells which are empty or already belong to this exact restoration. */
    void restore(ServerLevel level, BlockPos off, boolean unwind) {
        java.util.ArrayList<CompoundTag> writes = new java.util.ArrayList<>();
        for (Tag entry : cells) {
            var row = (CompoundTag) entry;
            if (unwind && !row.getBoolean("Host")) continue;
            var p = BlockPos.of(row.getLong("P")).offset(off);
            var state =
                    NbtUtils.readBlockState(
                            level.registryAccess().lookupOrThrow(Registries.BLOCK),
                            row.getCompound("State"));
            var present = level.getBlockState(p);
            if (unwind && present.equals(state) && level.getBlockEntity(p) != null) continue;
            if (unwind ? !present.is(Blocks.IRON_BLOCK) : !present.isAir())
                throw new IllegalStateException("occupied");
            writes.add(row);
        }
        // Original connected fuel tanks derive their metadata from adjacent tanks when placed.
        // Reapply states only after all neighbours exist.
        for (CompoundTag row : writes) {
            var p = BlockPos.of(row.getLong("P")).offset(off);
            var state =
                    NbtUtils.readBlockState(
                            level.registryAccess().lookupOrThrow(Registries.BLOCK),
                            row.getCompound("State"));
            level.setBlock(p, state, FLAGS);
            if (level.getBlockState(p).getBlock() != state.getBlock())
                throw new IllegalStateException("restore");
        }
        for (CompoundTag row : writes) {
            var p = BlockPos.of(row.getLong("P")).offset(off);
            var state =
                    NbtUtils.readBlockState(
                            level.registryAccess().lookupOrThrow(Registries.BLOCK),
                            row.getCompound("State"));
            if (!level.getBlockState(p).equals(state)) level.setBlock(p, state, FLAGS);
            if (!level.getBlockState(p).equals(state)) {
                dev.starbridge.Starbridge.LOGGER.error(
                        "Cabin cell {} expected {} found {}", p, state, level.getBlockState(p));
                throw new IllegalStateException("restore");
            }
            if (row.contains("Tile", Tag.TAG_COMPOUND)) {
                var snapshot = row.getCompound("Tile").copy();
                snapshot.putInt("x", p.getX());
                snapshot.putInt("y", p.getY());
                snapshot.putInt("z", p.getZ());
                var tile = level.getBlockEntity(p);
                if (tile == null) throw new IllegalStateException("restore");
                tile.load(snapshot);
                tile.setChanged();
            }
        }
    }

    private static Method method(Object target, String name, String fallback, Class<?>... args)
            throws ReflectiveOperationException {
        try {
            return target.getClass().getMethod(name, args);
        } catch (NoSuchMethodException e) {
            return target.getClass().getMethod(fallback, args);
        }
    }

    static byte[] fingerprint(Object source, Box coords) throws ReflectiveOperationException {
        final MessageDigest md;
        try {
            md = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new AssertionError(e);
        }
        Method block = method(source, "func_147439_a", "getBlock", int.class, int.class, int.class);
        Method meta =
                method(source, "func_72805_g", "getBlockMetadata", int.class, int.class, int.class);
        Class<?> kind = block.getReturnType();
        Method id;
        try {
            id = kind.getMethod("func_149682_b", kind);
        } catch (NoSuchMethodException e) {
            id = kind.getMethod("getIdFromBlock", kind);
        }
        for (BlockPos p : coords.cells()) {
            int x = p.getX(), y = p.getY(), z = p.getZ();
            Object value = block.invoke(source, x, y, z);
            int n = ((Number) id.invoke(null, value)).intValue();
            md.update((byte) (n >>> 24));
            md.update((byte) (n >>> 16));
            md.update((byte) (n >>> 8));
            md.update((byte) n);
            md.update(((Number) meta.invoke(source, x, y, z)).byteValue());
        }
        return md.digest();
    }

    boolean matches(Object storage) throws ReflectiveOperationException {
        if (storage == null
                || OriginalRocketApi.number(storage, "getSizeX") != box.sx()
                || OriginalRocketApi.number(storage, "getSizeY") != box.sy()
                || OriginalRocketApi.number(storage, "getSizeZ") != box.sz()) return false;
        return Arrays.equals(
                signature,
                fingerprint(
                        storage,
                        new Box(
                                BlockPos.ZERO,
                                new BlockPos(box.sx() - 1, box.sy() - 1, box.sz() - 1))));
    }

    void refreshNativeTiles(Object storage)
            throws ReflectiveOperationException, java.io.IOException {
        Method tile =
                method(storage, "func_147438_o", "getTileEntity", int.class, int.class, int.class);
        for (Tag entry : cells) {
            var row = (CompoundTag) entry;
            if (row.getBoolean("Host") || !row.contains("Tile")) continue;
            var p = BlockPos.of(row.getLong("P")).subtract(box.min());
            Object packed = tile.invoke(storage, p.getX(), p.getY(), p.getZ());
            var host = row.getCompound("Tile");
            if (!host.contains("legacy")) continue;
            if (packed == null) throw new IllegalStateException("manifest");
            byte[] bytes =
                    (byte[])
                            OriginalRocketApi.host(
                                    "LegacyBridge", "save", new Class<?>[] {Object.class}, packed);
            var original =
                    NbtIo.read(
                            new java.io.DataInputStream(new java.io.ByteArrayInputStream(bytes)));
            if (original == null
                    || !original.getString("id").equals(host.getCompound("legacy").getString("id")))
                throw new IllegalStateException("manifest");
            host.put("legacy", original);
        }
    }

    boolean intactForUnwrap(ServerLevel level) {
        for (Tag entry : cells) {
            var row = (CompoundTag) entry;
            var pos = BlockPos.of(row.getLong("P"));
            var current = level.getBlockState(pos);
            var expected =
                    NbtUtils.readBlockState(
                            level.registryAccess().lookupOrThrow(Registries.BLOCK),
                            row.getCompound("State"));
            if (!current.equals(expected)
                    && !(row.getBoolean("Host") && current.is(Blocks.IRON_BLOCK))) return false;
        }
        return true;
    }

    boolean matchesRestored(ServerLevel level, BlockPos off) {
        if (!(level.getBlockEntity(bridge.offset(off)) instanceof ShipCoreBlockEntity actual)
                || !actual.id().equals(core)) return false;
        for (Tag e : cells) {
            var row = (CompoundTag) e;
            var pos = BlockPos.of(row.getLong("P")).offset(off);
            var state =
                    NbtUtils.readBlockState(
                            level.registryAccess().lookupOrThrow(Registries.BLOCK),
                            row.getCompound("State"));
            if (!level.getBlockState(pos).equals(state)) return false;
            if (row.contains("Tile")) {
                var actualTile = level.getBlockEntity(pos);
                if (actualTile == null) return false;
                var saved = row.getCompound("Tile").copy();
                var actualTag = actualTile.saveWithFullMetadata();
                for (String field : java.util.List.of("x", "y", "z")) {
                    saved.remove(field);
                    actualTag.remove(field);
                    if (saved.contains("legacy")) saved.getCompound("legacy").remove(field);
                    if (actualTag.contains("legacy")) actualTag.getCompound("legacy").remove(field);
                }
                if (!saved.equals(actualTag)) return false;
            }
        }
        return true;
    }

    java.util.List<net.minecraft.world.phys.Vec3> seatPositions() {
        return cells.stream()
                .map(e -> (CompoundTag) e)
                .filter(
                        row ->
                                "advancedrocketry:seat"
                                        .equals(row.getCompound("State").getString("Name")))
                .map(row -> BlockPos.of(row.getLong("P")))
                .sorted(
                        java.util.Comparator.<BlockPos>comparingInt(p -> p.getY())
                                .thenComparingInt(BlockPos::getX)
                                .thenComparingInt(BlockPos::getZ))
                .map(
                        p ->
                                new net.minecraft.world.phys.Vec3(
                                        p.getX() + .5, p.getY() + .5, p.getZ() + .5))
                .toList();
    }

    net.minecraft.world.phys.Vec3 crewSeat(int index) {
        var seats = seatPositions();
        if (index >= 0 && index < seats.size()) return seats.get(index);
        throw new IllegalStateException("seat");
    }

    void rememberFuel(int amount, int capacity) {
        for (Tag e : cells) {
            var row = (CompoundTag) e;
            if (row.getLong("P") == bridge.asLong()) {
                var tile = row.getCompound("Tile");
                var data = tile.getCompound("ForgeData");
                data.putInt("starbridge:original_fuel", amount);
                data.putInt("starbridge:original_capacity", capacity);
                tile.put("ForgeData", data);
                return;
            }
        }
    }

    CompoundTag save() {
        var t = new CompoundTag();
        t.putLong("Min", box.min().asLong());
        t.putLong("Max", box.max().asLong());
        t.putLong("Bridge", bridge.asLong());
        t.putUUID("Core", core);
        t.put("Cells", cells);
        t.putByteArray("Signature", signature);
        return t;
    }

    static ShipyardHull load(CompoundTag t) {
        return new ShipyardHull(
                new Box(BlockPos.of(t.getLong("Min")), BlockPos.of(t.getLong("Max"))),
                t.getList("Cells", Tag.TAG_COMPOUND),
                BlockPos.of(t.getLong("Bridge")),
                t.getUUID("Core"),
                t.getByteArray("Signature"));
    }
}
