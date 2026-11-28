package dev.starbridge.qa.habitat;

import com.google.gson.Gson;

import dev.starbridge.voyage.VoyageModule;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SugarCaneBlock;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.level.BlockEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;

/**
 * Builds a fresh public fixture from seven crop/material numbers. Growth after planting is vanilla.
 */
@Mod.EventBusSubscriber(modid = "starbridgehabitatqa")
public final class GardenCamp {
    static final Path MARKER = Path.of("starbridge-garden.fixture");
    static final BlockPos CENTER = new BlockPos(62, 149, 62);
    static final BlockPos EAST = CENTER.east(), WEST = CENTER.west();
    static final BlockPos CHEST = CENTER.offset(-2, 0, -1), TABLE = CENTER.north(2);
    private static UUID gardener;
    private static long planted;
    private static boolean grew, westCut, eastCut;
    private static int papers, terminals;

    private record Inputs(
            int eastAge,
            int westAge,
            int iron,
            int redstone,
            int compass,
            int panes,
            int randomTickSpeed) {}

    private GardenCamp() {}

    static void observe(ServerLevel world, ServerPlayer player) throws Exception {
        if (gardener == null) {
            Inputs inputs = new Gson().fromJson(Files.readString(MARKER), Inputs.class);
            require(
                    inputs.equals(new Inputs(12, 4, 7, 6, 1, 16, 3)),
                    "Unexpected checkpoint input");
            require(
                    player.getInventory().isEmpty()
                            && !player.isCreative()
                            && !player.isSpectator()
                            && !world.getServer().getWorldData().getAllowCommands(),
                    "Expected an empty survival starter");
            require(
                    world.getGameRules().getInt(GameRules.RULE_RANDOMTICKING) == 3,
                    "Expected ordinary vanilla crop progress");
            for (int x = -4; x <= 4; x++)
                for (int z = -4; z <= 4; z++) {
                    world.setBlockAndUpdate(
                            CENTER.offset(x, -2, z), Blocks.BIRCH_PLANKS.defaultBlockState());
                    world.setBlockAndUpdate(
                            CENTER.offset(x, -1, z), Blocks.SEA_LANTERN.defaultBlockState());
                    for (int y = 0; y <= 4; y++) {
                        var block =
                                Math.abs(x) == 4 || Math.abs(z) == 4 || y == 4
                                        ? Blocks.GLASS
                                        : Blocks.AIR;
                        world.setBlockAndUpdate(CENTER.offset(x, y, z), block.defaultBlockState());
                    }
                }
            world.setBlockAndUpdate(EAST.below(), Blocks.DIRT.defaultBlockState());
            world.setBlockAndUpdate(WEST.below(), Blocks.DIRT.defaultBlockState());
            world.setBlockAndUpdate(CENTER.below(), Blocks.WATER.defaultBlockState());
            world.setBlockAndUpdate(CHEST, Blocks.CHEST.defaultBlockState());
            world.setBlockAndUpdate(TABLE, Blocks.CRAFTING_TABLE.defaultBlockState());
            var chest = (ChestBlockEntity) world.getBlockEntity(CHEST);
            chest.setItem(3, new ItemStack(Items.COMPASS, inputs.compass()));
            chest.setItem(21, new ItemStack(Items.GLASS_PANE, inputs.panes()));
            chest.setChanged();
            player.getInventory().setItem(9, new ItemStack(Items.IRON_INGOT, inputs.iron()));
            player.getInventory().setItem(11, new ItemStack(Items.REDSTONE, inputs.redstone()));
            player.teleportTo(world, 62.5, 149, 61.5, 0, 10);
            world.setBlockAndUpdate(
                    EAST,
                    Blocks.SUGAR_CANE
                            .defaultBlockState()
                            .setValue(SugarCaneBlock.AGE, inputs.eastAge()));
            world.setBlockAndUpdate(
                    WEST,
                    Blocks.SUGAR_CANE
                            .defaultBlockState()
                            .setValue(SugarCaneBlock.AGE, inputs.westAge()));
            require(
                    world.isEmptyBlock(EAST.above()) && world.isEmptyBlock(WEST.above()),
                    "Both upper spaces must start empty");
            planted = world.getGameTime();
            gardener = player.getUUID();
            save(
                    "garden-start",
                    Map.of(
                            "inputs",
                            inputs,
                            "initialInventoryEmpty",
                            true,
                            "upperSpacesEmpty",
                            true,
                            "plantedTick",
                            planted));
        }
        if (!grew && (world.getGameTime() - planted) % 1200 == 0) {
            save(
                    "garden-progress",
                    Map.of(
                            "elapsedTicks",
                            world.getGameTime() - planted,
                            "eastAge",
                            world.getBlockState(EAST).getValue(SugarCaneBlock.AGE),
                            "westAge",
                            world.getBlockState(WEST).getValue(SugarCaneBlock.AGE)));
        }
        if (!grew
                && (world.getBlockState(EAST.above()).is(Blocks.SUGAR_CANE)
                        || world.getBlockState(WEST.above()).is(Blocks.SUGAR_CANE))) {
            grew = true;
            save(
                    "garden-growth",
                    Map.of(
                            "plantedTick",
                            planted,
                            "grewAtTick",
                            world.getGameTime(),
                            "randomTickSpeed",
                            world.getGameRules().getInt(GameRules.RULE_RANDOMTICKING),
                            "east",
                            world.getBlockState(EAST.above()).is(Blocks.SUGAR_CANE),
                            "west",
                            world.getBlockState(WEST.above()).is(Blocks.SUGAR_CANE)));
        }
        if (grew
                && eastCut
                && westCut
                && papers == 3
                && terminals == 1
                && !Files.exists(Path.of("garden-server.json"))) {
            var chest = (ChestBlockEntity) world.getBlockEntity(CHEST);
            int stored = 0;
            for (int i = 0; i < chest.getContainerSize(); i++)
                if (chest.getItem(i).is(VoyageModule.SLATE.get()))
                    stored += chest.getItem(i).getCount();
            if (stored == 1)
                save(
                        "garden-server",
                        Map.of(
                                "eastCut",
                                true,
                                "westCut",
                                true,
                                "craftedPaper",
                                papers,
                                "craftedTerminal",
                                terminals,
                                "stored",
                                stored));
        }
    }

    @SubscribeEvent
    public static void broke(BlockEvent.BreakEvent event) {
        if (!grew
                || !event.getPlayer().getUUID().equals(gardener)
                || !event.getState().is(Blocks.SUGAR_CANE)) return;
        if (event.getPos().equals(EAST)) eastCut = true;
        if (event.getPos().equals(WEST)) westCut = true;
    }

    @SubscribeEvent
    public static void crafted(PlayerEvent.ItemCraftedEvent event) {
        if (!grew
                || event.getEntity().level().isClientSide
                || !event.getEntity().getUUID().equals(gardener)) return;
        if (event.getCrafting().is(Items.PAPER)) papers += event.getCrafting().getCount();
        if (event.getCrafting().is(VoyageModule.SLATE.get()))
            terminals += event.getCrafting().getCount();
    }

    static void save(String name, Object evidence) throws Exception {
        Files.writeString(Path.of(name + ".json"), new Gson().toJson(evidence));
    }

    static void require(boolean ok, String message) {
        if (!ok) throw new IllegalStateException(message);
    }
}
