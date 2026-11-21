package dev.starbridge.campaign;

import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.TagKey;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/** A sealed sample tube recovers physical cargo at the landing site. */
final class SampleTube extends Item {
    static final TagKey<Block> MOON_SURFACE = BlockTags.create(new ResourceLocation("starbridge", "lunar_samples"));
    SampleTube() { super(new Properties().stacksTo(16)); }

    @Override public InteractionResult useOn(UseOnContext context) {
        if (context.getLevel().isClientSide) return InteractionResult.SUCCESS;
        if (!(context.getPlayer() instanceof ServerPlayer player)) return InteractionResult.PASS;
        ServerLevel level = player.serverLevel();
        BlockPos pos = context.getClickedPos();
        BlockState state = level.getBlockState(pos);
        Item recovered;
        if (CampaignModule.matches(level, "moon") && state.is(MOON_SURFACE)) {
            recovered = CampaignModule.MOON_SAMPLE.get();
        } else {
            String route = CampaignModule.matches(level, "mars") ? "mars" : CampaignModule.matches(level, "belt") ? "belt" : "";
            if (route.isEmpty()) return help(player);
            BlockPos origin = CampaignSites.prepare(level, route);
            if (origin == null) return help(player);
            if (route.equals("mars") && pos.equals(origin) && state.is(Blocks.CHISELED_RED_SANDSTONE)) {
                recovered = CampaignModule.RELIC.get();
            } else if (route.equals("belt") && pos.equals(origin) && state.is(Blocks.WAXED_CUT_COPPER)) {
                recovered = CampaignModule.FLIGHT_RECORDER.get();
            } else if (route.equals("belt") && pos.equals(origin.east(4)) && state.is(Blocks.BLUE_ICE)
                    && CampaignData.get(level).online() && CampaignData.get(level).contract() == CampaignData.Contract.RESCUE) {
                recovered = CampaignModule.SURVIVOR.get();
            } else return help(player);
        }
        ItemStack tube = context.getItemInHand();
        if (!player.getAbilities().instabuild && tube.getCount() == 1) {
            player.setItemInHand(context.getHand(), new ItemStack(recovered));
        } else {
            if (!player.getInventory().add(new ItemStack(recovered))) {
                player.displayClientMessage(Component.translatable("campaign.starbridge.inventory_full"), true);
                return InteractionResult.FAIL;
            }
            if (!player.getAbilities().instabuild) tube.shrink(1);
        }
        player.sendSystemMessage(Component.translatable("campaign.starbridge.sample_collected", recovered.getDescription()));
        return InteractionResult.CONSUME;
    }
    private InteractionResult help(ServerPlayer player) {
        player.displayClientMessage(Component.translatable("campaign.starbridge.tube_help"), true);
        return InteractionResult.PASS;
    }
}
