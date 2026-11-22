package dev.starbridge.qa.moonwalk;

import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.contents.TranslatableContents;

import java.lang.reflect.Method;
import java.util.List;

/**
 * Read only heights and relief that the open field chart presents. All selections use widget
 * coordinates.
 */
final class FieldChart {
    record Cell(int column, int row, int approximateX, int approximateZ, int depth) {}

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
        drop.setAccessible(true);
        steep.setAccessible(true);
        double highest = Double.NEGATIVE_INFINITY;
        Cell best = null;
        for (int dx = -13; dx <= 13; dx++)
            for (int dz = -13; dz <= 13; dz++) {
                int col = cx + dx, row = cz + dz, depth = (int) drop.invoke(terrain, col, row);
                if (depth < 8) continue;
                int x = col * stride + stride / 2, z = row * stride + stride / 2;
                if (visited.stream().anyMatch(p -> Math.hypot(p.getX() - x, p.getZ() - z) < 14))
                    continue;
                double distance = Math.hypot(feet.getX() - x, feet.getZ() - z);
                double value =
                        depth
                                + ((boolean) steep.invoke(terrain, col, row) ? 12 : 0)
                                - distance * .045;
                if (value > highest) {
                    highest = value;
                    best = new Cell(dx + 16, dz + 16, x, z, depth);
                }
            }
        return best;
    }

    static void open(Screen sc, Cell cell) throws Exception {
        int x = (int) field(sc, "mapX"), y = (int) field(sc, "mapY"), w = (int) field(sc, "cell");
        at(sc, x + (cell.column() + .5) * w, y + (cell.row() + .5) * w);
    }
}
