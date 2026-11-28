package dev.starbridge.client;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.registries.ForgeRegistries;

/** Three different resources are consumed between starting the shipyard and reaching lunar orbit. */
enum LaunchSupplies {
    ASSEMBLER("assembler", "minecraft:coal", "libvulpes:tile.coalgenerator", "advancedrocketry:rocketbuilder"),
    PROPELLANT("propellant", "advancedrocketry:item.bucketrocketfuel", "advancedrocketry:fueltank", "advancedrocketry:rocketbuilder"),
    HELM("helm", "minecraft:charcoal", "starbridge:ship_core", "starbridge:navigation_slate");

    static final ResourceLocation GENERATOR = new ResourceLocation("libvulpes", "tile.coalgenerator");
    private final String label;
    private final ResourceLocation[] parts;

    LaunchSupplies(String label, String first, String next, String last) {
        this.label = label;
        this.parts = new ResourceLocation[]{new ResourceLocation(first), new ResourceLocation(next), new ResourceLocation(last)};
    }
    private Component tr(String suffix, Object... args) { return Component.translatable("slate.starbridge.supply." + label + suffix, args); }
    Component title() { return tr(""); }
    ItemStack draw(GuiGraphics graphics, Font font, int left, int top, int width, int mx, int my, Component use) {
        ItemStack hover = ItemStack.EMPTY;
        int column = (width - 22) / 3;
        boolean missing = false;
        for (int i = 0; i < parts.length; i++) {
            var item = ForgeRegistries.ITEMS.getValue(parts[i]);
            var stack = item == null ? ItemStack.EMPTY : new ItemStack(item);
            int cx = left + 11 + column * i + column / 2;
            graphics.renderOutline(cx - 13, top, 26, 26, i == 1 ? 0xff74dec7 : 0xff526e7d);
            if (stack.isEmpty()) { missing = true; graphics.drawCenteredString(font, "?", cx, top + 8, 0xffffc683); }
            else graphics.renderItem(stack, cx - 8, top + 5);
            if (mx >= cx - 13 && mx < cx + 13 && my >= top && my < top + 26) hover = stack;
            graphics.drawCenteredString(font, tr(".step" + i), cx, top + 30, 0xffdbe9e9);
            if (i < 2) graphics.drawCenteredString(font, "→", cx + column / 2, top + 9, 0xff74dec7);
        }
        var help = missing ? Component.translatable("slate.starbridge.supply.missing") : tr(".help", use);
        graphics.drawWordWrap(font, help, left + 13, top + 49, width - 26, missing ? 0xffffc683 : 0xffdbe9e9);
        return hover;
    }
}
