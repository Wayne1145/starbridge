package dev.starbridge.client;

import com.mojang.blaze3d.platform.InputConstants;
import dev.starbridge.flight.Vec3d;
import dev.starbridge.network.PilotInputPolicy;
import dev.starbridge.network.PilotModule;
import dev.starbridge.text.Lang;
import net.minecraft.Util;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import org.lwjgl.glfw.GLFW;

import java.util.Locale;
import java.util.UUID;

/** A non-pausing view of server telemetry with manual translation, yaw, launch and star-jump controls. */
@OnlyIn(Dist.CLIENT)
public final class PilotScreen extends Screen {
    private static final int PANEL = 0xE811181F;
    private static final int LINE = 0xFF30404B;
    private static final int WHITE = 0xFFE7F0F4;
    private static final int MUTED = 0xFF99ACB9;
    private static final int ACCENT = 0xFF66D7CA;
    private static final int AMBER = 0xFFFFC578;
    private static final int VIOLET = 0xFFB59CFF;
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
    private Vec3d lastInput = Vec3d.ZERO;
    private double lastYaw;

    public PilotScreen(PilotModule.PilotState initial) {
        super(Component.translatable("starbridge.pilot.title"));
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
        int gap = 6;
        int buttonWidth = Math.max(54, Math.min(90, (width - 28 - gap * 4) / 5));
        int x = 14;
        addRenderableWidget(new PilotButton(x, y, buttonWidth, 22, "starbridge.pilot.button.launch", b -> request(PilotModule.Action.LAUNCH)));
        x += buttonWidth + gap;
        addRenderableWidget(new PilotButton(x, y, buttonWidth, 22, "starbridge.pilot.button.hold", b -> request(PilotModule.Action.HOLD)));
        x += buttonWidth + gap;
        addRenderableWidget(new PilotButton(x, y, buttonWidth, 22, "starbridge.pilot.button.brake", b -> request(PilotModule.Action.BRAKE)));
        x += buttonWidth + gap;
        addRenderableWidget(new PilotButton(x, y, buttonWidth, 22, "starbridge.pilot.button.jump", b -> request(PilotModule.Action.JUMP)));
        addRenderableWidget(new PilotButton(width - buttonWidth - 14, y, buttonWidth, 22, "starbridge.pilot.button.leave", b -> onClose()));
    }

    @Override
    public boolean isPauseScreen() { return false; }

    @Override
    public void tick() {
        ticks++;
        if (minecraft == null || minecraft.player == null || minecraft.getConnection() == null) return;
        boolean controls = movementDown();
        if (requireNeutral && !controls) requireNeutral = false;
        if (ticks - lastSentTick < 2) return;
        if (pending != null) {
            transmit(pending, Vec3d.ZERO, 0);
            pending = null;
            wasDriving = false;
            return;
        }
        if (Util.getMillis() - receivedAt > 1500) {
            if (!staleHoldSent) {
                transmit(PilotModule.Action.HOLD, Vec3d.ZERO, 0);
                staleHoldSent = true;
                requireNeutral = true;
            } else transmit(PilotModule.Action.HEARTBEAT, Vec3d.ZERO, 0);
            wasDriving = false;
            return;
        }
        if (controls && !requireNeutral) {
            int forward = (down(GLFW.GLFW_KEY_W) ? 1 : 0) - (down(GLFW.GLFW_KEY_S) ? 1 : 0);
            int right = (down(GLFW.GLFW_KEY_D) ? 1 : 0) - (down(GLFW.GLFW_KEY_A) ? 1 : 0);
            int up = (down(GLFW.GLFW_KEY_SPACE) ? 1 : 0)
                    - (down(GLFW.GLFW_KEY_LEFT_SHIFT) || down(GLFW.GLFW_KEY_RIGHT_SHIFT) ? 1 : 0);
            int turn = (down(GLFW.GLFW_KEY_Q) ? 1 : 0) - (down(GLFW.GLFW_KEY_E) ? 1 : 0);
            Vec3d direction = PilotInputPolicy.cameraAcceleration(minecraft.player.getYRot(), forward, right, up);
            if (direction.length() > 0 || turn != 0) {
                transmit(PilotModule.Action.DRIVE, direction, turn);
                wasDriving = true;
                return;
            }
        }
        if (wasDriving) {
            transmit(PilotModule.Action.HOLD, Vec3d.ZERO, 0);
            wasDriving = false;
        } else transmit(PilotModule.Action.HEARTBEAT, Vec3d.ZERO, 0);
    }

