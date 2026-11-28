package dev.starbridge.client.orbit;

import dev.starbridge.orbit.OrbitWire;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

import java.util.List;
import java.util.UUID;

/** The navigation scope is local to this helm. Selecting a contact sends no flight command. */
final class TrafficScope {
    private static final ResourceLocation GRID = new ResourceLocation("starbridge_orbit", "textures/gui/traffic_scope.png");
    private static final int PANEL = 0xf510202e, EDGE = 0xff36596a;
    private static final int TEXT = 0xffe6f1ef, DIM = 0xff9bb1bf, SIGNAL = 0xff9bbef7, TRACKED = 0xffffc882;
    private static final int LEFT = 6, TOP = 27;
    private int width, height, radius, cx, cy;
    private UUID selected;
    private Component lastLabel;
    private OrbitWire.Frame plottedFrame;
    private List<Echo> echoes = List.of();
    private record Echo(OrbitWire.Contact contact, TrafficBearing bearing, Component name) {}

    void layout(int screenWidth, int screenHeight) {
        plottedFrame = null;
        width = Math.min(175, Math.max(110, screenWidth * 2 / 5));
        height = Math.min(89, Math.max(52, screenHeight - 88));
        radius = Math.max(10, Math.min((width - 72) / 2, (height - 19) / 2));
        cx = LEFT + 6 + radius;
        cy = TOP + height / 2;
    }

    int detailsX() { return cx + radius + 7; }
    int detailsWidth() { return LEFT + width - detailsX() - 4; }
    int buttonY() { return TOP + height - 20; }
    UUID tracked() { return selected; }

    static Component label(String key, Object... args) {
        return Component.translatable("orbit.starbridge.radar." + key, args);
    }

    void acquire(List<OrbitWire.Contact> contacts) {
        if (selected == null && !contacts.isEmpty()) select(contacts.get(0));
    }

    void next(List<OrbitWire.Contact> contacts) {
        if (contacts.isEmpty()) return;
        int index = 0;
        for (int i = 0; i < contacts.size(); i++) {
            if (contacts.get(i).core().equals(selected)) {
                index = (i + 1) % contacts.size();
                break;
            }
        }
        select(contacts.get(index));
    }

    private void select(OrbitWire.Contact contact) {
        selected = contact.core();
        lastLabel = name(contact);
    }

    private List<Echo> echoes(OrbitWire.Frame frame) {
        if (frame != plottedFrame) {
            echoes = frame.contacts().stream().map(c -> new Echo(c,
                    TrafficBearing.measure(frame.position(), frame.velocity(), frame.yaw(), c.position(), c.velocity(), radius), name(c))).toList();
            plottedFrame = frame;
        }
        return echoes;
    }

    private Echo hit(OrbitWire.Frame frame, double x, double y) {
        double best = 36.001;
        Echo result = null;
        for (var echo : echoes(frame)) {
            var p = echo.bearing();
            double dx = x - (cx + p.x()), dy = y - (cy + p.y());
            double distance = dx * dx + dy * dy;
            if (distance < best) { best = distance; result = echo; }
        }
        return result;
    }

    boolean click(OrbitWire.Frame frame, double x, double y) {
        if (x < LEFT || x >= LEFT + width || y < TOP || y >= TOP + height) return false;
        var chosen = hit(frame, x, y);
        if (chosen != null) select(chosen.contact());
        return true;
    }

    static Component name(OrbitWire.Contact contact) {
        String callSign = contact.core().toString().substring(0, 6);
        return contact.captain().isBlank() ? label("signal", callSign) : Component.literal(contact.captain() + " · " + callSign);
    }

    private void text(GuiGraphics g, Font font, Component label, int y, int color) {
        g.drawString(font, font.plainSubstrByWidth(label.getString(), detailsWidth()), detailsX(), y, color, false);
    }

    void draw(GuiGraphics g, Font font, OrbitWire.Frame frame) {
        g.fill(LEFT, TOP, LEFT + width, TOP + height, PANEL);
        g.renderOutline(LEFT, TOP, width, height, EDGE);
        int side = 2 * radius + 1;
        g.blit(GRID, cx - radius, cy - radius, side, side, 0f, 0f, 81, 81, 81, 81);
        g.hLine(cx - 2, cx + 2, cy + 2, TEXT);
        g.hLine(cx - 1, cx + 1, cy, TEXT);
        g.fill(cx, cy - 2, cx + 1, cy, TEXT);
        Echo track = null;
        for (var echo : echoes(frame)) {
            var p = echo.bearing();
            int x = cx + p.x(), y = cy + p.y();
            g.fill(x - 1, y - 1, x + 2, y + 2, SIGNAL);
            if (echo.contact().core().equals(selected)) track = echo;
        }
        if (track == null) {
            text(g, font, label(selected == null ? "empty" : "lost"), TOP + 7, DIM);
            if (selected != null && lastLabel != null) text(g, font, lastLabel, TOP + 18, DIM);
        } else {
            lastLabel = track.name();
            var bearing = track.bearing();
            g.renderOutline(cx + bearing.x() - 4, cy + bearing.y() - 4, 9, 9, TRACKED);
            text(g, font, track.name(), TOP + 6, TRACKED);
            text(g, font, label("distance", Math.round(bearing.range())), TOP + 18, TEXT);
            long speed = Math.round(Math.abs(bearing.closingSpeed()));
            text(g, font, speed == 0 ? label("steady") : label(bearing.closingSpeed() > 0 ? "closing" : "opening", speed), TOP + 29, SIGNAL);
            if (buttonY() > TOP + 49) {
                long dy = Math.round(bearing.altitude());
                text(g, font, dy == 0 ? label("level") : label(dy > 0 ? "above" : "below", Math.abs(dy)), TOP + 40, DIM);
            }
        }
    }

    void tooltip(GuiGraphics g, Font font, OrbitWire.Frame frame, int x, int y) {
        if (Math.hypot(x - cx, y - cy) > radius + 6) return;
        var hover = hit(frame, x, y);
        if (hover != null) g.renderTooltip(font, hover.name(), x, y);
    }
}
