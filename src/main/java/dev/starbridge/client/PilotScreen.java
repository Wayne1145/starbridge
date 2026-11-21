package dev.starbridge.client;

import com.mojang.blaze3d.platform.InputConstants;
import dev.starbridge.flight.Vec3d;
import dev.starbridge.network.PilotInputPolicy;
import dev.starbridge.network.PilotModule;
import net.minecraft.Util;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import org.lwjgl.glfw.GLFW;

import java.util.EnumSet;
import java.util.Locale;
import java.util.UUID;

import dev.starbridge.client.SurfaceControls.Binding;

/** A non-pausing view of server telemetry. This console supplies translation thrust, not obstacle avoidance. */
@OnlyIn(Dist.CLIENT)
public final class PilotScreen extends Screen {
    private static final int PANEL = 0xE811181F;
    private static final int LINE = 0xFF30404B;
    private static final int WHITE = 0xFFE7F0F4;
    private static final int MUTED = 0xFF99ACB9;
    private static final int ACCENT = 0xFF66D7CA;
    private static final int AMBER = 0xFFFFC578;
    private final UUID coreId;
    private PilotModule.PilotState state;
    private long receivedAt;
    private int ticks;
    private int lastSentTick = -2;
    private boolean closeSent;
    private boolean serverClosed;
    private boolean wasDriving;
    private boolean requireNeutral;
    private boolean staleHoldSent;
    private PilotModule.Action pending;
    private final EnumSet<Binding> held = EnumSet.noneOf(Binding.class);
    private double lastMouseX = Double.NaN, lastMouseY;
    private Vec3d lastInput = Vec3d.ZERO;

    public PilotScreen(PilotModule.PilotState initial) {
        super(tr("title"));
        coreId = initial.coreId();
        update(initial);
    }

    public UUID coreId() { return coreId; }

    public void update(PilotModule.PilotState packet) {
        if (!coreId.equals(packet.coreId())) return;
        state = packet;
        receivedAt = Util.getMillis();
        staleHoldSent = false;
    }

    @Override
    protected void init() {
        int y = height - 35;
        int buttonWidth = Math.max(64, Math.min(96, (width - 42) / 3));
        addRenderableWidget(new PilotButton(14, y, buttonWidth, 22, tr("hold", Binding.HOLD.label()), button -> request(PilotModule.Action.HOLD)));
        addRenderableWidget(new PilotButton(20 + buttonWidth, y, buttonWidth, 22, tr("brake", Binding.BRAKE.label()), button -> request(PilotModule.Action.BRAKE)));
        addRenderableWidget(new PilotButton(width - buttonWidth - 14, y, buttonWidth, 22, tr("leave"), button -> onClose()));
    }

    @Override
    public boolean isPauseScreen() { return false; }

    @Override
    public void tick() {
        ticks++;
        if (minecraft == null || minecraft.player == null || minecraft.getConnection() == null) return;
        if (!minecraft.isWindowActive()) {
            held.clear();
            lastMouseX = Double.NaN;
        }
        int forward = axis(Binding.FORWARD, Binding.BACK);
        int right = axis(Binding.RIGHT, Binding.LEFT);
        int up = axis(Binding.UP, Binding.DOWN);
        boolean controls = forward != 0 || right != 0 || up != 0;
        if (requireNeutral && !controls) requireNeutral = false;
        if (ticks - lastSentTick < 2) return;
        if (pending != null) {
            transmit(pending, Vec3d.ZERO);
            pending = null;
            wasDriving = false;
            return;
        }
        if (Util.getMillis() - receivedAt > 1500) {
            if (!staleHoldSent) {
                transmit(PilotModule.Action.HOLD, Vec3d.ZERO);
                staleHoldSent = true;
                requireNeutral = true;
            } else transmit(PilotModule.Action.HEARTBEAT, Vec3d.ZERO);
            wasDriving = false;
            return;
        }
        if (controls && !requireNeutral) {
            Vec3d direction = PilotInputPolicy.cameraAcceleration(minecraft.player.getYRot(), forward, right, up);
            if (direction.length() > 0) {
                transmit(PilotModule.Action.DRIVE, direction);
                wasDriving = true;
                return;
            }
        }
        if (wasDriving) {
            transmit(PilotModule.Action.HOLD, Vec3d.ZERO);
            wasDriving = false;
        } else transmit(PilotModule.Action.HEARTBEAT, Vec3d.ZERO);
    }

    private static Component tr(String key, Object... args) {
        return Component.translatable("surface.starbridge." + key, args);
    }

    private int axis(Binding positive, Binding negative) {
        return (held.contains(positive) ? 1 : 0) - (held.contains(negative) ? 1 : 0);
    }

    private void request(PilotModule.Action action) {
        pending = action;
        requireNeutral = true;
    }

