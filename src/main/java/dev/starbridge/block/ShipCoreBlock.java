package dev.starbridge.block;

import dev.starbridge.Starbridge;
import dev.starbridge.content.ContentModule;
import dev.starbridge.content.StarFuelCellItem;
import dev.starbridge.content.UpgradeModuleItem;
import dev.starbridge.text.Lang;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import org.jetbrains.annotations.Nullable;

public final class ShipCoreBlock extends BaseEntityBlock {
    public ShipCoreBlock() { super(Properties.copy(Blocks.IRON_BLOCK).strength(4, 12).lightLevel(state -> 7)); }
    @Override public RenderShape getRenderShape(BlockState state) { return RenderShape.MODEL; }
    @Override public BlockEntity newBlockEntity(BlockPos pos, BlockState state) { return new ShipCoreBlockEntity(pos, state); }
    @Override public void setPlacedBy(Level level, BlockPos pos, BlockState state, @Nullable LivingEntity placer, ItemStack stack) {
        if (placer instanceof Player player && level.getBlockEntity(pos) instanceof ShipCoreBlockEntity core) core.setOwner(player.getUUID());
    }

    @Override public InteractionResult use(BlockState state, Level level, BlockPos pos, Player player, InteractionHand hand, BlockHitResult hit) {
        if (level.isClientSide) return InteractionResult.SUCCESS;
        if (!(level.getBlockEntity(pos) instanceof ShipCoreBlockEntity core) || !(player instanceof ServerPlayer serverPlayer))
            return InteractionResult.PASS;
        core.register();
        if (!core.authorized(player)) {
            player.displayClientMessage(Component.translatable("message.starbridge.not_owner"), true);
            return InteractionResult.CONSUME;
        }
        CompoundTag data = player.getPersistentData();
        data.putUUID("starbridge:selected_core", core.id());
        ItemStack held = player.getItemInHand(hand);
        boolean creative = player.getAbilities().instabuild;
        if (held.getItem() instanceof dev.starbridge.content.HullMarkerItem) return InteractionResult.PASS;

        if (held.getItem() instanceof UpgradeModuleItem module) {
            if (core.installUpgrade(module.type())) {
                if (!creative) held.shrink(1);
                level.playSound(null, pos, SoundEvents.SMITHING_TABLE_USE, SoundSource.BLOCKS, 1, 1.2F);
                player.displayClientMessage(Lang.msg("upgrade_installed",
                        Component.translatable(module.getDescriptionId()), core.upgradeLevel(module.type()), module.type().maxLevel()), true);
            } else {
                player.displayClientMessage(Lang.msg("upgrade_full", Component.translatable(module.getDescriptionId())), true);
            }
            return InteractionResult.CONSUME;
        }
        if (held.getItem() instanceof StarFuelCellItem) {
            int added = core.charge(StarFuelCellItem.ENERGY);
            if (added <= 0) { player.displayClientMessage(Lang.msg("core_full"), true); return InteractionResult.CONSUME; }
            if (!creative) held.shrink(1);
            level.playSound(null, pos, SoundEvents.BEACON_POWER_SELECT, SoundSource.BLOCKS, 0.8F, 1.6F);
            player.displayClientMessage(Lang.msg("charged", added, core.energy(), core.capacity()), true);
            return InteractionResult.CONSUME;
        }
        if (held.is(ContentModule.STAR_CHART.get())) {
            try { core.jump(serverPlayer); }
            catch (RuntimeException failure) { player.displayClientMessage(Lang.of(failure), true); }
            return InteractionResult.CONSUME;
        }
        int added = core.addFuel(held, creative, player.isShiftKeyDown());
        if (added > 0) {
            level.playSound(null, pos, SoundEvents.FIRECHARGE_USE, SoundSource.BLOCKS, 0.5F, 1.4F);
            player.displayClientMessage(Lang.msg("charged", added, core.energy(), core.capacity()), true);
        } else {
            player.sendSystemMessage(Lang.msg("core_summary", core.energy(), core.capacity(),
                    core.upgradeLevel(ShipUpgrade.CAPACITOR), core.upgradeLevel(ShipUpgrade.THRUSTER),
                    core.upgradeLevel(ShipUpgrade.EFFICIENCY)));
            if (!core.lastErrorKey().isEmpty()) player.sendSystemMessage(Lang.keyOrText(core.lastErrorKey()));
            player.sendSystemMessage(Component.translatable("message.starbridge.core_selected"));
        }
        return InteractionResult.CONSUME;
    }
    @Override public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state, BlockEntityType<T> type) {
        return level.isClientSide ? null : createTickerHelper(type, Starbridge.SHIP_CORE_ENTITY.get(), ShipCoreBlockEntity::tick);
    }
}
