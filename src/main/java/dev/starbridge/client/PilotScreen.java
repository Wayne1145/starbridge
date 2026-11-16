package dev.starbridge.client;

import com.mojang.blaze3d.platform.InputConstants;
import dev.starbridge.flight.Vec3d;
import dev.starbridge.network.PilotInputPolicy;
import dev.starbridge.network.PilotModule;
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
    private Vec3d lastInput = Vec3d.ZERO;

    public PilotScreen(PilotModule.PilotState initial) {
        super(Component.literal("Starbridge · 驾驶台"));
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
        addRenderableWidget(new PilotButton(14, y, buttonWidth, 22, "H  悬停", button -> request(PilotModule.Action.HOLD)));
        addRenderableWidget(new PilotButton(20 + buttonWidth, y, buttonWidth, 22, "X  制动", button -> request(PilotModule.Action.BRAKE)));
        addRenderableWidget(new PilotButton(width - buttonWidth - 14, y, buttonWidth, 22, "Esc  离开", button -> onClose()));
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
            int forward = (down(GLFW.GLFW_KEY_W) ? 1 : 0) - (down(GLFW.GLFW_KEY_S) ? 1 : 0);
            int right = (down(GLFW.GLFW_KEY_D) ? 1 : 0) - (down(GLFW.GLFW_KEY_A) ? 1 : 0);
            int up = (down(GLFW.GLFW_KEY_SPACE) ? 1 : 0)
                    - (down(GLFW.GLFW_KEY_LEFT_SHIFT) || down(GLFW.GLFW_KEY_RIGHT_SHIFT) ? 1 : 0);
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

    private boolean movementDown() {
        return down(GLFW.GLFW_KEY_W) || down(GLFW.GLFW_KEY_A) || down(GLFW.GLFW_KEY_S) || down(GLFW.GLFW_KEY_D)
                || down(GLFW.GLFW_KEY_SPACE) || down(GLFW.GLFW_KEY_LEFT_SHIFT) || down(GLFW.GLFW_KEY_RIGHT_SHIFT);
    }

    private boolean down(int key) { return minecraft != null && InputConstants.isKeyDown(minecraft.getWindow().getWindow(), key); }

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

    @Override
    public boolean keyPressed(int key, int scanCode, int modifiers) {
        if (key == GLFW.GLFW_KEY_X) { request(PilotModule.Action.BRAKE); return true; }
        if (key == GLFW.GLFW_KEY_H) { request(PilotModule.Action.HOLD); return true; }
        if (key == GLFW.GLFW_KEY_W || key == GLFW.GLFW_KEY_A || key == GLFW.GLFW_KEY_S || key == GLFW.GLFW_KEY_D
                || key == GLFW.GLFW_KEY_SPACE || key == GLFW.GLFW_KEY_LEFT_SHIFT || key == GLFW.GLFW_KEY_RIGHT_SHIFT) return true;
        return super.keyPressed(key, scanCode, modifiers);
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
        graphics.drawString(font, "STARBRIDGE / PILOT", 24, 21, WHITE, false);
        graphics.drawString(font, "手动平移控制   ·   无地形避障", 24, 37, MUTED, false);
        String link = Util.getMillis() - receivedAt <= 1500 ? "● SERVER LIVE" : "● TELEMETRY DELAY";
        graphics.drawString(font, link, Math.max(24, right - font.width(link) - 12), 21,
                Util.getMillis() - receivedAt <= 1500 ? ACCENT : AMBER, false);

        String problem = !state.notice().isEmpty() ? state.notice() : state.error();
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
        graphics.drawString(font, "航速 / 格·秒⁻¹", 24, telemetryTop + 11, MUTED, false);
        double speed = Math.hypot(Math.hypot(state.vx(), state.vy()), state.vz());
        large(graphics, String.format(Locale.ROOT, "%.2f", speed), 24, telemetryTop + 28, ACCENT);
        graphics.drawString(font, font.plainSubstrByWidth("模式  " + state.mode(), columnWidth - 20), 24, telemetryTop + 65, WHITE, false);

        graphics.drawString(font, "动力储能 / FE", energyX, telemetryTop + 11, MUTED, false);
        String energy = String.format(Locale.ROOT, "%,d", state.energy());
        graphics.drawString(font, font.plainSubstrByWidth(energy, columnWidth - 24), energyX, telemetryTop + 32, WHITE, false);
        int barWidth = Math.max(20, columnWidth - 24);
        graphics.fill(energyX, telemetryTop + 49, energyX + barWidth, telemetryTop + 53, LINE);
        double fraction = state.capacity() > 0 ? Mth.clamp((double) state.energy() / state.capacity(), 0, 1) : 0;
        graphics.fill(energyX, telemetryTop + 49, energyX + (int) (barWidth * fraction), telemetryTop + 53,
                fraction > 0.15 ? ACCENT : AMBER);
        graphics.drawString(font, "推力输入 " + String.format(Locale.ROOT, "%.1f", lastInput.length()), energyX, telemetryTop + 65, MUTED, false);

        graphics.drawString(font, "世界坐标", positionX, telemetryTop + 11, MUTED, false);
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
        String hint = width >= 440 ? "WASD 平移   Space 上升   Shift 下降   右键拖动观察" : "WASD 平移  Space↑ Shift↓  右拖观察";
        graphics.drawString(font, font.plainSubstrByWidth(hint, width - 48), 24, height - 52, MUTED, false);
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
        PilotButton(int x, int y, int width, int height, String text, OnPress action) {
            super(x, y, width, height, Component.literal(text), action, DEFAULT_NARRATION);
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
