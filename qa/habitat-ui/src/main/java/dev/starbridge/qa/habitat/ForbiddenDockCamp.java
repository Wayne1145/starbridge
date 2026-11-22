package dev.starbridge.qa.habitat;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BarrelBlockEntity;

import java.nio.file.Files;
import java.nio.file.Path;

/** Adds one configured chest and an allowed cargo barrel to the existing hosted survival dock. */
final class ForbiddenDockCamp {
    static final Path MARKER = Path.of("starbridge-forbidden-ui.fixture");
    static final BlockPos CHEST = new BlockPos(63, 151, 60), BARREL = new BlockPos(61, 149, 60);
    private static boolean prepared;

    static void prepare(ServerLevel level, ServerPlayer captain) throws Exception {
        if (prepared) return;
        DockFloorCamp.prepare(level, captain);
        level.setBlock(DockFloorCamp.GAP, DockFloorCamp.original("launchpad").defaultBlockState(), 2);
        level.setBlock(CHEST, Blocks.CHEST.defaultBlockState(), 2);
        level.setBlock(BARREL, Blocks.BARREL.defaultBlockState(), 2);
        ((BarrelBlockEntity) level.getBlockEntity(BARREL)).setItem(0, new ItemStack(Items.DIAMOND, 7));
        captain.getInventory().setItem(0, new ItemStack(Items.DIAMOND_AXE));
        var host = level.getBlockEntity(DockFloorCamp.CONSOLE);
        var builder = host.getClass().getMethod("legacyTile").invoke(host);
        builder.getClass().getMethod("setEnergyStored", int.class).invoke(builder, 100000);
        Files.writeString(Path.of("forbidden-camp-ready"), "ready");
        prepared = true;
    }
}
