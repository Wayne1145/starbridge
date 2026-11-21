package dev.starbridge.client.orbit;

import com.mojang.blaze3d.platform.InputConstants;

import dev.starbridge.Starbridge;

import net.minecraft.client.*;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.*;
import net.minecraft.client.gui.screens.inventory.*;
import net.minecraft.client.multiplayer.*;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.minecraft.core.*;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.phys.*;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import org.lwjgl.glfw.GLFW;

import java.nio.file.*;

@Mod.EventBusSubscriber(modid = Starbridge.ID, value = Dist.CLIENT)
public class FixtureClient {
    static final int BASE = 128;
    static int clock, step, activeTicks, join, wait;
    static boolean begun;

    static void record(String message) throws Exception {
        Files.writeString(
                Path.of("orbit-ui-actions.log"),
                message + "\n",
                java.nio.file.StandardOpenOption.CREATE,
                java.nio.file.StandardOpenOption.APPEND);
    }

    static Button button(OrbitScreen screen, String name) {
        return screen.children().stream()
                .filter(
                        w ->
                                w instanceof Button b
                                        && b.getMessage().getContents()
                                                instanceof
                                                net.minecraft.network.chat.contents
                                                        .TranslatableContents
                                                t
                                        && t.getKey().equals("orbit.starbridge." + name))
                .map(w -> (Button) w)
                .findFirst()
                .orElseThrow();
    }

    static void click(OrbitScreen sc, String name) throws Exception {
        var b = button(sc, name);
        if (!b.active) throw new AssertionError(name + " disabled");
        int x = b.getX() + b.getWidth() / 2, y = b.getY() + 6;
        sc.mouseClicked(x, y, 0);
        sc.mouseReleased(x, y, 0);
        record("clicked " + name);
    }

    static void use(Minecraft mc, BlockPos p) {
        var hit = new BlockHitResult(Vec3.atCenterOf(p).add(0, 0, -.5), Direction.NORTH, p, false);
        mc.gameMode.useItemOn(mc.player, InteractionHand.MAIN_HAND, hit);
    }

    static void capture(Minecraft mc, String name) {
        Screenshot.grab(
                mc.gameDirectory,
                name + ".png",
                mc.getMainRenderTarget(),
                c -> Starbridge.LOGGER.info("ORBIT_CAPTURE {}", c.getString()));
    }

    @SubscribeEvent
    public static void tick(TickEvent.ClientTickEvent event) throws Exception {
        if (event.phase != TickEvent.Phase.END
                || !Boolean.getBoolean("starbridge.orbitFixture")
                || !Files.isRegularFile(Path.of("starbridge-orbit.fixture"))) return;
        ++clock;
        var mc = Minecraft.getInstance();
        if (mc.player == null) {
            if (mc.screen instanceof TitleScreen
                    && mc.getOverlay() == null
                    && clock > 60
                    && join++ == 0) {
                String addr = "127.0.0.1:25586";
                ConnectScreen.startConnecting(
                        mc.screen,
                        mc,
                        ServerAddress.parseString(addr),
                        new ServerData("Starbridge orbital lab", addr, true),
                        false);
            }
            return;
        }
        if (Files.isRegularFile(Path.of("actions-complete"))) return;
        // Give the isolated GLFW window its own test input without moving the user's desktop
        // pointer or pressing global keys.
        mc.setWindowActive(true);
        if (mc.getUser().getName().equals("OrbitCaptain")) {
            if (mc.screen instanceof OrbitScreen helm) {
                ++activeTicks;
                if (activeTicks == 12) {
                    var mapping = OrbitClient.Binding.FORWARD.mapping;
                    mapping.setKey(InputConstants.Type.KEYSYM.getOrCreate(GLFW.GLFW_KEY_I));
                    KeyMapping.resetMapping();
                    helm.keyPressed(GLFW.GLFW_KEY_I, 0, 0);
                    helm.keyPressed(GLFW.GLFW_KEY_A, 0, 0);
                    record("pressed remapped thrust I and turn A");
                }
                if (activeTicks == 33) {
                    helm.keyReleased(GLFW.GLFW_KEY_I, 0, 0);
                    helm.keyReleased(GLFW.GLFW_KEY_A, 0, 0);
                    capture(mc, "flight");
                }
                if (activeTicks == 42) click(helm, "cruise");
                if (activeTicks == 100) click(helm, "brake");
                if (activeTicks == 140) {
                    click(helm, "landing");
                }
                if (activeTicks == 145) {
                    if (button(helm, "touchdown").active)
                        throw new AssertionError("Premature landing button");
                    helm.mouseClicked(helm.width / 4, helm.height / 2, 0);
                    record("selected landing coordinate; descent locked while far from Moon");
                }
                if (activeTicks == 149) capture(mc, "approach");
                if (activeTicks == 155) {
                    click(helm, "leave");
                    step = 1;
                    wait = clock;
                }
                if (step == 2 && activeTicks > 160) {
                    record("right-click reopened helm after returning to cabin");
                    Files.writeString(Path.of("actions-complete"), "true");
                }
            } else if (step == 1 && mc.screen == null && clock - wait > 8) {
                use(mc, new BlockPos(3, BASE + 1, 1));
                step = 2;
            }
        } else {
            if (!begun
                    && mc.player.getY() > BASE + .5
                    && mc.player.getX() < 5
                    && mc.player.getX() > 0) {
                begun = true;
                wait = clock;
                record("engineer on lower deck");
            }
            if (!begun || clock - wait < 64) return;
            if (step == 0) {
                if (mc.player.getZ() < 7.45) {
                    mc.player.setYRot(0);
                    mc.player.setXRot(0);
                    mc.options.keyUp.setDown(true);
                } else {
                    mc.options.keyUp.setDown(false);
                    use(mc, new BlockPos(2, BASE + 3, 9));
                    step = 1;
                    wait = clock - 64;
                }
            }
            if (step == 1 && mc.screen instanceof ContainerScreen chest && clock - wait > 67) {
                mc.gameMode.handleInventoryMouseClick(
                        chest.getMenu().containerId, 0, 0, ClickType.QUICK_MOVE, mc.player);
                record("opened chest on upper deck and shift-clicked cargo");
                step = 2;
                wait = clock - 64;
            }
            if (step == 2 && clock - wait > 70) {
                mc.player.closeContainer();
                step = 3;
                wait = clock - 64;
            }
            if (step == 3 && clock - wait > 69) {
                use(mc, new BlockPos(3, BASE + 3, 9));
                step = 4;
            }
            if (step == 4 && mc.screen instanceof FurnaceScreen) {
                step = 5;
                wait = clock - 64;
            }
            if (step == 5 && clock - wait > 68) {
                capture(mc, "furnace");
                record("opened working furnace on upper deck");
                Files.writeString(Path.of("actions-complete"), "true");
            }
        }
    }
}
