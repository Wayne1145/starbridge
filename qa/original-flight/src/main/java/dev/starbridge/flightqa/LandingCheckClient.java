package dev.starbridge.flightqa;

import dev.starbridge.orbit.OrbitWire;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;

import java.nio.file.*;
import java.util.Map;

/**
 * All control actions use rendered widgets; the packet is read to assert which surveyed terrain
 * they display.
 */
final class LandingCheckClient {
    private static int step, visibleStep = -1, visibleSince;

    private static boolean shown() {
        if (visibleStep != step) { visibleStep = step; visibleSince = FlightClient.clock; }
        return FlightClient.clock - visibleSince >= 10;
    }

    static boolean beforeApproach(Minecraft mc, Screen screen) throws Exception {
        if (!LandingCheck.ENABLED || step == 9) return false;
        var field = screen.getClass().getDeclaredField("landingSite");
        field.setAccessible(true);
        var site = (OrbitWire.Site) field.get(screen);
        var button = FlightClient.find(screen, "orbit.starbridge.touchdown");
        switch (step) {
            case 0 -> {
                point(screen, 480, 480);
                step++;
            }
            case 1 -> {
                if (site != null
                        && site.clearance() == OrbitWire.Clearance.BORDER
                        && button != null
                        && !button.active) {
                    if (!shown()) return true;
                    FlightClient.snap(mc, "border-before-conversion");
                    MissionSignals.write(
                            Path.of("rejected-site.json"),
                            Map.of(
                                    "clearance",
                                    site.clearance().name(),
                                    "disabled",
                                    true,
                                    "markX",
                                    site.markX(),
                                    "markZ",
                                    site.markZ()));
                    step++;
                }
            }
            case 2 -> {
                if (Files.exists(Path.of("correct-site.json"))) {
                    point(screen, 0, 0);
                    step++;
                }
            }
            case 3 -> {
                if (site != null
                        && site.clearance() == OrbitWire.Clearance.CLEAR
                        && button != null
                        && button.active) {
                    FlightClient.snap(mc, "clear-before-obstruction");
                    MissionSignals.write(
                            Path.of("add-obstruction.json"),
                            Map.of(
                                    "x",
                                    site.centerX(),
                                    "z",
                                    site.centerZ(),
                                    "floor",
                                    site.floor()));
                    step++;
                }
            }
            case 4 -> {
                if (Files.exists(Path.of("obstruction-added.json"))
                        && FlightClient.click(screen, "orbit.starbridge.touchdown")) step++;
            }
            case 5 -> {
                if (site != null
                        && site.clearance() == OrbitWire.Clearance.OCCUPIED
                        && button != null
                        && !button.active) {
                    if (!shown()) return true;
                    FlightClient.snap(mc, "occupied-terrain-and-footprint");
                    MissionSignals.write(
                            Path.of("occupied-site.json"),
                            Map.of(
                                    "clearance",
                                    site.clearance().name(),
                                    "recheckedAfterClick",
                                    true,
                                    "disabled",
                                    true,
                                    "floor",
                                    site.floor(),
                                    "markX",
                                    site.markX(),
                                    "markZ",
                                    site.markZ()));
                    if (FlightClient.click(screen, "orbit.starbridge.go_around")) step++;
                }
            }
            case 6 -> {
                if (Files.exists(Path.of("climb-witness.json"))
                        && FlightClient.click(screen, "orbit.starbridge.cruise")) step++;
            }
            case 7 -> {
                if (FlightClient.click(screen, "orbit.starbridge.landing")) {
                    point(screen, 64, -64);
                    step++;
                }
            }
            case 8 -> {
                if (site != null
                        && site.clearance() == OrbitWire.Clearance.CLEAR
                        && button != null
                        && button.active
                        && site.x() != 0
                        && site.z() != 0) {
                    int painted = 0;
                    for (byte value : site.cells()) if (value != 0) painted++;
                    if (painted != OrbitWire.Site.GRID * OrbitWire.Site.GRID
                            || site.width() != 5
                            || site.depth() != 6)
                        throw new IllegalStateException(
                                "Incorrect live terrain raster or hull bounds");
                    FlightClient.snap(mc, "corrected-landing-terrain");
                    MissionSignals.write(
                            Path.of("safe-site.json"),
                            Map.of(
                                    "x",
                                    site.x(),
                                    "z",
                                    site.z(),
                                    "floor",
                                    site.floor(),
                                    "relief",
                                    site.relief(),
                                    "width",
                                    site.width(),
                                    "height",
                                    site.height(),
                                    "depth",
                                    site.depth(),
                                    "paintedCells",
                                    painted));
                    step++;
                    return false;
                }
            }
            default -> throw new IllegalStateException("Landing fixture stage");
        }
        return true;
    }

    private static void point(Screen screen, int x, int z) {
        int size = Math.max(52, Math.min(180, Math.min(screen.width / 2 - 28, screen.height - 93)));
        int left = Math.max(12, (screen.width / 2 - size) / 2),
                top = (screen.height - 35 - size) / 2;
        double a = left + (x + 512) * (size - 1) / 1024., b = top + (z + 512) * (size - 1) / 1024.;
        screen.mouseClicked(a, b, 0);
        screen.mouseReleased(a, b, 0);
    }
}
