package dev.starbridge.benchmark;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.ArrayDeque;
import java.util.BitSet;

import static org.junit.jupiter.api.Assertions.*;

final class ShipBlueprintTest {
    @ParameterizedTest
    @CsvSource({"100,0", "100,200", "200,0", "200,200", "300,0", "300,200"})
    void fixturesHaveExactlyOneCoreAndTheRequestedSupportedMachines(int length, int machines) {
        ShipBlueprint plan = ShipBlueprint.ship(length, machines);
        int foundCore = 0, foundMachines = 0;
        for (int i = 0; i < plan.volume(); i++) {
            ShipBlueprint.Cell cell = plan.cell(i);
            if (cell == ShipBlueprint.Cell.CORE) foundCore++;
            if (cell == ShipBlueprint.Cell.CHEST || cell == ShipBlueprint.Cell.FURNACE) {
                foundMachines++;
                assertTrue(plan.y(i) > 0);
                ShipBlueprint.Cell floor = plan.cell(i - plan.length);
                assertTrue(floor == ShipBlueprint.Cell.DECK || floor == ShipBlueprint.Cell.HULL || floor == ShipBlueprint.Cell.LIGHT,
                        "A machine must stand on a structural floor");
                assertNull(plan.cell(i + plan.length), "Machine access above must stay clear");
            }
        }
        assertEquals(1, foundCore);
        assertEquals(machines, foundMachines);
        assertEquals(ShipBlueprint.Cell.CORE, plan.cell(plan.coreIndex));
        int arrival = plan.coreIndex - 2;
        assertNull(plan.cell(arrival), "Bridge arrival feet must be unobstructed");
        assertNull(plan.cell(arrival + plan.length), "Bridge arrival head must be unobstructed");
        assertNotNull(plan.cell(arrival - plan.length), "Bridge arrival must have a floor");
    }

    @ParameterizedTest
    @CsvSource({"100", "200", "300"})
    void everyPlacedBlockIsFaceConnectedToTheShipCore(int length) {
        ShipBlueprint plan = ShipBlueprint.ship(length, 200);
        BitSet remaining = new BitSet(plan.volume());
        for (int i = 0; i < plan.volume(); i++) if (plan.cell(i) != null) remaining.set(i);
        assertTrue(remaining.cardinality() < plan.volume() / 2, "Fixture must remain hollow, not a filled cuboid");
        ArrayDeque<Integer> queue = new ArrayDeque<>();
        queue.add(plan.coreIndex);
        remaining.clear(plan.coreIndex);
        while (!queue.isEmpty()) {
            int i = queue.removeFirst();
            int x = plan.x(i), y = plan.y(i), z = plan.z(i);
            if (x > 0) visit(i - 1, remaining, queue);
            if (x + 1 < plan.length) visit(i + 1, remaining, queue);
            if (y > 0) visit(i - plan.length, remaining, queue);
            if (y + 1 < plan.height) visit(i + plan.length, remaining, queue);
            if (z > 0) visit(i - plan.length * plan.height, remaining, queue);
            if (z + 1 < plan.width) visit(i + plan.length * plan.height, remaining, queue);
        }
        assertEquals(0, remaining.cardinality(), "Assembly must not leave disconnected fixture pieces behind");
    }

    private static void visit(int index, BitSet remaining, ArrayDeque<Integer> queue) {
        if (!remaining.get(index)) return;
        remaining.clear(index);
        queue.addLast(index);
    }

    @Test
    void rejectsUnsupportedSizesAndUnboundedMachineLoads() {
        assertThrows(IllegalArgumentException.class, () -> ShipBlueprint.ship(101, 0));
        assertThrows(IllegalArgumentException.class, () -> ShipBlueprint.ship(300, -1));
        assertThrows(IllegalArgumentException.class, () -> ShipBlueprint.ship(300, 201));
    }
}