    private boolean movementDown() {
        return down(GLFW.GLFW_KEY_W) || down(GLFW.GLFW_KEY_A) || down(GLFW.GLFW_KEY_S) || down(GLFW.GLFW_KEY_D)
                || down(GLFW.GLFW_KEY_Q) || down(GLFW.GLFW_KEY_E)
                || down(GLFW.GLFW_KEY_SPACE) || down(GLFW.GLFW_KEY_LEFT_SHIFT) || down(GLFW.GLFW_KEY_RIGHT_SHIFT);
    }

    private boolean down(int key) { return minecraft != null && InputConstants.isKeyDown(minecraft.getWindow().getWindow(), key); }

    private void request(PilotModule.Action action) {
        pending = action;
        requireNeutral = true;
    }

    private void transmit(PilotModule.Action action, Vec3d vector, double yaw) {
        if (serverClosed || closeSent || minecraft == null || minecraft.getConnection() == null) return;
        PilotModule.sendInput(coreId, action, vector.x(), vector.y(), vector.z(), yaw);
        lastSentTick = ticks;
        lastInput = action == PilotModule.Action.DRIVE ? vector : Vec3d.ZERO;
        lastYaw = action == PilotModule.Action.DRIVE ? yaw : 0;
    }

    @Override
    public boolean keyPressed(int key, int scanCode, int modifiers) {
        switch (key) {
            case GLFW.GLFW_KEY_X -> { request(PilotModule.Action.BRAKE); return true; }
            case GLFW.GLFW_KEY_H -> { request(PilotModule.Action.HOLD); return true; }
            case GLFW.GLFW_KEY_L -> { request(PilotModule.Action.LAUNCH); return true; }
            case GLFW.GLFW_KEY_J -> { request(PilotModule.Action.JUMP); return true; }
            case GLFW.GLFW_KEY_W, GLFW.GLFW_KEY_A, GLFW.GLFW_KEY_S, GLFW.GLFW_KEY_D, GLFW.GLFW_KEY_Q, GLFW.GLFW_KEY_E,
                    GLFW.GLFW_KEY_SPACE, GLFW.GLFW_KEY_LEFT_SHIFT, GLFW.GLFW_KEY_RIGHT_SHIFT -> { return true; }
            default -> { return super.keyPressed(key, scanCode, modifiers); }
        }
    }

    @Override
    public boolean mouseClicked(double x, double y, int button) {
        if (button == GLFW.GLFW_MOUSE_BUTTON_RIGHT) { setDragging(true); return true; }
        return super.mouseClicked(x, y, button);
    }

    @Override
    public boolean mouseReleased(double x, double y, int button) {
        if (button == GLFW.GLFW_MOUSE_BUTTON_RIGHT) { setDragging(false); return true; }
        return super.mouseReleased(x, y, button);
    }

    @Override
    public boolean mouseDragged(double x, double y, int button, double deltaX, double deltaY) {
        if (button == GLFW.GLFW_MOUSE_BUTTON_RIGHT && minecraft != null && minecraft.player != null) {
            minecraft.player.setYRot(minecraft.player.getYRot() + (float) deltaX * 0.35F);
            minecraft.player.setXRot(Mth.clamp(minecraft.player.getXRot() + (float) deltaY * 0.35F, -80, 80));
            return true;
        }
        return super.mouseDragged(x, y, button, deltaX, deltaY);
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
        if (minecraft != null && minecraft.getConnection() != null) PilotModule.sendInput(coreId, PilotModule.Action.CLOSE, 0, 0, 0, 0);
    }

