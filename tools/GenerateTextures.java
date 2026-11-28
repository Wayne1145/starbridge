import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Random;

/**
 * Generates Starbridge's original 16×16 item and block textures. No third-party art is used.
 * Run from the project root with JDK 17+:  java tools/GenerateTextures.java
 */
public final class GenerateTextures {
    private static final Path ROOT = Path.of("src/main/resources/assets/starbridge/textures");
    private static final int CLEAR = 0;

    public static void main(String[] args) throws IOException {
        item("starlit_crystal", crystal());
        item("star_fuel_cell", fuelCell());
        item("star_chart", chart());
        item("hull_marker", marker());
        item("capacitor_module", module(0xFF4FD8E8, Icon.BARS));
        item("thruster_module", module(0xFFFF9A3C, Icon.CHEVRON));
        item("efficiency_module", module(0xFFB48CFF, Icon.RING));
        item("oxygen_mask", mask());
        item("oxygen_canister", canister());
        item("survey_core", surveyCore());
        block("starlit_ore", ore());
        System.out.println("Textures written under " + ROOT.toAbsolutePath());
    }

    // ------------------------------------------------------------ sprites

    private static int[][] crystal() {
        int[][] c = canvas();
        // Three facetted shards: tall centre, two short sides.
        shard(c, 8, 1, 13, 3, 0xFF8CF6FF, 0xFF3AB7D6, 0xFF2A6FA8);
        shard(c, 4, 6, 14, 2, 0xFFD9C4FF, 0xFF9B7BEA, 0xFF5B47A8);
        shard(c, 12, 7, 14, 2, 0xFFD9C4FF, 0xFF9B7BEA, 0xFF5B47A8);
        px(c, 7, 3, 0xFFFFFFFF); px(c, 7, 4, 0xFFE8FDFF); px(c, 3, 8, 0xFFFFFFFF);
        return outline(c, 0xFF14213A);
    }

    private static void shard(int[][] c, int cx, int top, int bottom, int halfWidth, int light, int mid, int dark) {
        for (int y = top; y <= bottom; y++) {
            double t = (double) (y - top) / Math.max(1, bottom - top);
            int half = (int) Math.round(Math.min(t * 2.2, 1.0) * halfWidth);
            for (int x = cx - half; x <= cx + half; x++) {
                int color = x < cx ? light : x == cx ? mid : dark;
                if (y > bottom - 2) color = dark;
                px(c, x, y, color);
            }
        }
    }

    private static int[][] fuelCell() {
        int[][] c = canvas();
        rect(c, 5, 3, 10, 14, 0xFF3B4652);
        rect(c, 6, 1, 9, 2, 0xFF8A97A3);
        rect(c, 7, 0, 8, 0, 0xFFC7D2DA);
        rect(c, 6, 5, 9, 12, 0xFF2A1B4F);
        for (int y = 5; y <= 12; y++) for (int x = 6; x <= 9; x++) {
            double glow = 1 - Math.abs(y - 8.5) / 4.5;
            px(c, x, y, mix(0xFF4B2A99, 0xFFD6B8FF, glow * (x == 7 || x == 8 ? 1 : 0.6)));
        }
        rect(c, 5, 13, 10, 13, 0xFF4FD8E8);
        col(c, 5, 3, 14, 0xFF55636F);
        return outline(c, 0xFF10151C);
    }

    private static int[][] chart() {
        int[][] c = canvas();
        rect(c, 2, 2, 13, 13, 0xFF1B2850);
        rect(c, 1, 2, 1, 13, 0xFFC9B38A);
        rect(c, 14, 2, 14, 13, 0xFFC9B38A);
        Random random = new Random(7);
        for (int i = 0; i < 9; i++) px(c, 3 + random.nextInt(10), 3 + random.nextInt(10), 0xFF7F8FC0);
        // Dotted route from home (green) to the moon (pale disc).
        int[][] route = {{4, 11}, {5, 10}, {6, 9}, {7, 9}, {8, 8}, {9, 7}, {10, 6}};
        for (int i = 0; i < route.length; i += 1) if (i % 2 == 0) px(c, route[i][0], route[i][1], 0xFFFFD27A);
        rect(c, 3, 11, 4, 12, 0xFF5FD38A);
        disc(c, 11, 4, 1.8, 0xFFE6E6EE);
        px(c, 12, 4, 0xFFB7B7C6);
        return outline(c, 0xFF2B2117);
    }

