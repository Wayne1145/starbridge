package dev.starbridge.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.Component;

/** A computer found by the ship's peripheral. The framed monitor and check mark distinguish the paired device. */
final class ComputerCard extends Button {
    private final boolean paired;

    ComputerCard(int x, int y, int width, Component message, boolean paired, OnPress action) {
        super(x, y, width, 25, message, action, DEFAULT_NARRATION);
        this.paired = paired;
    }

    @Override protected void renderWidget(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        int x = getX(), y = getY();
        int accent = paired ? 0xff74dec7 : isHoveredOrFocused() ? 0xffdbe9e9 : 0xff7798a8;
        g.fill(x, y, x + width, y + height, isHoveredOrFocused() ? 0xff233e50 : 0xff152c39);
        g.renderOutline(x, y, width, height, accent);
        g.renderOutline(x + 5, y + 6, 14, 10, accent);
        g.hLine(x + 9, x + 15, y + 18, accent);
        g.fill(x + 11, y + 15, x + 14, y + 18, accent);
        if (paired) {
            g.fill(x + 8, y + 10, x + 10, y + 12, accent);
            g.fill(x + 10, y + 12, x + 12, y + 14, accent);
            g.fill(x + 12, y + 10, x + 14, y + 12, accent);
            g.fill(x + 14, y + 8, x + 16, y + 10, accent);
        }
        var font = Minecraft.getInstance().font;
        renderScrollingString(g, font, getMessage(), x + 23, y, x + width - 3, y + height, 0xffdbe9e9);
    }
}
