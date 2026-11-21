package dev.starbridge.client;

import dev.starbridge.voyage.Route;
import dev.starbridge.voyage.VoyageNetwork;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** Server supplies the chart; the server rechecks permissions, fuel, berth and discovery on departure. */
public final class ChartScreen extends Screen {
    private final VoyageNetwork.Chart data;
    private final Route origin;
    private Route selected;
    private Button depart;
    private int left, top, panelWidth;
    private ChartScreen(VoyageNetwork.Chart data) {
        super(Component.translatable("item.starbridge.navigation_slate"));
        this.data = data;
        this.origin = data.current() >= 0 && data.current() < Route.values().length ? Route.values()[data.current()] : null;
    }
    public static void open(VoyageNetwork.Chart chart) { Minecraft.getInstance().setScreen(new ChartScreen(chart)); }
    private Component name(Route route) { return Component.translatable("route.starbridge." + route.key()); }
    private int y(int row) { return top + 44 + row * 21; }
    @Override protected void init() {
        panelWidth = Math.min(352, width - 16); left = (width - panelWidth) / 2; top = Math.max(2, (height - 208) / 2);
        for (Route route : Route.values()) {
            boolean known = (data.known() & (1 << route.ordinal())) != 0;
            Component label = route == origin ? Component.translatable("screen.starbridge.current", name(route)) :
                    known ? name(route) : Component.translatable("screen.starbridge.uncharted", name(route));
            var b = addRenderableWidget(Button.builder(label, clicked -> { selected = route; updateButton(); })
                    .bounds(left + 10, y(route.ordinal()), panelWidth - 20, 19).build());
            b.active = known && origin != null && route != origin;
        }
        depart = addRenderableWidget(Button.builder(Component.translatable("screen.starbridge.depart"), b -> {
            if (selected != null && depart.active) { VoyageNetwork.setCourse(data.core(), selected); onClose(); }
        }).bounds(left + 10, top + 179, panelWidth - 20, 20).build());
        updateButton();
    }
    private void updateButton() {
        depart.active = selected != null && origin != null && data.energy() >= origin.fareTo(selected) + selected.returnReserve();
    }
    @Override public void render(GuiGraphics g, int mx, int my, float dt) {
        renderBackground(g);
        g.fill(left, top, left + panelWidth, top + 206, 0xF5192630);
        g.fill(left, top, left + panelWidth, top + 2, 0xFF7DD5CC);
        g.drawString(font, title, left + 10, top + 9, 0xFFE8F4F2);
        g.drawString(font, Component.translatable("screen.starbridge.energy", data.energy()), left + 10, top + 27, 0xFFAEC8C9);
        Component fare = selected == null ? Component.translatable("screen.starbridge.choose") : Component.translatable(
                "screen.starbridge.fare", origin.fareTo(selected), selected.returnReserve());
        g.drawString(font, fare, left + 10, top + 158, 0xFFFFD298);
        super.render(g, mx, my, dt);
    }
    @Override public boolean isPauseScreen() { return false; }
}
