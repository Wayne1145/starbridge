package dev.starbridge.transit;

import dev.starbridge.compat.vs.VsShipBackend.StorageBounds;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class TransitJournalTest {
    @TempDir Path directory;

    @Test void interruptedVoyageRecoversOnlyUndeliveredCrewAndKeepsShipLocked() {
        Path path = directory.resolve("transit.dat");
        TransitJournal journal = TransitJournal.open(path);
        var pilot = new TransitJournal.Rider(UUID.randomUUID(), new Vec3(45.25, 5, -300.5), 108, -21);
        var engineer = new TransitJournal.Rider(UUID.randomUUID(), new Vec3(50, 0.625, -299.5), 28, 3);
        var leg = new TransitJournal.Leg(UUID.randomUUID(), UUID.randomUUID(), 141, new BlockPos(48, 4, -300),
                "minecraft:overworld", "starbridge:survey_moon", new Vec3(2.25, 135, 3.25), "moon", 12000,
                List.of(pilot,engineer), Set.of(), false, new StorageBounds(40,0,-310,58,12,-290), "a".repeat(64));
        journal.prepare(leg);
        journal = TransitJournal.open(path);
        assertTrue(journal.locked(leg.ship()));
        assertThrows(IllegalStateException.class, () -> TransitJournal.open(path).deliver(leg, pilot.player()));
        assertThrows(IllegalStateException.class, () -> TransitJournal.open(path).prepare(leg));
        var paid = journal.commit(leg);
        journal.deliver(paid, pilot.player());
        journal = TransitJournal.open(path);
        var recovered = journal.legs().iterator().next();
        assertEquals(leg.crew(), recovered.crew());
        assertEquals(Set.of(pilot.player()), recovered.done());
        assertEquals(leg.berth(), recovered.berth());
        assertTrue(recovered.committed());
        var complete = journal.deliver(recovered, engineer.player());
        journal.remove(complete);
        assertFalse(TransitJournal.open(path).locked(leg.ship()));
    }
}
