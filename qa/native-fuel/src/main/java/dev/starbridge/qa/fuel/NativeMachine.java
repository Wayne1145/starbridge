package dev.starbridge.qa.fuel;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import net.minecraftforge.registries.ForgeRegistries;

import java.lang.reflect.Array;
import java.util.ArrayList;
import java.util.List;

/**
 * Builds the pinned mod's own multiblock plan in the disposable factory. All processing happens in
 * the native tiles.
 */
final class NativeMachine {
    record Site(
            BlockPos controller,
            List<BlockPos> inputs,
            List<BlockPos> outputs,
            BlockPos generator) {}

    static Site place(ServerLevel level, String controllerId, BlockPos center)
            throws ReflectiveOperationException {
        set(level, center, block(controllerId), 2);
        Object controller = tile(level, center);
        Object[][][] plan =
                (Object[][][]) controller.getClass().getMethod("getStructure").invoke(controller);
        int[] anchor = null;
        for (int y = 0; y < plan.length; y++)
            for (int z = 0; z < plan[y].length; z++) {
                for (int x = 0; x < plan[y][z].length; x++)
                    if (Character.valueOf('c').equals(plan[y][z][x])) anchor = new int[] {x, y, z};
            }
        if (anchor == null)
            throw new IllegalStateException(
                    "Controller missing from original " + controllerId + " plan");
        Object front = controller.getClass().getMethod("getFrontDirection").invoke(controller);
        int dx = front.getClass().getField("offsetX").getInt(front),
                dz = front.getClass().getField("offsetZ").getInt(front);
        List<BlockPos> inputs = new ArrayList<>(),
                outputs = new ArrayList<>(),
                plugs = new ArrayList<>();
        var allowed = controller.getClass().getMethod("getAllowableBlocks", Object.class);
        var toHost =
                Class.forName("retrofit.forge.world.LegacyContent")
                        .getMethod("byLegacy", Object.class);
        for (int y = 0; y < plan.length; y++)
            for (int z = 0; z < plan[y].length; z++) {
                for (int x = 0; x < plan[y][z].length; x++) {
                    Object cell = plan[y][z][x];
                    if (cell == null || Character.valueOf('c').equals(cell)) continue;
                    var pos =
                            center.offset(
                                    (x - anchor[0]) * dz - (z - anchor[2]) * dx,
                                    anchor[1] - y,
                                    -(x - anchor[0]) * dx - (z - anchor[2]) * dz);
                    var choices = (List<?>) allowed.invoke(controller, cell);
                    if (choices.isEmpty())
                        throw new IllegalStateException("No registered block for " + cell);
                    var choice = choices.get(0);
                    Object legacy = choice.getClass().getMethod("getBlock").invoke(choice);
                    int meta = (int) choice.getClass().getMethod("getMeta").invoke(choice);
                    var hosted = (Block) toHost.invoke(null, legacy);
                    if (hosted == null)
                        throw new IllegalStateException("No hosted block for " + cell);
                    set(level, pos, hosted, meta);
                    if (Character.valueOf('L').equals(cell)) inputs.add(pos);
                    if (Character.valueOf('l').equals(cell)) outputs.add(pos);
                    if (Character.valueOf('P').equals(cell)) plugs.add(pos);
                }
            }
        if (!Boolean.TRUE.equals(
                controller.getClass().getMethod("attemptCompleteStructure").invoke(controller)))
            throw new IllegalStateException(
                    "The original multiblock rejected its prepared plan: " + controllerId);
        var generator = plugs.get(0).above();
        if (!level.isEmptyBlock(generator)) generator = plugs.get(0).below();
        if (!level.isEmptyBlock(generator))
            throw new IllegalStateException("No free generator position at " + plugs.get(0));
        set(level, generator, block("libvulpes:tile.coalgenerator"), 0);
        return new Site(center, List.copyOf(inputs), List.copyOf(outputs), generator);
    }

    static Block block(String id) {
        return ForgeRegistries.BLOCKS.getDelegate(new ResourceLocation(id)).orElseThrow().get();
    }

    static Object tile(ServerLevel level, BlockPos p) throws ReflectiveOperationException {
        var wrapper = level.getBlockEntity(p);
        if (wrapper == null)
            throw new IllegalStateException("No wrapped native tile at " + p.toShortString());
        return wrapper.getClass().getMethod("legacyTile").invoke(wrapper);
    }

    static int amount(ServerLevel level, BlockPos p) throws ReflectiveOperationException {
        Object tile = tile(level, p);
        Class<?> direction =
                tile.getClass()
                        .getClassLoader()
                        .loadClass("net.minecraftforge.common.util.ForgeDirection");
        var tanks =
                tile.getClass()
                        .getMethod("getTankInfo", direction)
                        .invoke(tile, direction.getField("UNKNOWN").get(null));
        Object tank = Array.get(tanks, 0), fluid = tank.getClass().getField("fluid").get(tank);
        return fluid == null ? 0 : fluid.getClass().getField("amount").getInt(fluid);
    }

    static void set(ServerLevel level, BlockPos pos, Block block, int meta) {
        var state = block.defaultBlockState();
        if (block.getStateDefinition().getProperty("meta") instanceof IntegerProperty prop)
            state = state.setValue(prop, meta);
        level.setBlockAndUpdate(pos, state);
    }

    private NativeMachine() {}
}
