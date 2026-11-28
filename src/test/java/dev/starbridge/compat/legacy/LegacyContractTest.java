package dev.starbridge.compat.legacy;

import dev.starbridge.compat.legacy.LegacyContract.Realm;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class LegacyContractTest {
    private static final String BODY = FakeBody.class.getName();
    private final AtomicInteger loaderCalls = new AtomicInteger();

    public static final class FakeBody {
        public static int created;
        public int dimension = 7;
        private String secret = "hidden";
        public FakeBody() { created++; }
        public FakeBody(int dimension) { this.dimension = dimension; }
        public boolean getReachable() { return true; }
        public int scaled(int factor, String unused) { return dimension * factor; }
        public static String name() { return "moon"; }
    }

    @BeforeEach
    void install() {
        LegacyContract.useLoaders(realm -> {
            loaderCalls.incrementAndGet();
            return LegacyContractTest.class.getClassLoader();
        });
    }

    @AfterEach
    void restore() { LegacyContract.reset(); }

    @Test
    void resolvesAndInvokesDeclaredMembers() throws ReflectiveOperationException {
        var reachable = LegacyContract.method("galacticraft", Realm.LEGACY, BODY, "getReachable");
        var scaled = LegacyContract.method("galacticraft", Realm.LEGACY, BODY, "scaled", "int", String.class);
        var name = LegacyContract.method("galacticraft", Realm.LEGACY, BODY, "name");
        var dimension = LegacyContract.field("galacticraft", Realm.LEGACY, BODY, "dimension");
        var secret = LegacyContract.declaredField("galacticraft", Realm.LEGACY, BODY, "secret");
        var make = LegacyContract.constructor("galacticraft", Realm.LEGACY, BODY, "int");

        Object body = LegacyContract.create(make, 3);
        assertEquals(true, LegacyContract.invoke(reachable, body));
        assertEquals(12, LegacyContract.invoke(scaled, body, 4, "x"));
        assertEquals("moon", LegacyContract.invoke(name, null));
        assertEquals(3, LegacyContract.get(dimension, body));
        assertEquals("hidden", LegacyContract.get(secret, body));
        assertSame(FakeBody.class, LegacyContract.type(reachable));
        assertTrue(LegacyContract.verify(List.of(reachable, scaled, name, dimension, secret, make)).isEmpty());
    }

    @Test
    void reportsEachMissingReferenceByName() {
        var renamed = LegacyContract.method("advancedrocketry", Realm.LEGACY, BODY, "getRocketPadBounds", "int", "int", "int");
        var absent = LegacyContract.type("galacticraft", Realm.LEGACY, "micdoodle8.mods.galacticraft.api.galaxies.GalaxyRegistry");
        var field = LegacyContract.field("libvulpes", Realm.HOST, BODY, "storage");
        var fine = LegacyContract.method("galacticraft", Realm.LEGACY, BODY, "getReachable");

        var missing = LegacyContract.verify(List.of(renamed, absent, field, fine));
        assertEquals(3, missing.size());
        assertEquals("member not found", missing.get(0).reason());
        assertTrue(missing.get(0).describe().contains("FakeBody#getRocketPadBounds(int,int,int)"), missing.get(0).describe());
        assertEquals("class not found", missing.get(1).reason());
        assertEquals("member not found", missing.get(2).reason());
        assertEquals(1, LegacyContract.missingFor(missing, "advancedrocketry").size());
        assertEquals(List.of(), LegacyContract.missingFor(missing, "retrofit"));
    }

    @Test
    void resolvesOnlyOncePerLoader() throws ReflectiveOperationException {
        var reachable = LegacyContract.method("galacticraft", Realm.LEGACY, BODY, "getReachable");
        var first = LegacyContract.method(reachable);
        var second = LegacyContract.method(reachable);
        assertSame(first, second, "a resolved member must be cached");
    }

    @Test
    void reportsALegacyRuntimeThatHasNotStarted() {
        LegacyContract.useLoaders(realm -> realm == Realm.LEGACY ? null : LegacyContractTest.class.getClassLoader());
        var reachable = LegacyContract.method("galacticraft", Realm.LEGACY, BODY, "getReachable");
        var missing = LegacyContract.verify(List.of(reachable));
        assertEquals(1, missing.size());
        assertEquals("class not found", missing.get(0).reason());
        assertThrows(ClassNotFoundException.class, () -> LegacyContract.method(reachable));
    }
}
