package dev.starbridge.client.orbit;

import dev.starbridge.orbit.CrewWatch;
import dev.starbridge.orbit.OrbitWire;
import net.minecraft.client.Minecraft;
import net.minecraft.Util;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;

import java.util.Locale;

/** A live instrument for passengers at the helm during an orbital leg. */
public final class CrewFlightScreen extends Screen {
    private static final int PANEL = 0xff102431, EDGE = 0xff456271, TEXT = 0xffecf1ed,
            DIM = 0xffb2c5cc, AQUA = 0xff80dfca, GOLD = 0xffffc982;
    private CrewWatch.Reading state;
    private boolean ended;
    private long lastUpdate = Util.getMillis();
    private int left, top, panelWidth;

    private CrewFlightScreen(CrewWatch.Reading state) { super(tr("title")); this.state = state; }
    private static Component tr(String key, Object... args) { return Component.translatable("orbit.starbridge.crew." + key, args); }
    private static Component port(String id) {
        String key = "route.starbridge." + id;
        if (I18n.exists(key)) return Component.translatable(key);
        String s = id.substring(id.lastIndexOf('.') + 1).replace('_', ' ');
        return Component.literal(s.isEmpty() ? id : Character.toUpperCase(s.charAt(0)) + s.substring(1));
    }

    public static void receive(CrewWatch.Reading next) {
        var mc = Minecraft.getInstance();
        if (mc.screen instanceof CrewFlightScreen screen && screen.state.session().equals(next.session())) {
            if (next.motion() == CrewWatch.Motion.ENDED) screen.ended = true;
            else { screen.state = next; screen.lastUpdate = Util.getMillis(); }
        } else if (next.open()) mc.setScreen(new CrewFlightScreen(next));
    }

    @Override protected void init() {
        panelWidth = Math.min(310, width - 12);
        left = (width - panelWidth) / 2;
        top = Math.max(2, (height - 150) / 2);
        addRenderableWidget(Button.builder(CommonComponents.GUI_DONE, b -> onClose()).bounds(left + (panelWidth - 84) / 2, top + 125, 84, 19).build());
    }
    @Override public boolean isPauseScreen() { return false; }
    @Override public void removed() {
        if (!ended && minecraft != null && minecraft.getConnection() != null) OrbitWire.leaveCrewWatch(state.session());
        super.removed();
    }

    private void text(GuiGraphics g, Component label, int x, int y, int room, int color) {
        g.drawString(font, font.plainSubstrByWidth(label.getString(), Math.max(0, room)), x, y, color, false);
    }
    private Component station() {
        if (state.motion() == CrewWatch.Motion.PAUSED) return tr("idle");
        return switch (state.issuer().station()) {
            case HELM -> tr("station.helm");
            case COMPUTER -> tr("station.computer", state.issuer().computer());
            case AGENT -> tr("station.agent");
        };
    }

    @Override public void render(GuiGraphics g, int mouseX, int mouseY, float part) {
        renderBackground(g);
        g.fill(left, top, left + panelWidth, top + 150, PANEL);
        g.renderOutline(left, top, panelWidth, 150, EDGE);
        text(g, title, left + 9, top + 8, panelWidth / 2 - 10, TEXT);
        boolean waiting = Util.getMillis() - lastUpdate > 2500;
        String modeKey = ended ? "ended" : waiting ? "waiting" : state.motion().name().toLowerCase(Locale.ROOT);
        var mode = tr("motion." + modeKey);
        int right = left + panelWidth - 12;
        int clear = !ended && !waiting && state.motion() != CrewWatch.Motion.PAUSED ? AQUA : GOLD;
        g.drawString(font, mode, right - font.width(mode), top + 8, clear, false);
        g.hLine(left + 8, right, top + 22, EDGE);
        int x = left + 13, half = (right - x) / 2;
        text(g, port(state.from()), x, top + 28, half, DIM);
        var destination = port(state.to());
        g.drawString(font, font.plainSubstrByWidth(destination.getString(), half), right - Math.min(font.width(destination), half), top + 28, TEXT, false);
        int y = top + 47;
        g.hLine(x, right, y, EDGE);
        int marker = x + (int) Math.round((right - x) * Mth.clamp(1 - state.remaining() / Math.max(1, state.span()), 0, 1));
        g.hLine(x, marker, y, AQUA);
        g.renderOutline(x - 2, y - 2, 5, 5, DIM); g.renderOutline(right - 2, y - 2, 5, 5, TEXT);
        g.hLine(marker - 2, marker + 2, y - 3, GOLD);
        g.hLine(marker - 1, marker + 1, y - 2, GOLD);
        g.fill(marker, y - 1, marker + 1, y + 1, GOLD);
        text(g, tr("distance", Math.round(state.remaining())), x, top + 56, half, TEXT);
        text(g, tr("speed", Math.round(state.speed())), x + half + 4, top + 56, half, TEXT);
        Component captain = state.captain().isEmpty() ? tr("offline") : Component.literal(state.captain());
        text(g, tr("captain", captain), x, top + 75, half, DIM);
        text(g, tr("order", station()), x + half + 4, top + 75, half, DIM);
        text(g, tr("energy", state.energy()), x, top + 94, half, TEXT);
        text(g, tr("reserve", state.reserve()), x + half + 4, top + 94, half, GOLD);
        int fuelWidth = right - x;
        g.fill(x, top + 108, right + 1, top + 112, EDGE);
        int protectedWidth = (int) Math.round(fuelWidth * Mth.clamp(state.reserve() / (double)Math.max(1, state.energy()), 0, 1));
        if (state.energy() > 0) {
            g.fill(x, top + 108, x + protectedWidth, top + 112, GOLD);
            g.fill(x + protectedWidth, top + 108, right + 1, top + 112, AQUA);
        }
        super.render(g, mouseX, mouseY, part);
    }
}
