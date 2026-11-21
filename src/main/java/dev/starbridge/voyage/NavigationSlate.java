package dev.starbridge.voyage;

import dev.starbridge.block.ShipCoreBlockEntity;
import dev.starbridge.server.CoreRegistry;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import org.jetbrains.annotations.Nullable;
import java.util.List;
import net.minecraft.world.level.Level;

public final class NavigationSlate extends Item {
    public NavigationSlate() { super(new Properties().stacksTo(1)); }
    @Override public InteractionResultHolder<ItemStack> use(Level level, Player user, InteractionHand hand) {
        if (user instanceof ServerPlayer player) open(player);
        return InteractionResultHolder.sidedSuccess(user.getItemInHand(hand), level.isClientSide);
    }
    @Override public void appendHoverText(ItemStack stack, @Nullable Level level, List<Component> tooltip, TooltipFlag flag) {
        super.appendHoverText(stack, level, tooltip, flag);
        tooltip.add(Component.translatable("slate.starbridge.guide.hint"));
    }

    public static void open(ServerPlayer player) {
        var data = player.getPersistentData();
        ShipCoreBlockEntity core = data.hasUUID("starbridge:selected_core")
                ? CoreRegistry.find(data.getUUID("starbridge:selected_core")).orElse(null) : null;
        if (core == null || !core.authorized(player) || core.getLevel() != player.serverLevel() || !core.loadedForControl()) {
            VoyageNetwork.openSlateGuide(player);
            return;
        }
        VoyageNetwork.open(player, core);
    }
}
