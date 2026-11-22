package dev.starbridge.client.shipyard;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;

final class FabricationStockTest {
    @Test void reservesAnExactMaterialForTheSlotWithNoSubstitute() {
        assertArrayEquals(new int[] {1, 0}, FabricationStock.match(
                new boolean[][] {{true, true}, {true, false}}, new int[] {1, 1}));
    }

    @Test void aStackOfTwoCoversOnlyTwoOfThreeRepeatedInputs() {
        assertArrayEquals(new int[] {0, 0, -1}, FabricationStock.match(
                new boolean[][] {{true}, {true}, {true}}, new int[] {2}));
    }
}
