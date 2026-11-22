package dev.starbridge.qa.moonwalk;

import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.contents.TranslatableContents;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Read only heights and relief that the open field chart presents. All selections use widget
 * coordinates.
 */
final class FieldChart {
    record Cell(int column, int row, int approximateX, int approximateZ, int depth, boolean surfaceBrick) {}

    static Object field(Object target, String name) throws Exception {
        var member = target.getClass().getDeclaredField(name);
        member.setAccessible(true);
        return member.get(target);
    }

    static Button button(Screen sc, String key) {
        return sc.children().stream()
                .filter(Button.class::isInstance)
                .map(Button.class::cast)
                .filter(
                        b ->
                                b.visible
                                        && b.getMessage().getContents()
                                                instanceof TranslatableContents t
                                        && key.equals(t.getKey()))
                .findFirst()
                .orElse(null);
    }

    static boolean click(Screen sc, String key) {
        var b = button(sc, key);
        if (b == null || !b.active) return false;
        at(sc, b.getX() + b.getWidth() / 2d, b.getY() + b.getHeight() / 2d);
        return true;
    }

    static void at(Screen sc, double x, double y) {
        FieldStart.require(sc.mouseClicked(x, y, 0), "Widget accepted input");
        sc.mouseReleased(x, y, 0);
    }

    static Cell best(Screen sc, BlockPos feet, List<BlockPos> visited) throws Exception {
        int stride = (int) field(sc, "stride"),
                cx = (int) field(sc, "centerX"),
                cz = (int) field(sc, "centerZ");
        var terrain = field(sc, "terrain");
        Method drop = terrain.getClass().getDeclaredMethod("rimDrop", int.class, int.class);
        Method steep = terrain.getClass().getDeclaredMethod("steep", int.class, int.class);
        Method masonry = terrain.getClass().getDeclaredMethod("exposedMasonry", int.class, int.class);
        masonry.setAccessible(true);
        drop.setAccessible(true);
        steep.setAccessible(true);
        double highest = Double.NEGATIVE_INFINITY;
        Cell best = null;
        for (int dx = -13; dx <= 13; dx++)
            for (int dz = -13; dz <= 13; dz++) {
                int col = cx + dx, row = cz + dz, depth = (int) drop.invoke(terrain, col, row);
                BlockPos face = (BlockPos) masonry.invoke(terrain, col, row);
                if (face == null && depth < 8) continue;
                int x = col * stride + stride / 2, z = row * stride + stride / 2;
                if (face == null && visited.stream().anyMatch(p -> Math.hypot(p.getX() - x, p.getZ() - z) < 14))
                    continue;
                double distance = Math.hypot(feet.getX() - x, feet.getZ() - z);
                double value =
                        (face == null ? 0 : 1000) + depth
                                + ((boolean) steep.invoke(terrain, col, row) ? 12 : 0)
                                - distance * .045;
                if (value > highest) {
                    highest = value;
                    best = new Cell(dx + 16, dz + 16, x, z, depth, face != null);
                }
            }
        return best;
    }

    /** Accept a map cue only if its violet ink is already visible on the hosted framebuffer. */
    static boolean surfaceFrame(Minecraft mc, Screen sc, Cell cell) throws Exception {
        int cellSize = (int)field(sc,"cell");
        int x = (int)field(sc,"mapX") + cell.column() * cellSize;
        int y = (int)field(sc,"mapY") + cell.row() * cellSize;
        try (var image = Screenshot.takeScreenshot(mc.getMainRenderTarget())) {
            int left = (int)Math.ceil(x * (double)image.getWidth() / sc.width);
            int right = (int)Math.floor((x+cellSize) * (double)image.getWidth() / sc.width);
            int top = (int)Math.ceil(y * (double)image.getHeight() / sc.height);
            int bottom = (int)Math.floor((y+cellSize) * (double)image.getHeight() / sc.height);
            for (int py=top;py<bottom;py++) for (int px=left;px<right;px++) {
                int rgba = image.getPixelRGBA(px,py);
                int rgb = (rgba & 255) << 16 | rgba & 0xff00 | (rgba >>> 16 & 255);
                if (rgb != 0xdc99e4 && rgb != 0x733463) continue;
                Files.createDirectories(Path.of("screenshots"));
                image.writeToFile(Path.of("screenshots/surface-masonry.png"));
                FieldStart.write("field-map-signal.json",Map.of("pixel",List.of(px,py),"rgb",rgb,
                        "chartCell",List.of(cell.column(),cell.row()),"framebuffer",List.of(image.getWidth(), image.getHeight())));
                return true;
            }
        }
        return false;
    }

    static void open(Screen sc, Cell cell) throws Exception {
        int x = (int) field(sc, "mapX"), y = (int) field(sc, "mapY"), w = (int) field(sc, "cell");
        at(sc, x + (cell.column() + .5) * w, y + (cell.row() + .5) * w);
    }
}
