package dev.starbridge.client.shipyard;

import org.junit.jupiter.api.Test;

import static dev.starbridge.client.shipyard.LaunchPadPlan.*;
import static org.junit.jupiter.api.Assertions.*;

class LaunchPadPickingTest {
    @Test void topViewSeparatesAdjacentCellsAndRejectsMarginsAtEveryScale() {
        for (int size = 1; size <= 16; size++) {
            assertSame(TOWER, pick(false, size, size - 0.01, 2.5 * size));
            assertSame(PAD, pick(false, size, size, 2.5 * size));
            assertSame(ASSEMBLER, pick(false, size, 2.5 * size, 0.5 * size));
            assertNull(pick(false, size, 4.5 * size, 0));
            assertNull(pick(false, size, -0.01, 2.5 * size));
            assertNull(pick(false, size, 2.5 * size, -0.01));
            assertNull(pick(false, size, WIDTH * size, size));
            assertNull(pick(false, size, size, WIDTH * size));
        }
    }

    @Test void elevationShowsSixTowerLevelsAndTheAssemblerAboveThePad() {
        int size = 12;
        for (int level = 0; level < HEIGHT; level++) {
            assertSame(TOWER, pick(true, size, 6, (level + 0.5) * size));
        }
        assertSame(ASSEMBLER, pick(true, size, 30, 54));
        assertSame(PAD, pick(true, size, 30, 66));
        assertNull(pick(true, size, 30, 42));
        assertNull(pick(true, size, 6, HEIGHT * size));
    }
}
