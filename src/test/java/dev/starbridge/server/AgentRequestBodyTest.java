package dev.starbridge.server;

import dev.starbridge.flight.Vec3d;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class AgentRequestBodyTest {
    @Test
    void finiteNumericCoordinatesAndEmptyControlBodiesAreAccepted() {
        assertEquals(new Vec3d(1, 90.5, -20), AgentRequestBody.parse("navigate", "{\"x\":1,\"y\":90.5,\"z\":-20}"));
        assertNull(AgentRequestBody.parse("hold", "{}"));
        assertNull(AgentRequestBody.parse("stop", "{}"));
    }

    @Test
    void ambiguousOrNonFiniteCommandsAreRejectedBeforeScheduling() {
        String[] malformed = {
                "{\"x\":1,\"x\":2,\"y\":0,\"z\":0}",
                "{\"x\":\"1\",\"y\":0,\"z\":0}",
                "{\"x\":true,\"y\":0,\"z\":0}",
                "{\"x\":1e999,\"y\":0,\"z\":0}",
                "{\"x\":NaN,\"y\":0,\"z\":0}",
                "{\"x\":{},\"y\":0,\"z\":0}",
                "{x:1,y:0,z:0}",
                "{\"x\":1,/*comment*/\"y\":0,\"z\":0}",
                "{\"x\":1,\"y\":0}",
                "{\"x\":1,\"y\":0,\"z\":0,\"command\":\"op\"}",
                "{\"x\":1,\"y\":0,\"z\":0}{}",
                "[]"
        };
        for (String body : malformed) {
            assertThrows(RuntimeException.class, () -> AgentRequestBody.parse("navigate", body), body);
        }
        assertThrows(IllegalArgumentException.class, () -> AgentRequestBody.parse("stop", "{\"x\":0}"));
    }

    @Test
    void deepJsonIsRejectedWithoutRecursiveParsing() {
        String nested = "{\"x\":" + "[".repeat(1_000) + "0" + "]".repeat(1_000) + ",\"y\":0,\"z\":0}";
        assertThrows(IllegalArgumentException.class, () -> AgentRequestBody.parse("navigate", nested));
    }
}