    public void closedByServer(String notice) {
        serverClosed = true;
        if (minecraft != null) {
            minecraft.setScreen(null);
            if (minecraft.player != null) minecraft.player.displayClientMessage(Lang.keyOrText(notice), false);
        }
    }

    private Component t(String key, Object... args) { return Component.translatable("starbridge.pilot." + key, args); }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        // Deliberately preserve the live world view instead of drawing an opaque menu backdrop.
        int left = 12, right = width - 12;
        boolean live = Util.getMillis() - receivedAt <= 1500;
        graphics.fill(left, 12, right, 54, PANEL);
        graphics.fill(left, 12, left + 3, 54, ACCENT);
        graphics.drawString(font, t("header"), 24, 21, WHITE, false);
        graphics.drawString(font, t(state.docked() ? "subtitle_docked" : "subtitle"), 24, 37, state.docked() ? AMBER : MUTED, false);
        Component link = t(live ? "link_live" : "link_delay");
        graphics.drawString(font, link, Math.max(24, right - font.width(link) - 12), 21, live ? ACCENT : AMBER, false);

        String problemKey = !state.notice().isEmpty() ? state.notice() : state.error();
        if (!problemKey.isEmpty()) {
            Component problem = Lang.keyOrText(problemKey);
            graphics.fill(left, 59, right, 78, 0xE83C2E21);
            graphics.drawString(font, font.substrByWidth(problem, width - 48).getString(), 24, 65, AMBER, false);
            if (mouseY >= 59 && mouseY <= 78) graphics.renderTooltip(font, problem, mouseX, mouseY);
        }

        int telemetryTop = height - 168;
        int telemetryBottom = height - 65;
        graphics.fill(left, telemetryTop, right, telemetryBottom, PANEL);
        graphics.fill(left, telemetryTop, right, telemetryTop + 1, LINE);
        int columnWidth = (width - 24) / 3;
        int energyX = left + columnWidth + 12;
        int positionX = left + columnWidth * 2 + 12;
        graphics.fill(left + columnWidth, telemetryTop + 12, left + columnWidth + 1, telemetryBottom - 12, LINE);
        graphics.fill(left + columnWidth * 2, telemetryTop + 12, left + columnWidth * 2 + 1, telemetryBottom - 12, LINE);

        graphics.drawString(font, t("speed"), 24, telemetryTop + 11, MUTED, false);
        double speed = Math.hypot(Math.hypot(state.vx(), state.vy()), state.vz());
        large(graphics, String.format(Locale.ROOT, "%.2f", speed), 24, telemetryTop + 28, ACCENT);
        graphics.drawString(font, t("mode", t("mode." + state.mode().toLowerCase(Locale.ROOT))), 24, telemetryTop + 62, WHITE, false);
        graphics.drawString(font, t("attitude", String.format(Locale.ROOT, "%03.0f", state.heading()),
                String.format(Locale.ROOT, "%.1f", state.tilt())), 24, telemetryTop + 76, MUTED, false);

        graphics.drawString(font, t("energy"), energyX, telemetryTop + 11, MUTED, false);
        String energy = String.format(Locale.ROOT, "%,d / %,d", state.energy(), state.capacity());
        graphics.drawString(font, font.plainSubstrByWidth(energy, columnWidth - 24), energyX, telemetryTop + 30, WHITE, false);
        int barWidth = Math.max(20, columnWidth - 24);
        graphics.fill(energyX, telemetryTop + 45, energyX + barWidth, telemetryTop + 49, LINE);
        double fraction = state.capacity() > 0 ? Mth.clamp((double) state.energy() / state.capacity(), 0, 1) : 0;
        graphics.fill(energyX, telemetryTop + 45, energyX + (int) (barWidth * fraction), telemetryTop + 49,
                fraction > 0.15 ? ACCENT : AMBER);
        graphics.drawString(font, t("thrust", String.format(Locale.ROOT, "%.1f", lastInput.length()),
                lastYaw > 0 ? "◀" : lastYaw < 0 ? "▶" : "·"), energyX, telemetryTop + 58, MUTED, false);
        Component jump = state.jumpReady()
                ? t("jump_ready", String.format(Locale.ROOT, "%,d", state.jumpCost()))
                : t("jump_blocked", state.jumpBlocker().isEmpty() ? t("jump_unknown") : Lang.keyOrText(state.jumpBlocker()));
        graphics.drawString(font, font.substrByWidth(jump, columnWidth - 20).getString(), energyX, telemetryTop + 74,
                state.jumpReady() ? VIOLET : MUTED, false);