    private void transmit(PilotModule.Action action, Vec3d vector) {
        if (serverClosed || closeSent || minecraft == null || minecraft.getConnection() == null) return;
        PilotModule.sendInput(coreId, action, vector.x(), vector.y(), vector.z());
        lastSentTick = ticks;
        lastInput = action == PilotModule.Action.DRIVE ? vector : Vec3d.ZERO;
    }

    private boolean press(InputConstants.Key input) {
        for (Binding binding : Binding.values()) {
            if (!binding.mapping.isActiveAndMatches(input)) continue;
            if (held.add(binding)) {
                if (binding == Binding.HOLD) request(PilotModule.Action.HOLD);
                if (binding == Binding.BRAKE) request(PilotModule.Action.BRAKE);
                if (binding == Binding.LOOK) lastMouseX = Double.NaN;
            }
            return true;
        }
        return false;
    }

    @Override public boolean keyPressed(int key, int scanCode, int modifiers) {
        if (key == GLFW.GLFW_KEY_ESCAPE) return super.keyPressed(key, scanCode, modifiers);
        return press(InputConstants.getKey(key, scanCode)) || super.keyPressed(key, scanCode, modifiers);
    }

    @Override public boolean keyReleased(int key, int scanCode, int modifiers) {
        return held.removeIf(binding -> binding.mapping.matches(key, scanCode)) || super.keyReleased(key, scanCode, modifiers);
    }

    @Override public boolean mouseClicked(double x, double y, int button) {
        return super.mouseClicked(x, y, button) || press(InputConstants.Type.MOUSE.getOrCreate(button));
    }

    @Override public boolean mouseReleased(double x, double y, int button) {
        boolean released = held.removeIf(binding -> binding.mapping.matchesMouse(button));
        return super.mouseReleased(x, y, button) || released;
    }

    @Override public void mouseMoved(double x, double y) {
        if (held.contains(Binding.LOOK) && !Double.isNaN(lastMouseX) && minecraft != null && minecraft.player != null) {
            minecraft.player.setYRot(minecraft.player.getYRot() + (float) (x - lastMouseX) * 0.35F);
            minecraft.player.setXRot(Mth.clamp(minecraft.player.getXRot() + (float) (y - lastMouseY) * 0.35F, -80, 80));
        }
        lastMouseX = x;
        lastMouseY = y;
        super.mouseMoved(x, y);
    }

    @Override
    public void onClose() {
        sendClose();
        if (minecraft != null) minecraft.setScreen(null);
    }

    @Override
    public void removed() { sendClose(); }

    private void sendClose() {
        if (closeSent || serverClosed) return;
        closeSent = true;
        if (minecraft != null && minecraft.getConnection() != null) PilotModule.sendInput(coreId, PilotModule.Action.CLOSE, 0, 0, 0);
    }

