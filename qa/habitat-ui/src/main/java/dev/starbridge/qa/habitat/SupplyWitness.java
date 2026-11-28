package dev.starbridge.qa.habitat;

import com.google.gson.Gson;
import dev.starbridge.block.ShipCoreBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraftforge.registries.ForgeRegistries;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/** Independent observations of an untouched generator feeding the real RF consumer. */
final class SupplyWitness {
    static final BlockPos GENERATOR = new BlockPos(63,149,61), ASSEMBLER = GENERATOR.east(), HELM = new BlockPos(63,149,63);
    private static int startingRF = -1;
    private SupplyWitness() {}
    static void prepare(ServerLevel level, ServerPlayer player) {
        var gen = ForgeRegistries.BLOCKS.getValue(new ResourceLocation("libvulpes", "tile.coalgenerator"));
        var assembler = ForgeRegistries.BLOCKS.getValue(new ResourceLocation("advancedrocketry", "rocketbuilder"));
        if (gen == null || assembler == null) throw new IllegalStateException("Missing native machines");
        level.setBlockAndUpdate(ASSEMBLER, assembler.defaultBlockState());
        level.setBlockAndUpdate(GENERATOR, gen.defaultBlockState());
        player.getInventory().setItem(4, new ItemStack(Items.LAVA_BUCKET));
        player.getInventory().setItem(5, new ItemStack(Items.COAL));
    }
    static void sample(ServerLevel level, ServerPlayer player) throws Exception {
        var wrapped = level.getBlockEntity(ASSEMBLER);
        if (wrapped == null) return;
        var original = wrapped.getClass().getMethod("legacyTile").invoke(wrapped);
        if (original == null) return;
        int rf = ((Number)original.getClass().getMethod("getEnergyStored").invoke(original)).intValue();
        if (startingRF == -1) {
            startingRF = rf;
            if (rf != 0) throw new IllegalStateException("Original assembler was not initially empty");
        }
        if (rf > 0 && !Files.isRegularFile(Path.of("assembler-powered.json"))) {
            Files.writeString(Path.of("assembler-powered.json"),new Gson().toJson(Map.of("initialRF",startingRF,"actualRF",rf,"tile",original.getClass().getName())));
        }
        if (rf <= 0 || !(level.getBlockEntity(HELM) instanceof ShipCoreBlockEntity helm) || helm.energy() != 200000) return;
        if (player.getInventory().countItem(Items.LAVA_BUCKET) != 0 || player.getInventory().countItem(Items.BUCKET) != 1) return;
        if (!Files.isRegularFile(Path.of("supply-server.json"))) Files.writeString(Path.of("supply-server.json"),new Gson().toJson(Map.of(
                "initialAssemblerRF",startingRF,"assemblerRF",rf,"coreFE",helm.energy(),"emptyBucket",1,"survival",!player.isCreative(),"pass",true)));
    }
}
