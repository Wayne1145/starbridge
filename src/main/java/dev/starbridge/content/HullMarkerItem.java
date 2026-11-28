package dev.starbridge.content;

import dev.starbridge.assembly.AssemblyModule;
import dev.starbridge.block.ShipCoreBlockEntity;
import dev.starbridge.text.Lang;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * Survival ship assembly without typing coordinates: right-click a block for corner A, sneak +
 * right-click for corner B, then right-click your ship core to scan; right-click it again to confirm.
 */
public final class HullMarkerItem extends Item {
    private static final String CORNER_A = "CornerA";
    private static final String CORNER_B = "CornerB";

    public HullMarkerItem(Properties properties) { super(properties); }

    @Override
    public InteractionResult useOn(UseOnContext context) {
        Level level = context.getLevel();
        if (level.isClientSide) return InteractionResult.SUCCESS;
        if (!(context.getPlayer() instanceof ServerPlayer player)) return InteractionResult.PASS;
        ItemStack stack = context.getItemInHand();
        BlockPos clicked = context.getClickedPos();
        if (level.getBlockEntity(clicked) instanceof ShipCoreBlockEntity core && !player.isShiftKeyDown()) {
            if (!core.authorized(player)) {
                player.displayClientMessage(Component.translatable("message.starbridge.not_owner"), true);
                return InteractionResult.CONSUME;
            }
            BlockPos a = corner(stack, CORNER_A), b = corner(stack, CORNER_B);
            if (a == null || b == null) {
                player.displayClientMessage(Lang.msg("marker_need_corners"), true);
                return InteractionResult.CONSUME;
            }
            AssemblyModule.useMarker(player, core, a, b);
            return InteractionResult.CONSUME;
        }
        String key = player.isShiftKeyDown() ? CORNER_B : CORNER_A;
        stack.getOrCreateTag().put(key, NbtUtils.writeBlockPos(clicked));
        level.playSound(null, clicked, SoundEvents.AMETHYST_BLOCK_CHIME, SoundSource.PLAYERS, 1, key.equals(CORNER_A) ? 1.2F : 0.8F);
        if (level instanceof ServerLevel serverLevel)
            serverLevel.sendParticles(ParticleTypes.END_ROD, clicked.getX() + 0.5, clicked.getY() + 1.1, clicked.getZ() + 0.5, 12, 0.3, 0.3, 0.3, 0.01);
        BlockPos a = corner(stack, CORNER_A), b = corner(stack, CORNER_B);
        if (a != null && b != null) {
            long volume = (long) (Math.abs(a.getX() - b.getX()) + 1) * (Math.abs(a.getY() - b.getY()) + 1) * (Math.abs(a.getZ() - b.getZ()) + 1);
            player.displayClientMessage(Lang.msg("marker_box", Math.abs(a.getX() - b.getX()) + 1,
                    Math.abs(a.getY() - b.getY()) + 1, Math.abs(a.getZ() - b.getZ()) + 1, volume), true);
        } else {
            player.displayClientMessage(Lang.msg(key.equals(CORNER_A) ? "marker_a" : "marker_b",
                    clicked.getX(), clicked.getY(), clicked.getZ()), true);
        }
        return InteractionResult.CONSUME;
    }

    private static @Nullable BlockPos corner(ItemStack stack, String key) {
        CompoundTag tag = stack.getTag();
        return tag != null && tag.contains(key) ? NbtUtils.readBlockPos(tag.getCompound(key)) : null;
    }

    @Override
    public void appendHoverText(ItemStack stack, @Nullable Level level, List<Component> tooltip, TooltipFlag flag) {
        tooltip.add(Component.translatable("tooltip.starbridge.hull_marker").withStyle(ChatFormatting.GRAY));
        for (String key : new String[]{CORNER_A, CORNER_B}) {
            BlockPos pos = corner(stack, key);
            if (pos != null) tooltip.add(Component.literal((key.equals(CORNER_A) ? "A  " : "B  ")
                    + pos.getX() + " " + pos.getY() + " " + pos.getZ()).withStyle(ChatFormatting.DARK_AQUA));
        }
    }

    @Override
    public boolean isFoil(ItemStack stack) { return corner(stack, CORNER_A) != null && corner(stack, CORNER_B) != null; }
}