    public void closedByServer(String notice) {
        serverClosed = true;
        if (minecraft != null) {
            minecraft.setScreen(null);
            if (minecraft.player != null) minecraft.player.displayClientMessage(Component.literal(notice), false);
        }
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        // Deliberately preserve the live world view instead of drawing an opaque menu backdrop.
        int left = 12, right = width - 12;
        graphics.fill(left, 12, right, 54, PANEL);
        graphics.fill(left, 12, left + 3, 54, ACCENT);
        graphics.drawString(font, title, 24, 21, WHITE, false);
        graphics.drawString(font, tr("description"), 24, 37, MUTED, false);
        Component link = tr(Util.getMillis() - receivedAt <= 1500 ? "live" : "delay");
        graphics.drawString(font, link, Math.max(24, right - font.width(link) - 12), 21,
                Util.getMillis() - receivedAt <= 1500 ? ACCENT : AMBER, false);

        String problem = !state.notice().isEmpty() ? state.notice() : state.error();
        if (problem.isEmpty() && state.mode().equals("LAUNCHING")) problem = "surface.starbridge.status.launching";
        if (problem.startsWith("surface.starbridge.")) problem = Component.translatable(problem).getString();
        if (!problem.isEmpty()) {
            graphics.fill(left, 59, right, 78, 0xE83C2E21);
            graphics.drawString(font, font.plainSubstrByWidth(problem, width - 48), 24, 65, AMBER, false);
            if (mouseY >= 59 && mouseY <= 78) graphics.renderTooltip(font, Component.literal(problem), mouseX, mouseY);
        }

        int telemetryTop = height - 154;
        int telemetryBottom = height - 65;
        graphics.fill(left, telemetryTop, right, telemetryBottom, PANEL);
        graphics.fill(left, telemetryTop, right, telemetryTop + 1, LINE);
        int columnWidth = (width - 24) / 3;
        int energyX = left + columnWidth + 12;
        int positionX = left + columnWidth * 2 + 12;
        graphics.fill(left + columnWidth, telemetryTop + 12, left + columnWidth + 1, telemetryBottom - 12, LINE);
        graphics.fill(left + columnWidth * 2, telemetryTop + 12, left + columnWidth * 2 + 1, telemetryBottom - 12, LINE);
        graphics.drawString(font, tr("speed"), 24, telemetryTop + 11, MUTED, false);
        double speed = Math.hypot(Math.hypot(state.vx(), state.vy()), state.vz());
        large(graphics, String.format(Locale.ROOT, "%.2f", speed), 24, telemetryTop + 28, ACCENT);
        graphics.drawString(font, font.plainSubstrByWidth(tr("mode", tr("mode." + state.mode().toLowerCase(Locale.ROOT))).getString(), columnWidth - 20), 24, telemetryTop + 65, WHITE, false);

        graphics.drawString(font, tr("energy"), energyX, telemetryTop + 11, MUTED, false);
        String energy = String.format(Locale.ROOT, "%,d", state.energy());
        graphics.drawString(font, font.plainSubstrByWidth(energy, columnWidth - 24), energyX, telemetryTop + 32, WHITE, false);
        int barWidth = Math.max(20, columnWidth - 24);
        graphics.fill(energyX, telemetryTop + 49, energyX + barWidth, telemetryTop + 53, LINE);
        double fraction = state.capacity() > 0 ? Mth.clamp((double) state.energy() / state.capacity(), 0, 1) : 0;
        graphics.fill(energyX, telemetryTop + 49, energyX + (int) (barWidth * fraction), telemetryTop + 53,
                fraction > 0.15 ? ACCENT : AMBER);
        graphics.drawString(font, tr("thrust", String.format(Locale.ROOT, "%.1f", lastInput.length())), energyX, telemetryTop + 65, MUTED, false);

        graphics.drawString(font, tr("position"), positionX, telemetryTop + 11, MUTED, false);
        coordinate(graphics, "X", state.x(), positionX, telemetryTop + 29, columnWidth - 24);
        coordinate(graphics, "Y", state.y(), positionX, telemetryTop + 43, columnWidth - 24);
        coordinate(graphics, "Z", state.z(), positionX, telemetryTop + 57, columnWidth - 24);

        if (telemetryTop > 110) {
            int cx = width / 2, cy = (80 + telemetryTop) / 2;
            graphics.fill(cx - 10, cy, cx - 3, cy + 1, 0x997DD5CC);
            graphics.fill(cx + 3, cy, cx + 10, cy + 1, 0x997DD5CC);
            graphics.fill(cx, cy - 10, cx + 1, cy - 3, 0x997DD5CC);
            graphics.fill(cx, cy + 3, cx + 1, cy + 10, 0x997DD5CC);
        }
        graphics.fill(left, height - 59, right, height - 10, PANEL);
        Component hint = tr("hint", Binding.FORWARD.label(), Binding.BACK.label(), Binding.LEFT.label(), Binding.RIGHT.label(),
                Binding.UP.label(), Binding.DOWN.label(), Binding.LOOK.label());
        graphics.drawString(font, font.plainSubstrByWidth(hint.getString(), width - 48), 24, height - 52, MUTED, false);
        if (mouseY >= height - 59 && mouseY < height - 36) graphics.renderTooltip(font, font.split(hint, width - 48), mouseX, mouseY);
        super.render(graphics, mouseX, mouseY, partialTick);
    }

    private void coordinate(GuiGraphics graphics, String axis, double value, int x, int y, int maximumWidth) {
        graphics.drawString(font, font.plainSubstrByWidth(axis + "  " + String.format(Locale.ROOT, "%.1f", value), maximumWidth), x, y, WHITE, false);
    }

    private void large(GuiGraphics graphics, String text, int x, int y, int color) {
        graphics.pose().pushPose();
        graphics.pose().scale(2, 2, 1);
        graphics.drawString(font, text, x / 2.0F, y / 2.0F, color, false);
        graphics.pose().popPose();
    }

    private final class PilotButton extends Button {
        PilotButton(int x, int y, int width, int height, Component text, OnPress action) {
            super(x, y, width, height, text, action, DEFAULT_NARRATION);
            setTooltip(Tooltip.create(text));
        }

        @Override
        protected void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
            boolean highlighted = isHoveredOrFocused();
            graphics.fill(getX(), getY(), getX() + getWidth(), getY() + getHeight(), highlighted ? 0xFF24423F : 0xFF1C2B33);
            graphics.fill(getX(), getY() + getHeight() - 1, getX() + getWidth(), getY() + getHeight(), highlighted ? ACCENT : LINE);
            graphics.drawCenteredString(font, font.plainSubstrByWidth(getMessage().getString(), width - 4), getX() + getWidth() / 2, getY() + 7, highlighted ? ACCENT : WHITE);
        }
    }
}
