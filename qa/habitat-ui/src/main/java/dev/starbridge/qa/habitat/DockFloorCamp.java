package dev.starbridge.qa.habitat;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import net.minecraftforge.registries.ForgeRegistries;

import java.nio.file.Path;

/** A prepared dock with one missing floor tile, a separate acceptable docking pad and one spare. */
final class DockFloorCamp {
    static final Path MARKER = Path.of("starbridge-pad-floor.fixture");
    static final BlockPos CONSOLE = new BlockPos(62, 149, 59);
    static final BlockPos GAP = new BlockPos(63, 148, 61);
    static final BlockPos LANDING = new BlockPos(61, 148, 62);
    private static boolean prepared;

    static Block original(String id) {
        var key = ResourceLocation.parse("advancedrocketry:" + id);
        if (!ForgeRegistries.BLOCKS.containsKey(key)) throw new IllegalStateException("Missing " + key);
        return ForgeRegistries.BLOCKS.getValue(key);
    }

    static void prepare(ServerLevel level, ServerPlayer player) {
        if (prepared) return;
        if (player.isCreative() || player.isSpectator() || !player.getInventory().isEmpty()
                || player.server.getWorldData().getAllowCommands()) throw new IllegalStateException("Expected fresh survival");
        for (var pos : BlockPos.betweenClosed(58, 147, 56, 66, 155, 65)) {
            var state = pos.getY() == 147 || pos.getY() == 148 && pos.getX() >= 64
                    ? Blocks.SMOOTH_STONE.defaultBlockState() : Blocks.AIR.defaultBlockState();
            level.setBlock(pos, state, 2);
        }
        var pad = original("launchpad");
        for (var pos : BlockPos.betweenClosed(61, 148, 60, 63, 148, 62)) {
            if (!pos.equals(GAP)) level.setBlock(pos, pos.equals(LANDING)
                    ? original("tile.dockingpad").defaultBlockState() : pad.defaultBlockState(), 2);
        }
        for (int y = 148; y < 154; y++) level.setBlock(new BlockPos(60, y, 61), original("structuretower").defaultBlockState(), 2);
        var builder = original("rocketbuilder").defaultBlockState();
        level.setBlock(CONSOLE, builder.setValue((IntegerProperty) builder.getBlock().getStateDefinition().getProperty("meta"), 2), 3);
        player.getInventory().setItem(0, new ItemStack(pad));
        player.teleportTo(level, 64.5, 149, 61.15, 135, 15);
        prepared = true;
    }
}
