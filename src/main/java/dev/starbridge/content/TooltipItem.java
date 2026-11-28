package dev.starbridge.content;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/** An item with a one-line translated hint: {@code tooltip.starbridge.<id>}. */
public class TooltipItem extends Item {
    private final String hintKey;
    private final boolean glint;

    public TooltipItem(String id, boolean glint, Properties properties) {
        super(properties);
        this.hintKey = "tooltip.starbridge." + id;
        this.glint = glint;
    }

    @Override
    public void appendHoverText(ItemStack stack, @Nullable Level level, List<Component> tooltip, TooltipFlag flag) {
        tooltip.add(Component.translatable(hintKey).withStyle(ChatFormatting.GRAY));
    }

    @Override
    public boolean isFoil(ItemStack stack) { return glint || super.isFoil(stack); }
}
