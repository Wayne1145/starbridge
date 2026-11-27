package dev.starbridge.qa.fuel;

import com.mojang.authlib.GameProfile;
import dev.starbridge.Starbridge;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import net.minecraftforge.common.util.FakePlayerFactory;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.UUID;

@Mod.EventBusSubscriber(modid = "starbridgenativefuelqa")
public final class FuelBucketProbe {
    @SubscribeEvent
    public static void started(ServerStartedEvent event) {
        if (!Boolean.getBoolean("starbridge.fuelBucketProbe")) return;
        try {
            run(event.getServer().overworld());
            Starbridge.LOGGER.info("FUEL_BUCKET_PROBE PASS");
        } catch (Throwable failure) {
            Starbridge.LOGGER.error("FUEL_BUCKET_PROBE FAIL", failure);
        } finally {
            event.getServer().halt(false);
        }
    }

    private static void run(ServerLevel level) {
        var fuel = ForgeRegistries.ITEMS.getValue(ResourceLocation.parse("advancedrocketry:item.bucketrocketfuel"));
        var source = ForgeRegistries.BLOCKS.getValue(ResourceLocation.parse("advancedrocketry:tile.rocketfuelblock"));
        require(fuel != null && fuel != Items.AIR && source != null && source != Blocks.AIR, "Original registry entries missing");
        var state = source.defaultBlockState();
        var meta = (IntegerProperty) source.getStateDefinition().getProperty("meta");
        var pos = new BlockPos(0, 310, 0);
        level.getChunkAt(pos);
        level.setBlockAndUpdate(pos.below(), Blocks.STONE.defaultBlockState());
        var player = FakePlayerFactory.get(level, new GameProfile(UUID.fromString("4a62fc80-4284-4f36-8d15-97acf4099c53"), "FuelBucketProbe"));
        player.moveTo(0.5, 312.0, 0.5, 0, 90);
        player.getAbilities().instabuild = false;
        level.setBlockAndUpdate(pos, state.setValue(meta, 0));
        player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.BUCKET, 2));
        var collected = Items.BUCKET.use(level, player, InteractionHand.MAIN_HAND);
        require(collected.getResult().consumesAction() && level.isEmptyBlock(pos), "Survival: original source was not picked up: " + collected.getResult());
        require(collected.getObject().is(Items.BUCKET) && collected.getObject().getCount() == 1
                && player.getInventory().countItem(fuel) == 1, "Survival: incorrect bucket exchange");
        level.setBlockAndUpdate(pos, state.setValue(meta, 1));
        Items.BUCKET.use(level, player, InteractionHand.MAIN_HAND);
        require(level.getBlockState(pos).is(source) && player.getInventory().countItem(fuel) == 1,
                "Flowing fuel yielded a bucket");
        player.getInventory().clearContent();
        level.setBlockAndUpdate(pos, state.setValue(meta, 0));
        player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.BUCKET));
        player.getAbilities().instabuild = true;
        var creative = Items.BUCKET.use(level, player, InteractionHand.MAIN_HAND);
        require(creative.getResult().consumesAction() && level.isEmptyBlock(pos)
                && creative.getObject().is(Items.BUCKET) && player.getInventory().countItem(fuel) == 1,
                "Creative: incorrect bucket exchange");
        CreativeModeTabs.tryRebuildTabContents(level.enabledFeatures(), true, level.registryAccess());
        var tab = level.registryAccess().registryOrThrow(Registries.CREATIVE_MODE_TAB).get(ResourceLocation.parse("starbridge:main"));
        require(tab.getDisplayItems().stream().anyMatch(s -> s.is(fuel)), "Original bucket missing from Starbridge tab");
        require(CreativeModeTabs.searchTab().getDisplayItems().stream().anyMatch(s -> s.is(fuel)), "Original bucket missing from search");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }
}