    private static int[][] marker() {
        int[][] c = canvas();
        for (int i = 0; i < 11; i++) {
            int x = 3 + i, y = 13 - i;
            px(c, x, y, i % 3 == 0 ? 0xFF7A5B2E : 0xFFD9A94E);
            px(c, x, y - 1, 0xFFF0CF85);
        }
        rect(c, 12, 1, 14, 3, 0xFF4FD8E8);
        px(c, 13, 2, 0xFFE8FDFF);
        rect(c, 1, 13, 3, 14, 0xFF5B6773);
        return outline(c, 0xFF1A1410);
    }

    private enum Icon { BARS, CHEVRON, RING }

    private static int[][] module(int accent, Icon icon) {
        int[][] c = canvas();
        rect(c, 2, 2, 13, 13, 0xFF26313B);
        rect(c, 3, 3, 12, 12, 0xFF33414D);
        for (int i = 4; i <= 11; i += 2) {
            px(c, i, 1, 0xFFD9B45A); px(c, i, 14, 0xFFD9B45A);
            px(c, 1, i, 0xFFD9B45A); px(c, 14, i, 0xFFD9B45A);
        }
        int dark = mix(accent, 0xFF000000, 0.45);
        switch (icon) {
            case BARS -> {
                for (int bar = 0; bar < 3; bar++) {
                    int x = 5 + bar * 2, top = 9 - bar * 2;
                    rect(c, x, top, x, 10, accent);
                    px(c, x, top, 0xFFFFFFFF);
                }
                rect(c, 4, 11, 11, 11, dark);
            }
            case CHEVRON -> {
                // A rising exhaust flame: orange shell, yellow core, white tip at the nozzle.
                for (int y = 4; y <= 11; y++) {
                    double half = y < 8 ? (y - 4) * 0.75 : 3 - (y - 8) * 0.6;
                    for (int x = 4; x <= 11; x++) {
                        double dx = Math.abs(x - 7.5);
                        if (dx <= half + 0.5) px(c, x, y, dx <= half - 1 ? 0xFFFFE07A : accent);
                    }
                }
                rect(c, 6, 11, 9, 11, dark);
                px(c, 7, 9, 0xFFFFFFFF); px(c, 8, 9, 0xFFFFFFFF);
            }
            case RING -> {
                for (int y = 3; y <= 12; y++) for (int x = 3; x <= 12; x++) {
                    double d = Math.hypot(x - 7.5, y - 7.5);
                    if (d > 2.6 && d < 4.2) px(c, x, y, y < 7 ? accent : dark);
                }
                rect(c, 7, 7, 8, 8, 0xFFFFFFFF);
            }
        }
        return outline(c, 0xFF0E1318);
    }

    private static int[][] mask() {
        int[][] c = canvas();
        for (int y = 3; y <= 13; y++) for (int x = 2; x <= 13; x++) {
            double d = Math.hypot((x - 7.5) / 6.0, (y - 8.5) / 5.6);
            if (d <= 1) px(c, x, y, mix(0xFFF2F5F7, 0xFFA9B4BD, Math.max(0, (y - 4) / 10.0)));
        }
        rect(c, 4, 7, 11, 10, 0xFF2D8FB0);
        rect(c, 5, 7, 7, 8, 0xFF8EE7F5);
        rect(c, 3, 11, 4, 12, 0xFF6E7A84);
        rect(c, 11, 11, 12, 12, 0xFF6E7A84);
        return outline(c, 0xFF1A2229);
    }

    private static int[][] canister() {
        int[][] c = canvas();
        rect(c, 5, 4, 10, 14, 0xFFE4EAEE);
        col(c, 9, 4, 14, 0xFFB5C0C8);
        col(c, 10, 4, 14, 0xFF96A3AD);
        col(c, 5, 4, 14, 0xFFF8FBFC);
        rect(c, 5, 8, 10, 10, 0xFF3C8FD6);
        rect(c, 6, 8, 6, 10, 0xFF7FC2F4);
        rect(c, 6, 2, 9, 3, 0xFF7B8790);
        rect(c, 7, 0, 8, 1, 0xFFD54B4B);
        return outline(c, 0xFF1C252C);
    }

