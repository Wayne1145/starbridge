package dev.starbridge.transit;

import dev.starbridge.compat.vs.VsShipBackend.StorageBounds;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
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
                List.of(pilot,engineer), Set.of(), false, new StorageBounds(40,0,-310,58,12,-290), "a".repeat(64), null);
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

    @Test void restartAfterFeeSaveVerifiesExactCargoAndCoreBeforeCompleting() throws Exception {
        for (UUID previous : new UUID[] {null, UUID.randomUUID()}) {
            Path path = directory.resolve(previous == null ? "outbound.dat" : "return.dat");
            Path savedShip = path.resolveSibling(path.getFileName() + ".ship");
            var corePos = new BlockPos(48, 4, -300);
            var pilot = new TransitJournal.Rider(UUID.randomUUID(), Vec3.ZERO, 0, 0);
            UUID coreId = UUID.randomUUID(), voyage = UUID.randomUUID();
            CompoundTag core = new CompoundTag(), chest = new CompoundTag(), furnace = new CompoundTag();
            core.putUUID("CoreId", coreId);
            core.putUUID("Owner", pilot.player());
            core.putInt("Energy", 22000);
            if (previous != null) core.putUUID("PaidVoyage", previous);
            chest.putInt("Diamonds", 17);
            furnace.putShort("CookTime", (short) 1);
            var leg = new TransitJournal.Leg(voyage, coreId, 91, corePos, "minecraft:overworld", "unbound:moon",
                    Vec3.ZERO, "moon", 12000, List.of(pilot), Set.of(), false, new StorageBounds(47,4,-300,49,4,-300),
                    digest(null, corePos, core, chest, furnace), null);
            TransitJournal journal = TransitJournal.open(path);
            journal.prepare(leg);
            // Normal machines may tick after native transfer but before the fee is taken.
            furnace.putShort("CookTime", (short) 5);
            String arrival = digest(null, corePos, core, chest, furnace);
            assertNotEquals(leg.digest(), arrival);
            journal.preparePayment(leg, TransitJournal.FareProof.capture(core, arrival));
            core.putInt("Energy", 10000);
            core.putUUID("PaidVoyage", voyage);
            CompoundTag ship = new CompoundTag();
            ship.put("Core", core); ship.put("Chest", chest); ship.put("Furnace", furnace);
            NbtIo.write(ship, savedShip.toFile());

            journal = TransitJournal.open(path);
            var pending = journal.legs().iterator().next();
            assertFalse(pending.committed());
            assertTrue(journal.locked(leg.ship()));
            ship = NbtIo.read(savedShip.toFile());
            assertNotNull(ship);
            core = ship.getCompound("Core"); chest = ship.getCompound("Chest"); furnace = ship.getCompound("Furnace");
            assertEquals(arrival, pending.expectedArrivalDigest());
            assertEquals(arrival, digest(pending, corePos, core, chest, furnace));
            assertEquals(voyage, core.getUUID("PaidVoyage"));
            assertEquals(10000, core.getInt("Energy"));

            CompoundTag changed = core.copy(); changed.putInt("Energy", 9999);
            assertNotEquals(arrival, digest(pending, corePos, changed, chest, furnace));
            changed = core.copy(); changed.putUUID("PaidVoyage", UUID.randomUUID());
            assertNotEquals(arrival, digest(pending, corePos, changed, chest, furnace));
            changed = core.copy(); changed.putUUID("Owner", UUID.randomUUID());
            assertNotEquals(arrival, digest(pending, corePos, changed, chest, furnace));
            changed = chest.copy(); changed.putInt("Diamonds", 16);
            assertNotEquals(arrival, digest(pending, corePos, core, changed, furnace));
            changed = furnace.copy(); changed.putShort("CookTime", (short) 0);
            assertNotEquals(arrival, digest(pending, corePos, core, chest, changed));
            assertSame(core, pending.beforePayment(corePos.east(), core));
            var committed = journal.commit(pending);
            journal.remove(journal.deliver(committed, pilot.player()));
            assertFalse(TransitJournal.open(path).locked(leg.ship()));
        }
    }
    private static String digest(TransitJournal.Leg passage, BlockPos corePos, CompoundTag core,
                                 CompoundTag chest, CompoundTag furnace) throws NoSuchAlgorithmException {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        HullManifest.appendEntity(digest, corePos, core, passage);
        HullManifest.appendEntity(digest, corePos.east(), chest, passage);
        HullManifest.appendEntity(digest, corePos.west(), furnace, passage);
        return HexFormat.of().formatHex(digest.digest());
    }
}
