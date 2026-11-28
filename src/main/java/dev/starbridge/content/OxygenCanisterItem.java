package dev.starbridge.content;

import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

/** Right-click to refill personal oxygen; the mask is still required to breathe. */
public final class OxygenCanisterItem extends Item {
    public static final int REFILL_TICKS = 2_400;
    public static final int CAPACITY_TICKS = 9_600;
    public static final String OXYGEN_KEY = "starbridge.personal_oxygen";

    public OxygenCanisterItem() {
        super(new Item.Properties().stacksTo(16));
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        int previous = oxygen(player);
        if (previous >= CAPACITY_TICKS) {
            if (!level.isClientSide) player.displayClientMessage(dev.starbridge.text.Lang.msg("oxygen_full"), true);
            return InteractionResultHolder.fail(stack);
        }
        if (player instanceof ServerPlayer) {
            setOxygen(player, previous + REFILL_TICKS);
            if (!player.getAbilities().instabuild) stack.shrink(1);
            player.getCooldowns().addCooldown(this, 10);
            level.playSound(null, player.blockPosition(), SoundEvents.BOTTLE_FILL,
                    SoundSource.PLAYERS, 0.6F, 0.7F);
            player.displayClientMessage(dev.starbridge.text.Lang.msg("oxygen_refilled", oxygen(player) / 20), true);
        }
        return InteractionResultHolder.sidedSuccess(stack, level.isClientSide);
    }

    public static int oxygen(Player player) {
        return Math.max(0, Math.min(CAPACITY_TICKS, player.getPersistentData().getInt(OXYGEN_KEY)));
    }

    public static void setOxygen(Player player, int ticks) {
        player.getPersistentData().putInt(OXYGEN_KEY, Math.max(0, Math.min(CAPACITY_TICKS, ticks)));
    }
}