    private static int[][] surveyCore() {
        int[][] c = canvas();
        for (int y = 0; y < 16; y++) for (int x = 0; x < 16; x++) {
            double d = Math.hypot(x - 7.5, y - 7.5);
            if (d <= 4.2) px(c, x, y, mix(0xFFE8FDFF, 0xFF2E9FC2, d / 4.2));
            double ring = Math.abs(Math.hypot((x - 7.5) / 1.0, (y - 7.5) / 0.45) - 6.3);
            if (ring < 0.7 && (d > 4.2 || y > 8)) px(c, x, y, y > 8 ? 0xFFE7B24A : 0xFF9C7322);
        }
        px(c, 6, 5, 0xFFFFFFFF);
        return outline(c, 0xFF101A24);
    }

    private static int[][] ore() {
        int[][] c = new int[16][16];
        Random random = new Random(1145);
        for (int y = 0; y < 16; y++) for (int x = 0; x < 16; x++) {
            int shade = random.nextInt(14) - 7;
            int base = ((x / 4 + y / 3) % 3 == 0) ? 0xFF6E6F68 : 0xFF66675F;
            c[y][x] = shift(base, shade);
        }
        int[][] veins = {{3, 3}, {11, 4}, {6, 9}, {12, 11}, {2, 12}};
        for (int[] v : veins) {
            int x = v[0], y = v[1];
            px(c, x, y, 0xFF8CF6FF); px(c, x + 1, y, 0xFF3AB7D6);
            px(c, x, y + 1, 0xFF9B7BEA); px(c, x + 1, y + 1, 0xFF5B47A8);
            px(c, x, y - 1, 0xFFE8FDFF);
        }
        return c;
    }

    // ------------------------------------------------------------ helpers

    private static int[][] canvas() { return new int[16][16]; }

    private static void px(int[][] c, int x, int y, int argb) { if (x >= 0 && y >= 0 && x < 16 && y < 16) c[y][x] = argb; }

    private static void rect(int[][] c, int x0, int y0, int x1, int y1, int argb) {
        for (int y = y0; y <= y1; y++) for (int x = x0; x <= x1; x++) px(c, x, y, argb);
    }

    private static void col(int[][] c, int x, int y0, int y1, int argb) { rect(c, x, y0, x, y1, argb); }

    private static void disc(int[][] c, double cx, double cy, double r, int argb) {
        for (int y = 0; y < 16; y++) for (int x = 0; x < 16; x++) if (Math.hypot(x - cx, y - cy) <= r) px(c, x, y, argb);
    }

    /** Adds a one-pixel dark outline around every opaque region. */
    private static int[][] outline(int[][] c, int argb) {
        int[][] out = new int[16][16];
        for (int y = 0; y < 16; y++) out[y] = c[y].clone();
        for (int y = 0; y < 16; y++) for (int x = 0; x < 16; x++) {
            if (c[y][x] != CLEAR) continue;
            boolean edge = false;
            for (int[] d : new int[][]{{1, 0}, {-1, 0}, {0, 1}, {0, -1}}) {
                int nx = x + d[0], ny = y + d[1];
                if (nx >= 0 && ny >= 0 && nx < 16 && ny < 16 && c[ny][nx] != CLEAR) edge = true;
            }
            if (edge) out[y][x] = argb;
        }
        return out;
    }

    private static int mix(int a, int b, double t) {
        t = Math.max(0, Math.min(1, t));
        int ar = (a >> 16) & 255, ag = (a >> 8) & 255, ab = a & 255;
        int br = (b >> 16) & 255, bg = (b >> 8) & 255, bb = b & 255;
        return 0xFF000000 | ((int) (ar + (br - ar) * t) << 16) | ((int) (ag + (bg - ag) * t) << 8) | (int) (ab + (bb - ab) * t);
    }

    private static int shift(int argb, int amount) {
        int r = clamp(((argb >> 16) & 255) + amount), g = clamp(((argb >> 8) & 255) + amount), b = clamp((argb & 255) + amount);
        return 0xFF000000 | (r << 16) | (g << 8) | b;
    }

    private static int clamp(int value) { return Math.max(0, Math.min(255, value)); }

    private static void item(String name, int[][] pixels) throws IOException { write(ROOT.resolve("item/" + name + ".png"), pixels); }

    private static void block(String name, int[][] pixels) throws IOException { write(ROOT.resolve("block/" + name + ".png"), pixels); }

    private static void write(Path path, int[][] pixels) throws IOException {
        Files.createDirectories(path.getParent());
        BufferedImage image = new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < 16; y++) for (int x = 0; x < 16; x++) image.setRGB(x, y, pixels[y][x]);
        ImageIO.write(image, "png", path.toFile());
    }
}
