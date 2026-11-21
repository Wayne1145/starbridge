package dev.starbridge.flightqa;

import dev.starbridge.orbit.OrbitWire;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;

import java.nio.file.*;
import java.util.Map;

final class LandingCheckClient {
    private static int step;

    static boolean beforeApproach(Minecraft mc, Screen screen) throws Exception {
        if (!LandingCheck.ENABLED || step == 3) return false;
        var surface = FlightClient.find(screen, "orbit.starbridge.touchdown");
        if (step == 0) {
            point(screen, 480, 480);
            step = 1;
            FlightClient.log("Selected (480,480) beyond the test Moon world border");
            return true;
        }
        if (step == 1) {
            if (surface != null && surface.active) {
                FlightClient.snap(mc, "outside-border-before-descent");
                FlightClient.click(screen, "orbit.starbridge.touchdown");
                step = 2;
            }
            return true;
        }
        var field = screen.getClass().getDeclaredField("frame");
        field.setAccessible(true);
        var frame = (OrbitWire.Frame) field.get(screen);
        if (frame.notice().equals("Choose a landing footprint inside the world border")
                && !Files.isRegularFile(Path.of("rejected-site.json"))) {
            FlightClient.snap(mc, "outside-border-server-rejection");
            FlightClient.log("Visible landing instrument notice: " + frame.notice());
            MissionSignals.write(
                    Path.of("rejected-site.json"),
                    Map.of(
                            "notice",
                            frame.notice(),
                            "buttonEnabledBeforeRequest",
                            true,
                            "screen",
                            screen.getClass().getName(),
                            "fuel",
                            frame.fuel()));
        }
        if (Files.isRegularFile(Path.of("correct-site.json"))) {
            point(screen, 0, 0);
            step = 3;
            FlightClient.log("Selected (0,0) on the same landing screen after denial");
        }
        return true;
    }

    private static void point(Screen screen, int x, int z) {
        int w = screen.width,
                h = screen.height,
                size = Math.max(52, Math.min(180, Math.min(w / 2 - 28, h - 93)));
        int left = Math.max(12, (w / 2 - size) / 2), top = (h - 35 - size) / 2;
        double a = left + (x + 512) * (size - 1) / 1024., b = top + (z + 512) * (size - 1) / 1024.;
        screen.mouseClicked(a, b, 0);
        screen.mouseReleased(a, b, 0);
    }
}
