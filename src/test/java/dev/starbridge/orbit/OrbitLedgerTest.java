package dev.starbridge.orbit;

import static org.junit.jupiter.api.Assertions.*;

import dev.starbridge.flight.Vec3d;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.UUID;

class OrbitLedgerTest {
    @Test
    void worldReloadPreservesShipAndCabinButRequiresTheCaptainToResume(@TempDir Path temp)
            throws Exception {
        var ledger = new OrbitLedger();
        UUID ship = UUID.randomUUID(), pilot = UUID.randomUUID();
        var pose =
                new OrbitalFlight.Pose(new Vec3d(1400, 91.2, 620), new Vec3d(2, 0, -4.4), 156, 12);
        var flight = new OrbitLedger.Leg(ship, pilot, "home", "moon", pose);
        flight.reserve = 25024;
        flight.cruise = true;
        flight.braking = true;
        flight.suspended = false;
        var cabin =
                new OrbitLedger.Cabin(
                        "minecraft:overworld",
                        new BlockPos(302, 177, -2),
                        new BlockPos(300, 171, -5),
                        new BlockPos(599, 184, 14));
        ledger.legs.put(ship, flight);
        ledger.cabins.put(ship, cabin);
        var file = temp.resolve("orbits.dat").toFile();
        NbtIo.write(ledger.save(new CompoundTag()), file);
        var saved = OrbitLedger.load(NbtIo.read(file));
        var resumed = saved.legs.get(ship);
        assertEquals(pilot, resumed.captain);
        assertEquals("moon", resumed.to);
        assertEquals(pose, resumed.pose);
        assertEquals(flight.reserve, resumed.reserve);
        assertEquals(cabin, saved.cabins.get(ship));
        assertTrue(resumed.suspended);
        assertFalse(resumed.cruise);
        assertFalse(resumed.braking);
    }
}
