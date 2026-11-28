package dev.starbridge.content;

import dev.starbridge.block.ShipUpgrade;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/** Right-click a ship core to install. */
public final class UpgradeModuleItem extends Item {
    private final ShipUpgrade type;

    public UpgradeModuleItem(ShipUpgrade type, Properties properties) {
        super(properties);
        this.type = type;
    }

    public ShipUpgrade type() { return type; }

    @Override
    public void appendHoverText(ItemStack stack, @Nullable Level level, List<Component> tooltip, TooltipFlag flag) {
        tooltip.add(Component.translatable("tooltip.starbridge.upgrade." + type.id()).withStyle(ChatFormatting.GRAY));
        tooltip.add(Component.translatable("tooltip.starbridge.upgrade.install", type.maxLevel()).withStyle(ChatFormatting.DARK_AQUA));
    }
}
