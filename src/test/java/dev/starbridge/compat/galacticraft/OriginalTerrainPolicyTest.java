package dev.starbridge.compat.galacticraft;

import static org.junit.jupiter.api.Assertions.*;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class OriginalTerrainPolicyTest {
    @TempDir Path world;

    private Path region(int dim, boolean populated) throws IOException {
        Path dir = Files.createDirectories(world.resolve("dimensions/retrofit/legacy_dim_" + dim + "/region"));
        byte[] bytes = new byte[populated ? 12288 : 8192];
        if (populated) ByteBuffer.wrap(bytes).putInt(4092, (2 << 8) | 1);
        return Files.write(dir.resolve("r.0.0.mca"), bytes);
    }

    @Test void preserveOnlyPreviouslyGeneratedDimensionsEvenAfterNewPlanetsGetChunks() throws IOException {
        region(-28, true);
        region(-29, false);
        assertEquals(Set.of(-28), OriginalTerrainPolicy.load(world));
        region(-29, true);
        region(-1007, true);
        assertEquals(Set.of(-28), OriginalTerrainPolicy.load(world));
    }

    @Test void freshWorldStaysOnTheHostSeedAfterRestart() throws IOException {
        assertTrue(OriginalTerrainPolicy.load(world).isEmpty());
        region(-28, true);
        assertTrue(OriginalTerrainPolicy.load(world).isEmpty());
    }

    @Test void unknownSettingsDoNotSilentlyChangeTheSeed() throws IOException {
        Files.createDirectories(world.resolve("data"));
        Path marker = Files.writeString(world.resolve("data").resolve(OriginalTerrainPolicy.FILE), "unexpected-version\n");
        assertThrows(IOException.class, () -> OriginalTerrainPolicy.load(world));
        assertEquals("unexpected-version\n", Files.readString(marker));
    }
}
