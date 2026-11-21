package dev.starbridge.qa.integrated;

import com.mojang.blaze3d.platform.GlUtil;

import net.minecraft.client.Minecraft;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.io.*;
import java.nio.file.*;
import java.util.*;

/**
 * Frame-end to frame-end on the render thread. A stall is attributed to the frame on either side of
 * it.
 */
@Mod.EventBusSubscriber(modid = "starbridgeintegratedqa", value = Dist.CLIENT)
public final class Frames {
    static BufferedWriter out;
    static long previous, seq;
    static String lastPhase, lastScreen, lastWorld;
    static boolean finished;

    @SubscribeEvent
    public static void frame(TickEvent.RenderTickEvent e) {
        if (!IntegratedFlight.ENABLED
                || e.phase != TickEvent.Phase.END
                || IntegratedFlight.origin == 0
                || finished) return;
        try {
            FlightProfile.begin(Thread.currentThread());
            var mc = Minecraft.getInstance();
            long now = System.nanoTime();
            var phase = IntegratedFlight.preparing ? "restore" : Pilot.phase;
            String screen = mc.screen == null ? "world" : mc.screen.getClass().getSimpleName();
            String world =
                    mc.level == null ? "unloaded" : mc.level.dimension().location().toString();
            if (out == null) {
                out = Files.newBufferedWriter(Path.of("frames.csv"));
                out.write(
                        "seq,startNs,endNs,startPhase,endPhase,startScreen,endScreen,startWorld,endWorld\n");
                IntegratedFlight.write(
                        "graphics.json",
                        Map.of(
                                "renderer",
                                GlUtil.getRenderer(),
                                "vendor",
                                GlUtil.getVendor(),
                                "OpenGL",
                                GlUtil.getOpenGLVersion(),
                                "width",
                                mc.getWindow().getWidth(),
                                "height",
                                mc.getWindow().getHeight(),
                                "cap",
                                mc.options.framerateLimit().get(),
                                "vsync",
                                mc.options.enableVsync().get(),
                                "renderDistance",
                                mc.options.renderDistance().get(),
                                "simulationDistance",
                                mc.options.simulationDistance().get(),
                                "thread",
                                Thread.currentThread().getName()));
            }
            if (previous > 0) {
                out.write(
                        (++seq)
                                + ","
                                + previous
                                + ","
                                + now
                                + ","
                                + lastPhase
                                + ","
                                + phase
                                + ","
                                + lastScreen
                                + ","
                                + screen
                                + ","
                                + lastWorld
                                + ","
                                + world
                                + "\n");
                if (seq % 120 == 0) out.flush();
            }
            previous = now;
            lastPhase = phase;
            lastScreen = screen;
            lastWorld = world;
        } catch (Exception ex) {
            IntegratedFlight.fail(ex);
        }
    }

    static void finish() throws Exception {
        finished = true;
        FlightProfile.finish();
        if (out != null) {
            out.flush();
            out.close();
        }
    }
}
