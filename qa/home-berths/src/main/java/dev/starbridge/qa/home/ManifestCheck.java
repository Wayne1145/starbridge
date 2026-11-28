package dev.starbridge.qa.home;

import dev.starbridge.compat.vs.VsShipBackend.StorageBounds;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.StringTagVisitor;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.BarrelBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BarrelBlockEntity;
import net.minecraft.world.level.block.state.BlockState;

import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

/** Check the pre-existing journal digest and recover changed inventories across chunk and section edges. */
final class ManifestCheck {
    private ManifestCheck() {}

    private static Method method(Class<?> type, String name, Class<?>... parameters) throws ReflectiveOperationException {
        Method result = type.getDeclaredMethod(name, parameters);
        result.setAccessible(true);
        return result;
    }

    // The original digest visits chunk X, chunk Z, Y, Z then X. Only these six occupied cells contribute.
    private static String expectedDigest(ServerLevel level, StorageBounds box, List<BlockPos> cells) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        for (BlockPos p : cells) {
            BlockState state = level.getBlockState(p);
            digest.update(ByteBuffer.allocate(16).putInt(p.getX() - box.minX()).putInt(p.getY() - box.minY())
                    .putInt(p.getZ() - box.minZ()).putInt(Block.getId(state)).array());
            if (state.hasBlockEntity()) {
                byte[] bytes = new StringTagVisitor().visit(level.getBlockEntity(p).saveWithFullMetadata())
                        .getBytes(StandardCharsets.UTF_8);
                digest.update(ByteBuffer.allocate(4).putInt(bytes.length).array());
                digest.update(bytes);
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static BarrelBlockEntity barrel(ServerLevel level, BlockPos p) {
        if (level.getBlockEntity(p) instanceof BarrelBlockEntity barrel) return barrel;
        throw new IllegalStateException("Expected test barrel at " + p);
    }

    private static void contents(ServerLevel level, BlockPos p, Item expected, int count) {
        ItemStack stack = barrel(level, p).getItem(0);
        if (!stack.is(expected) || stack.getCount() != count) throw new IllegalStateException("Unexpected hold at " + p);
    }

    static Map<String, Object> run(ServerLevel source, ServerLevel target) throws Exception {
        // Negative X/Z, an empty section above the cargo, and both sides of a region of four chunks.
        BlockPos a = new BlockPos(-17, 190, -17), b = new BlockPos(-17, 191, -16),
                c = new BlockPos(-16, 192, -17), d = new BlockPos(-16, 192, -16);
        BlockPos unaffected = new BlockPos(-18, 190, -17);
        StorageBounds box = new StorageBounds(-17, 190, -17, -15, 211, -15);
        List<BlockPos> cells = List.of(a, b, b.above(), c, d, d.above());
        for (BlockPos p : List.of(a, b, c, d, unaffected)) {
            for (ServerLevel level : List.of(source, target)) {
                level.setBlockAndUpdate(p, Blocks.BARREL.defaultBlockState());
                barrel(level, p).setItem(0, new ItemStack(level == source ? Items.DIAMOND : Items.IRON_INGOT, 7));
            }
        }
        source.setBlockAndUpdate(b.above(), Blocks.IRON_BLOCK.defaultBlockState());
        source.setBlockAndUpdate(d.above(), Blocks.COPPER_BLOCK.defaultBlockState());
        String expected = expectedDigest(source, box, cells);
        Class<?> type = Class.forName("dev.starbridge.transit.HullManifest");
        Object departure = method(type, "capture", ServerLevel.class, StorageBounds.class).invoke(null, source, box);
        Object seal = method(departure.getClass(), "seal").invoke(departure);
        String observed = (String) method(seal.getClass(), "digest").invoke(seal);
        Map<?, ?> machines = (Map<?, ?>) method(departure.getClass(), "machines").invoke(departure);
        if (!expected.equals(observed) || !Integer.valueOf(6).equals(method(seal.getClass(), "blocks").invoke(seal))
                || !machines.keySet().equals(java.util.Set.of(a,b,c,d))) throw new IllegalStateException("Journal digest or cargo capture changed");

        // A has moved, B still occupies its source, C changed type and only D's facing changed in transit.
        for (BlockPos p : List.of(a,c,d)) source.removeBlock(p, false);
        target.setBlockAndUpdate(c, Blocks.GOLD_BLOCK.defaultBlockState());
        target.setBlockAndUpdate(d, Blocks.BARREL.defaultBlockState().setValue(BarrelBlock.FACING, Blocks.BARREL.defaultBlockState().getValue(BarrelBlock.FACING).getOpposite()));
        if (source.getBlockState(b).equals(target.getBlockState(d))) throw new IllegalStateException("Test needs different facings");
        method(type, "restoreMovedMachines", ServerLevel.class, ServerLevel.class, Map.class).invoke(null,source,target,machines);
        contents(target,a,Items.DIAMOND,7);
        contents(target,b,Items.IRON_INGOT,7);
        contents(source,b,Items.DIAMOND,7);
        if (!target.getBlockState(c).is(Blocks.GOLD_BLOCK)) throw new IllegalStateException("Replaced destination");
        contents(target,d,Items.IRON_INGOT,7);
        contents(target,unaffected,Items.IRON_INGOT,7);
        return Map.of("blocks", cells.size(), "capturedMachines", machines.size(), "digest", observed,
                "oldFormatMatches", true, "outsideIgnored", true, "preservedSource", true, "changedTargetsProtected", true,
                "restoredDiamonds", 7);
    }
}
