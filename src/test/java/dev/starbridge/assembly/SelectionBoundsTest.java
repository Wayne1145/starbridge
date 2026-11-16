package dev.starbridge.assembly;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class SelectionBoundsTest {
    @Test
    void inclusiveVolumeAcceptsLargeThinShipsButRejectsHugeRegions() {
        assertEquals(633_600, SelectionBounds.between(0, 0, 0, 299, 32, 63).volume());
        assertThrows(IllegalArgumentException.class, () -> SelectionBounds.between(0, 0, 0, 300, 1, 1));
        assertThrows(IllegalArgumentException.class, () -> SelectionBounds.between(0, 0, 0, 299, 299, 299));
    }

    @Test
    void extremeCoordinatesCannotOverflowAroundSafetyLimits() {
        assertThrows(IllegalArgumentException.class,
                () -> SelectionBounds.between(Integer.MIN_VALUE, 0, 0, Integer.MAX_VALUE, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> new SelectionBounds(5, 0, 0, 4, 0, 0));
    }

    @Test
    void reversedCornersEnumerateEveryCellExactlyOnceIncludingEdges() {
        SelectionBounds bounds = SelectionBounds.between(4, 5, 6, -3, -1, -2);
        Set<String> cells = new HashSet<>();
        for (int index = 0; index < bounds.volume(); index++) {
            int x = bounds.x(index), y = bounds.y(index), z = bounds.z(index);
            assertTrue(bounds.contains(x, y, z));
            assertTrue(cells.add(x + ":" + y + ":" + z));
        }
        assertEquals(bounds.volume(), cells.size());
        assertTrue(cells.contains("-3:-1:-2"));
        assertTrue(cells.contains("4:5:6"));
        assertFalse(bounds.contains(5, 5, 6));
        assertThrows(IndexOutOfBoundsException.class, () -> bounds.x(-1));
        assertThrows(IndexOutOfBoundsException.class, () -> bounds.x(bounds.volume()));
    }
}
