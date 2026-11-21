package dev.starbridge.compat.retrofit;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class SharedRetrofitSettingsTest {
    private static final String BASE = "client {\n    B:PlanetSkyOverride=true\n}\nworld {\n    I:moonId=-28\n}\n";
    @Test void ignoresOnlyKnownClientLayoutAndKeepsEveryGameplaySetting() {
        var hud = BASE.replace("client {", "# client layout\nclient {\n    I:oxygenBarX=18\n    B:lockUI=true");
        assertEquals(SharedRetrofitSettings.rocketryHash(BASE), SharedRetrofitSettings.rocketryHash(hud));
        for (var change : new String[]{hud.replace("-28","-29"), hud.replace("Override=true","Override=false"),
                hud.replace("I:oxygenBarX=18","I:futureClientSetting=18"), hud.replace("world {","world {\n    I:oxygenBarX=18")})
            assertNotEquals(SharedRetrofitSettings.rocketryHash(BASE), SharedRetrofitSettings.rocketryHash(change));
        assertNotEquals(SharedRetrofitSettings.rocketryHash("S:values <\n#one\n>"),SharedRetrofitSettings.rocketryHash("S:values <\n#two\n>"));
    }
    @Test void preservesOtherModsAndFallsBackToUpstreamIfTheFileCannotBeRead(@TempDir Path dir) throws Exception {
        String ar="config/advRocketry/advancedRocketry.cfg";
        var fingerprints = Map.of(ar,"native","dimension-ids.txt","ids","config/splash.properties","timestamp","config/other.cfg","foreign");
        var shared = SharedRetrofitSettings.compare(fingerprints,dir);
        assertEquals(Map.of(ar,"native","dimension-ids.txt","ids","config/other.cfg","foreign"),shared);
        Files.createDirectories(dir.resolve(ar).getParent());Files.writeString(dir.resolve(ar),BASE);
        assertEquals(SharedRetrofitSettings.rocketryHash(BASE),SharedRetrofitSettings.compare(fingerprints,dir).get(ar));
        assertEquals("timestamp",fingerprints.get("config/splash.properties"));
    }
}
