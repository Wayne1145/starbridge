package dev.starbridge.client;

import dev.starbridge.compat.galacticraft.OriginalOxygenEquipment;
import dev.starbridge.voyage.VoyageNetwork;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** Shared personal equipment action on the two pages that can open the original inventory. */
public abstract class EquipmentAccessScreen extends Screen {
    private static int nextRequest;
    private int request;
    private Button openButton;
    private Component notice;
    private boolean waiting;

    protected EquipmentAccessScreen(Component title) { super(title); }

    protected final void equipmentActions(int left, int top, int width, Component back) {
        int middle = (width - 5) / 2;
        openButton = addRenderableWidget(Button.builder(Component.translatable("nav.starbridge.kit.open"), button -> {
            notice = null;
            waiting = true;
            button.active = false;
            request = ++nextRequest;
            VoyageNetwork.openPersonalEquipment(request);
        }).bounds(left, top, middle, 18).build());
        openButton.active = !waiting;
        addRenderableWidget(Button.builder(back, button -> onClose())
                .bounds(left + middle + 5, top, width - middle - 5, 18).build());
    }

    public static void failed(int request, OriginalOxygenEquipment.Result result) {
        if (!(Minecraft.getInstance().screen instanceof EquipmentAccessScreen page) || !page.waiting || page.request != request) return;
        String key = switch (result) {
            case MISSING -> "missing";
            case BUSY -> "busy";
            default -> "unavailable";
        };
        page.notice = Component.translatable("nav.starbridge.kit.open." + key);
        page.waiting = false;
        page.openButton.active = true;
    }

    protected final void drawEquipmentNotice(GuiGraphics graphics, int x, int bottom, int width) {
        if (notice == null) return;
        var lines = font.split(notice, width - 12);
        int top = bottom - 6 - lines.size() * font.lineHeight;
        graphics.fill(x, top, x + width, bottom, 0xff302520);
        graphics.renderOutline(x, top, width, bottom - top, 0xffd59d62);
        int y = top + 3;
        for (var line : lines) {
            graphics.drawString(font, line, x + 6, y, 0xffffdaa3, false);
            y += font.lineHeight;
        }
    }
}
