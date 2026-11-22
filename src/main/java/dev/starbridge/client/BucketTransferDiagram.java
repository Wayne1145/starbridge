package dev.starbridge.client;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.registries.ForgeRegistries;

/** Actual upper input slot and lower pickup slot of a LibVulpes fluid hatch. */
enum BucketTransferDiagram {
    WATER("water", true, "minecraft:water_bucket", "minecraft:bucket"),
    TAKE_GAS("take_gas", false, "minecraft:bucket", "gas"),
    FEED_GAS("feed_gas", true, "gas", "minecraft:bucket"),
    TAKE_FUEL("take_fuel", false, "minecraft:bucket", "advancedrocketry:item.bucketrocketfuel");

    private final String key;
    private final boolean inputHatch;
    private final String supplied, collected;

    BucketTransferDiagram(String key, boolean inputHatch, String supplied, String collected) {
        this.key = key;
        this.inputHatch = inputHatch;
        this.supplied = supplied;
        this.collected = collected;
    }

    private static Component tr(String key) { return Component.translatable("slate.starbridge.bucket." + key); }
    Component title() { return tr(key); }
    ItemStack hatch() {
        var hatch = item("libvulpes:blockhatch");
        if (!hatch.isEmpty()) hatch.getOrCreateTag().putInt("LegacyMeta", inputHatch ? 2 : 3);
        return hatch;
    }
    Component recipeLabel() { return tr(inputHatch ? "input_recipe" : "output_recipe"); }
    private static ItemStack item(String id) {
        var entry = ForgeRegistries.ITEMS.getValue(new ResourceLocation(id));
        return entry == null ? ItemStack.EMPTY : new ItemStack(entry);
    }
    private static ItemStack icon(String id, long ticks) {
        return item(id.equals("gas") ? (ticks / 40 % 2 == 0 ? "advancedrocketry:item.bucketoxygen" : "advancedrocketry:item.buckethydrogen") : id);
    }
    ItemStack draw(GuiGraphics g, Font font, int left, int top, int width, int mx, int my, long ticks) {
        int x = left + 20, y = top + 81, text = left + width / 2;
        var upper = icon(supplied, ticks);
        var lower = icon(collected, ticks);
        var hatch = hatch();
        g.renderOutline(x, y, 24, 24, 0xff7995a3);
        g.renderOutline(x, y + 34, 24, 24, 0xff74dec7);
        if (!upper.isEmpty()) g.renderItem(upper, x + 4, y + 4);
        if (!lower.isEmpty()) g.renderItem(lower, x + 4, y + 38);
        g.drawCenteredString(font, "↓", x + 12, y + 24, 0xff74dec7);
        g.drawString(font, tr("insert"), x + 30, y + 8, 0xffdbe9e9, false);
        g.drawString(font, tr("collect"), x + 30, y + 42, 0xffdbe9e9, false);
        if (!hatch.isEmpty()) g.renderItem(hatch, text, y + 2);
        g.drawString(font, tr(inputHatch ? "input" : "output"), text + 21, y + 5, 0xffdbe9e9, false);
        g.drawWordWrap(font, tr(key + "_help"), text, y + 27, left + width - text - 12, 0xffdbe9e9);
        if (mx >= x && mx < x + 24 && my >= y && my < y + 24) return upper;
        if (mx >= x && mx < x + 24 && my >= y + 34 && my < y + 58) return lower;
        if (mx >= text && mx < text + 18 && my >= y && my < y + 20) return hatch;
        return ItemStack.EMPTY;
    }
}
