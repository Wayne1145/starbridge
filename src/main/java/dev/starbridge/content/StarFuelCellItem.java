package dev.starbridge.content;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/** Dense lunar fuel. Right-click a ship core to add {@link #ENERGY} FE. */
public final class StarFuelCellItem extends Item {
    public static final int ENERGY = 1_000_000;

    public StarFuelCellItem(Properties properties) { super(properties); }

    @Override
    public void appendHoverText(ItemStack stack, @Nullable Level level, List<Component> tooltip, TooltipFlag flag) {
        tooltip.add(Component.translatable("tooltip.starbridge.star_fuel_cell", String.format("%,d", ENERGY)).withStyle(ChatFormatting.GRAY));
    }
}
