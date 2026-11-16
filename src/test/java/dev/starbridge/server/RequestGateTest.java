package dev.starbridge.server;

import org.junit.jupiter.api.Test;

import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

class RequestGateTest {
    @Test
    void expiredQueuedCommandsNeverMutateGameState() {
        AtomicLong clock = new AtomicLong(100);
        AtomicInteger changes = new AtomicInteger();
        RequestGate gate = new RequestGate(clock::get, 20, () -> true);
        clock.set(120);
        assertThrows(RequestExpiredException.class, () -> gate.execute(changes::incrementAndGet));
        assertEquals(0, changes.get());
    }

    @Test
    void cancelledCommandsCannotRunWhenTheServerQueueLaterRecovers() throws Exception {
        ExecutorService server = Executors.newSingleThreadExecutor();
        CountDownLatch releaseQueue = new CountDownLatch(1);
        AtomicInteger changes = new AtomicInteger();
        RequestGate gate = new RequestGate(System::nanoTime, TimeUnit.SECONDS.toNanos(10), () -> true);
        try {
            server.submit(() -> { releaseQueue.await(); return null; });
            Future<Integer> queued = server.submit(() -> gate.execute(changes::incrementAndGet));
            gate.cancel();
            releaseQueue.countDown();
            ExecutionException failure = assertThrows(ExecutionException.class, () -> queued.get(2, TimeUnit.SECONDS));
            assertInstanceOf(RequestExpiredException.class, failure.getCause());
            assertEquals(0, changes.get());
        } finally {
            releaseQueue.countDown();
            server.shutdownNow();
        }
    }

    @Test
    void shutdownInvalidatesRequestsWhichHaveNotStarted() {
        AtomicBoolean open = new AtomicBoolean(true);
        AtomicInteger changes = new AtomicInteger();
        RequestGate gate = new RequestGate(() -> 0, 10, open::get);
        open.set(false);
        assertThrows(RequestExpiredException.class, () -> gate.execute(changes::incrementAndGet));
        assertEquals(0, changes.get());
    }

    @Test
    void requestExecutesAtMostOnceAndHandlesNanoTimeWraparound() throws Exception {
        AtomicLong clock = new AtomicLong(Long.MAX_VALUE - 5);
        AtomicInteger changes = new AtomicInteger();
        RequestGate gate = new RequestGate(clock::get, 20, () -> true);
        clock.addAndGet(10);
        assertEquals(10, gate.remainingNanos());
        assertEquals(1, gate.execute(changes::incrementAndGet));
        assertThrows(RequestExpiredException.class, () -> gate.execute(changes::incrementAndGet));
        assertEquals(1, changes.get());
    }
}
