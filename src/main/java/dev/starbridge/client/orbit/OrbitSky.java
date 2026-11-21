package dev.starbridge.client.orbit;

import dev.starbridge.flight.Vec3d;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.util.Mth;

import java.util.Random;

/**
 * The visual sky is projected from server-space positions; steering rotates the star field and
 * thrust creates parallax.
 */
final class OrbitSky {
    private static final Vec3d[] STARS = new Vec3d[1050];
    private static final Vec3d[] DUST = new Vec3d[180];

    static {
        var random = new Random(42653);
        for (int i = 0; i < STARS.length; i++) {
            double y = random.nextDouble() * 2 - 1,
                    a = random.nextDouble() * Math.PI * 2,
                    r = Math.sqrt(1 - y * y);
            STARS[i] = new Vec3d(Math.cos(a) * r, y, Math.sin(a) * r);
        }
        for (int i = 0; i < DUST.length; i++)
            DUST[i] =
                    new Vec3d(
                            random.nextDouble() * 800,
                            random.nextDouble() * 800,
                            random.nextDouble() * 800);
    }

    private OrbitSky() {}

    static void draw(
            GuiGraphics g,
            FlightCamera camera,
            Vec3d pos,
            int width,
            int top,
            int bottom,
            int cx,
            int cy,
            double focal) {
        g.fillGradient(0, top, width, bottom, 0xff040811, 0xff102335);
        for (int i = 0; i < STARS.length; i++) {
            var aim = camera.transform(STARS[i]);
            if (aim.depth() < .13) continue;
            int x = aim.x(cx, focal), y = aim.y(cy, focal);
            if (x < 0 || x >= width || y < top || y >= bottom) continue;
            int size = i % 43 == 0 ? 2 : 1;
            g.fill(
                    x,
                    y,
                    x + size,
                    y + size,
                    switch (i % 5) {
                        case 0 -> 0xffcad3cb;
                        case 1 -> 0xff839db4;
                        default -> 0xff53627b;
                    });
        }
        for (var dust : DUST) {
            Vec3d local =
                    new Vec3d(
                            wrap(dust.x() - pos.x()),
                            wrap(dust.y() - pos.y()),
                            wrap(dust.z() - pos.z()));
            var aim = camera.transform(local);
            if (aim.depth() < 9) continue;
            int x = aim.x(cx, focal), y = aim.y(cy, focal);
            if (x < 0 || x >= width || y < top || y >= bottom) continue;
            g.fill(x, y, x + 1, y + 1, aim.depth() < 120 ? 0xff94b7be : 0xff4a5969);
        }
    }

    private static double wrap(double n) {
        return n - Math.floor((n + 400) / 800) * 800;
    }

    static void body(GuiGraphics g, int cx, int cy, int radius, String target) {
        int hue =
                switch (target) {
                    case "home" -> 0x78b6d0;
                    case "moon" -> 0xcec8b8;
                    case "mars" -> 0xd98162;
                    case "belt" -> 0xa49f8a;
                    default -> (target.hashCode() & 0x3f3f3f) | 0x909090;
                };
        for (int dy = -radius; dy <= radius; dy++) {
            double ny = dy / (double) radius;
            int half = (int) Math.round(radius * Math.sqrt(Math.max(0, 1 - ny * ny)));
            int split = (int) (half * (.38 - .24 * ny));
            float band = (float) (.94 + .06 * Math.sin(ny * 14));
            g.fill(cx - half, cy + dy, cx + half + 1, cy + dy + 1, color(hue, .30f));
            g.fill(cx - half, cy + dy, cx + split + 1, cy + dy + 1, color(hue, .70f * band));
            g.fill(
                    cx - half + half / 12,
                    cy + dy,
                    cx + split - half / 4 + 1,
                    cy + dy + 1,
                    color(hue, band));
        }
    }

    private static int color(int base, float light) {
        return 0xff000000
                | (int) ((base >> 16 & 255) * light) << 16
                | (int) ((base >> 8 & 255) * light) << 8
                | (int) ((base & 255) * light);
    }

    static void marker(GuiGraphics g, int x, int y, int r, int color) {
        int edge = Mth.clamp(r / 2, 2, 5);
        for (int dir : new int[] {-1, 1}) {
            int left = x - r, right = x + r, at = y + dir * r;
            g.hLine(left, left + edge, at, color);
            g.hLine(right - edge, right, at, color);
            g.vLine(left, Math.min(at, at - dir * edge), Math.max(at, at - dir * edge), color);
            g.vLine(right, Math.min(at, at - dir * edge), Math.max(at, at - dir * edge), color);
        }
    }
}
