package dev.starbridge.campaign;

import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;

/** Shared construction orders at the deep-space station and rotating contracts after commissioning. */
final class RelayTerminal extends Block {
    RelayTerminal() { super(Properties.copy(Blocks.IRON_BLOCK).strength(3.5f)); }

    @Override public InteractionResult use(BlockState state, Level level, BlockPos pos, Player player, InteractionHand hand, BlockHitResult hit) {
        if (level.isClientSide) return InteractionResult.SUCCESS;
        if (!(player instanceof ServerPlayer crew) || hand != InteractionHand.MAIN_HAND) return InteractionResult.PASS;
        if (!CampaignModule.matches(crew.serverLevel(), "relay")) return message(crew, "wrong_site");
        BlockPos anchor = CampaignSites.prepare(crew.serverLevel(), "relay");
        if (anchor == null || !pos.equals(anchor)) return message(crew, "wrong_site");
        CampaignData data = CampaignData.get(crew.serverLevel());
        ItemStack held = crew.getItemInHand(hand);
        if (!data.online()) return construct(crew, held, data);
        return contract(crew, held, data);
    }

    private InteractionResult construct(ServerPlayer crew, ItemStack held, CampaignData data) {
        CampaignData.Material material = held.is(CampaignModule.ARRAY.get()) ? CampaignData.Material.ARRAYS
                : held.is(Items.COPPER_INGOT) ? CampaignData.Material.COPPER
                : held.is(Items.REDSTONE_BLOCK) ? CampaignData.Material.POWER : null;
        if (material != null) {
            int accepted = data.deposit(material, held.getCount());
            if (!crew.getAbilities().instabuild) held.shrink(accepted);
        }
        if (data.online()) {
            crew.serverLevel().getServer().getPlayerList().broadcastSystemMessage(Component.translatable("campaign.starbridge.online", crew.getDisplayName()), false);
            award(crew, "relay_online");
            return order(crew, data);
        }
        crew.sendSystemMessage(Component.translatable("campaign.starbridge.materials", data.count(CampaignData.Material.ARRAYS), CampaignData.ARRAYS_REQUIRED,
                data.count(CampaignData.Material.COPPER), CampaignData.COPPER_REQUIRED, data.count(CampaignData.Material.POWER), CampaignData.POWER_REQUIRED));
        return InteractionResult.CONSUME;
    }

    private InteractionResult contract(ServerPlayer crew, ItemStack held, CampaignData data) {
        award(crew, "relay_online");
        CampaignData.Contract type = data.contract();
        Item cargo = switch (type) {
            case SUPPLY -> Items.BREAD; case SURVEY -> CampaignModule.RELIC.get(); case RESCUE -> CampaignModule.SURVIVOR.get();
        };
        int cost = type == CampaignData.Contract.SUPPLY ? 16 : 1;
        if (!held.is(cargo) || held.getCount() < cost) return order(crew, data);
        // Use the slot being emptied, or reserve space for the complete reward before consuming any cargo.
        int rewardCount = type == CampaignData.Contract.RESCUE ? 6 : 4;
        ItemStack reward = new ItemStack(Items.EMERALD, rewardCount);
        boolean replacing = held.getCount() == cost && !crew.getAbilities().instabuild;
        if (!replacing) {
            int space = 0;
            for (ItemStack stack : crew.getInventory().items) {
                if (stack.isEmpty()) space += reward.getMaxStackSize();
                else if (ItemStack.isSameItemSameTags(stack, reward)) space += stack.getMaxStackSize() - stack.getCount();
            }
            if (space < rewardCount) return message(crew, "inventory_full");
        }
        if (replacing) crew.setItemInHand(InteractionHand.MAIN_HAND, reward);
        else {
            if (!crew.getAbilities().instabuild) held.shrink(cost);
            crew.getInventory().add(reward);
        }
        data.finishContract();
        award(crew, "relay_dispatch");
        crew.sendSystemMessage(Component.translatable("campaign.starbridge.order_done", rewardCount));
        return order(crew, data);
    }

    private static InteractionResult order(ServerPlayer crew, CampaignData data) {
        return message(crew, switch (data.contract()) {
            case SUPPLY -> "order.supply"; case SURVEY -> "order.survey"; case RESCUE -> "order.rescue";
        });
    }
    private static InteractionResult message(ServerPlayer player, String key) {
        player.sendSystemMessage(Component.translatable("campaign.starbridge." + key));
        return InteractionResult.CONSUME;
    }
    private static void award(ServerPlayer player, String path) {
        var advancement = player.serverLevel().getServer().getAdvancements().getAdvancement(new net.minecraft.resources.ResourceLocation("starbridge", "campaign/" + path));
        if (advancement != null) player.getAdvancements().award(advancement, "reached");
    }
}