        graphics.drawString(font, t("position", dimensionName()), positionX, telemetryTop + 11, MUTED, false);
        coordinate(graphics, "X", state.x(), positionX, telemetryTop + 29, columnWidth - 24);
        coordinate(graphics, "Y", state.y(), positionX, telemetryTop + 43, columnWidth - 24);
        coordinate(graphics, "Z", state.z(), positionX, telemetryTop + 57, columnWidth - 24);

        if (telemetryTop > 120) compass(graphics, width / 2, (84 + telemetryTop) / 2);

        graphics.fill(left, height - 59, right, height - 10, PANEL);
        Component hint = t(width >= 520 ? "hint" : "hint_short");
        graphics.drawString(font, font.substrByWidth(hint, width - 48).getString(), 24, height - 52, MUTED, false);
        super.render(graphics, mouseX, mouseY, partialTick);
    }

    private Component dimensionName() {
        String dimension = state.dimension();
        if (dimension.equals("starbridge:survey_moon")) return t("dimension.moon");
        if (dimension.equals("minecraft:overworld")) return t("dimension.overworld");
        return Component.literal(dimension);
    }

    /** Heading ribbon and crosshair; the ribbon scrolls as the ship yaws. */
    private void compass(GuiGraphics graphics, int cx, int cy) {
        int half = 90;
        graphics.fill(cx - half, cy - 34, cx + half, cy - 20, 0x99111820);
        double heading = state.heading();
        String[] labels = {"S", "SW", "W", "NW", "N", "NE", "E", "SE"};
        for (int i = 0; i < 8; i++) {
            double angle = i * 45 - heading;
            angle = ((angle + 540) % 360) - 180;
            int x = cx + (int) Math.round(angle * 1.5);
            if (Math.abs(x - cx) > half - 6) continue;
            graphics.drawCenteredString(font, labels[i], x, cy - 31, i % 2 == 0 ? WHITE : MUTED);
        }
        graphics.fill(cx, cy - 36, cx + 1, cy - 18, ACCENT);
        graphics.fill(cx - 10, cy, cx - 3, cy + 1, 0x997DD5CC);
        graphics.fill(cx + 3, cy, cx + 10, cy + 1, 0x997DD5CC);
        graphics.fill(cx, cy - 10, cx + 1, cy - 3, 0x997DD5CC);
        graphics.fill(cx, cy + 3, cx + 1, cy + 10, 0x997DD5CC);
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
        PilotButton(int x, int y, int width, int height, String key, OnPress action) {
            super(x, y, width, height, Component.translatable(key), action, DEFAULT_NARRATION);
        }

        @Override
        protected void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
            boolean highlighted = isHoveredOrFocused();
            graphics.fill(getX(), getY(), getX() + getWidth(), getY() + getHeight(), highlighted ? 0xFF24423F : 0xFF1C2B33);
            graphics.fill(getX(), getY() + getHeight() - 1, getX() + getWidth(), getY() + getHeight(), highlighted ? ACCENT : LINE);
            graphics.drawCenteredString(font, getMessage(), getX() + getWidth() / 2, getY() + 7, highlighted ? ACCENT : WHITE);
        }
    }
}
