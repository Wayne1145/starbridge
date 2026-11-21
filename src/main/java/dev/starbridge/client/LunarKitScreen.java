package dev.starbridge.client;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.List;

/** Original equipment for the selected lunar route. The icons come from the installed pack. */
public final class LunarKitScreen extends EquipmentAccessScreen {
    private static final int PANEL = 0xff142837, EDGE = 0xff365566, TEXT = 0xffdbe9e9;
    private static final int OXYGEN = 0xff78daca, EXPOSURE = 0xffffc683;
    private final Screen chart;
    private final List<ItemStack> oxygen, suit;
    private final boolean radiation;
    private int left, top, panelWidth, panelHeight;

    LunarKitScreen(Screen chart) {
        super(label("title"));
        this.chart = chart;
        oxygen = items("galacticraftcore", "item.oxygenmask", "item.oxygengear",
                "item.oxygentankheavyfull", "item.oxygentankheavyfull");
        radiation = ForgeRegistries.ITEMS.containsKey(new ResourceLocation("galaxyspace", "item.spacesuit_helmet"));
        suit = radiation ? items("galaxyspace", "item.spacesuit_helmet", "item.spacesuit_plate", "item.spacesuit_leg", "item.spacesuit_boots")
                : items("advancedrocketry", "item.spacehelmet", "item.spacechest", "item.spaceleggings", "item.spaceboots");
    }

    private static List<ItemStack> items(String mod, String... names) {
        return java.util.Arrays.stream(names).map(name -> {
            var item = ForgeRegistries.ITEMS.getValue(new ResourceLocation(mod, name));
            return item == null ? ItemStack.EMPTY : new ItemStack(item);
        }).toList();
    }

    private static Component label(String key) {
        return Component.translatable("nav.starbridge.kit." + key);
    }

    @Override protected void init() {
        panelWidth = Math.min(width - 8, 364);
        panelHeight = Math.min(height - 8, 210);
        left = (width - panelWidth) / 2;
        top = (height - panelHeight) / 2;
        equipmentActions(left + 12, top + panelHeight - 24, panelWidth - 24, label("back"));
    }

    @Override public void onClose() { minecraft.setScreen(chart); }
    @Override public boolean isPauseScreen() { return false; }

    @Override public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        renderBackground(graphics);
        graphics.fill(left, top, left + panelWidth, top + panelHeight, PANEL);
        graphics.renderOutline(left, top, panelWidth, panelHeight, EDGE);
        graphics.drawCenteredString(font, title, width / 2, top + 9, TEXT);
        int secondY = top + 26 + (panelHeight - 52) / 2;
        var oxygenHover = row(graphics, top + 26, oxygen, "oxygen", "oxygen_help", OXYGEN, mouseX, mouseY);
        var suitHover = row(graphics, secondY, suit, radiation ? "radiation" : "pressure",
                radiation ? "radiation_help" : "pressure_help", EXPOSURE, mouseX, mouseY);
        drawEquipmentNotice(graphics, left + 12, top + panelHeight - 26, panelWidth - 24);
        super.render(graphics, mouseX, mouseY, partialTick);
        var hover = oxygenHover.isEmpty() ? suitHover : oxygenHover;
        if (!hover.isEmpty()) graphics.renderTooltip(font, hover, mouseX, mouseY);
    }

    private ItemStack row(GuiGraphics graphics, int y, List<ItemStack> pieces, String heading, String hint,
                          int accent, int mouseX, int mouseY) {
        int x = left + 12;
        graphics.hLine(x, left + panelWidth - 13, y, EDGE);
        graphics.drawString(font, label(heading), x, y + 7, accent, false);
        ItemStack hovered = ItemStack.EMPTY;
        for (var piece : pieces) {
            int slotY = y + 23;
            graphics.renderOutline(x, slotY, 21, 21, accent);
            if (piece.isEmpty()) graphics.drawCenteredString(font, "?", x + 10, slotY + 6, accent);
            else graphics.renderItem(piece, x + 3, slotY + 3);
            if (mouseX >= x && mouseX < x + 21 && mouseY >= slotY && mouseY < slotY + 21) hovered = piece;
            x += 24;
        }
        graphics.drawWordWrap(font, label(hint), x + 4, y + 21, left + panelWidth - x - 16, TEXT);
        return hovered;
    }
}
