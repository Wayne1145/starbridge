package dev.starbridge.compat.rocketry;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.stats.Stats;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ItemUtils;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import net.minecraft.world.level.gameevent.GameEvent;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraftforge.event.entity.player.FillBucketEvent;
import net.minecraftforge.eventbus.api.Event;
import net.minecraftforge.registries.ForgeRegistries;

/** Connect the original fuel source and bucket across Retrofit's modern Forge boundary. */
public final class OriginalFuelBucket {
    private static final ResourceLocation BUCKET = new ResourceLocation("advancedrocketry", "item.bucketrocketfuel");
    private static final ResourceLocation SOURCE = new ResourceLocation("advancedrocketry", "tile.rocketfuelblock");

    private OriginalFuelBucket() {}

    public static void addToTab(CreativeModeTab.Output output) {
        var fuel = fuelBucket();
        if (!fuel.isEmpty()) output.accept(fuel);
    }

    public static void fill(FillBucketEvent event) {
        var empty = event.getEmptyBucket();
        if (event.getResult() != Event.Result.DEFAULT || !empty.is(Items.BUCKET)
                || !(event.getTarget() instanceof BlockHitResult hit) || hit.getType() != HitResult.Type.BLOCK) return;

        var level = event.getLevel();
        var pos = hit.getBlockPos();
        var player = event.getEntity();
        if (!isSource(level.getBlockState(pos)) || !level.mayInteract(player, pos)
                || !player.mayUseItemAt(pos.relative(hit.getDirection()), hit.getDirection(), empty)) return;

        var filled = fuelBucket();
        if (filled.isEmpty()) return;
        if (!level.isClientSide) {
            if (!level.removeBlock(pos, false)) return;
            // Forge's ALLOW result only exchanges buckets for survival players.
            if (player.getAbilities().instabuild) ItemUtils.createFilledResult(empty, player, filled);
            player.awardStat(Stats.ITEM_USED.get(Items.BUCKET));
            level.playSound(null, pos, SoundEvents.BUCKET_FILL, SoundSource.BLOCKS, 1, 1);
            level.gameEvent(player, GameEvent.FLUID_PICKUP, pos);
        }
        event.setFilledBucket(filled);
        event.setResult(Event.Result.ALLOW);
    }

    private static ItemStack fuelBucket() {
        var item = ForgeRegistries.ITEMS.getValue(BUCKET);
        return item == null ? ItemStack.EMPTY : new ItemStack(item);
    }

    private static boolean isSource(BlockState state) {
        // Retrofit preserves the legacy fluid level as block metadata. Only zero is a source.
        return SOURCE.equals(ForgeRegistries.BLOCKS.getKey(state.getBlock()))
                && state.getBlock().getStateDefinition().getProperty("meta") instanceof IntegerProperty meta
                && state.getValue(meta) == 0;
    }
}
