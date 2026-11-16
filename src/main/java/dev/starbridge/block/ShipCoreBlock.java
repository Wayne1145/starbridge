package dev.starbridge.block;

import dev.starbridge.Starbridge;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import org.jetbrains.annotations.Nullable;

public final class ShipCoreBlock extends BaseEntityBlock {
    public ShipCoreBlock() { super(Properties.copy(Blocks.IRON_BLOCK).strength(4, 12)); }
    @Override public RenderShape getRenderShape(BlockState state) { return RenderShape.MODEL; }
    @Override public BlockEntity newBlockEntity(BlockPos pos, BlockState state) { return new ShipCoreBlockEntity(pos, state); }
    @Override public void setPlacedBy(Level level, BlockPos pos, BlockState state, @Nullable LivingEntity placer, ItemStack stack) {
        if (placer instanceof Player player && level.getBlockEntity(pos) instanceof ShipCoreBlockEntity core) core.setOwner(player.getUUID());
    }
    @Override public InteractionResult use(BlockState state, Level level, BlockPos pos, Player player, InteractionHand hand, BlockHitResult hit) {
        if (level.isClientSide) return InteractionResult.SUCCESS;
        if (!(level.getBlockEntity(pos) instanceof ShipCoreBlockEntity core)) return InteractionResult.PASS;
        core.register();
        if (!core.authorized(player)) {
            player.displayClientMessage(Component.translatable("message.starbridge.not_owner"), true);
            return InteractionResult.CONSUME;
        }
        CompoundTag data = player.getPersistentData();
        data.putUUID("starbridge:selected_core", core.id());
        if (core.addFuel(player.getItemInHand(hand), player.getAbilities().instabuild)) {
            player.displayClientMessage(Component.literal("Starbridge · " + core.energy() + " FE"), true);
        } else {
            player.sendSystemMessage(Component.literal("Starbridge · " + core.shortStatus()));
            player.sendSystemMessage(Component.translatable("message.starbridge.core_selected"));
        }
        return InteractionResult.CONSUME;
    }
    @Override public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state, BlockEntityType<T> type) {
        return level.isClientSide ? null : createTickerHelper(type, Starbridge.SHIP_CORE_ENTITY.get(), ShipCoreBlockEntity::tick);
    }
}
