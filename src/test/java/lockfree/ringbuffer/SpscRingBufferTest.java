package lockfree.ringbuffer;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

/**
 * JUnit 5 tests for {@link SpscRingBuffer}.
 */
class SpscRingBufferTest {

    // ── Single-threaded tests ───────────────────────────────────────────

    @Test
    void pollOnEmptyReturnsNull() {
        SpscRingBuffer<Integer> rb = new SpscRingBuffer<>(4);
        assertNull(rb.poll());
        assertNull(rb.peek());
        assertTrue(rb.isEmpty());
        assertFalse(rb.isFull());
        assertEquals(0, rb.size());
        assertEquals(4, rb.capacity());
    }

    @Test
    void offerPollFifo() {
        SpscRingBuffer<Integer> rb = new SpscRingBuffer<>(8);
        for (int i = 1; i <= 5; i++) assertTrue(rb.offer(i));

        assertEquals(5, rb.size());
        assertFalse(rb.isEmpty());
        assertEquals(1, rb.peek());

        for (int i = 1; i <= 5; i++) {
            assertEquals(i, rb.poll());
        }
        assertTrue(rb.isEmpty());
        assertNull(rb.poll());
    }

    @Test
    void offerNullThrows() {
        SpscRingBuffer<String> rb = new SpscRingBuffer<>(4);
        assertThrows(IllegalArgumentException.class, () -> rb.offer(null));
    }

    @Test
    void singleElement() {
        SpscRingBuffer<String> rb = new SpscRingBuffer<>(4);
        assertTrue(rb.offer("only"));
        assertEquals("only", rb.peek());
        assertEquals("only", rb.poll());
        assertNull(rb.poll());
        assertEquals(0, rb.size());
    }

    @Test
    void capacityEnforced() {
        SpscRingBuffer<Integer> rb = new SpscRingBuffer<>(4);
        assertTrue(rb.offer(1));
        assertTrue(rb.offer(2));
        assertTrue(rb.offer(3));
        assertTrue(rb.offer(4));
        assertTrue(rb.isFull());
        assertFalse(rb.offer(5)); // buffer full

        // After consuming one, we can offer again.
        assertEquals(1, rb.poll());
        assertFalse(rb.isFull());
        assertTrue(rb.offer(5));
    }

    @Test
    void wrapAroundCorrectness() {
        SpscRingBuffer<Integer> rb = new SpscRingBuffer<>(4);

        // Fill and drain multiple times to force index wrap-around.
        for (int round = 0; round < 10; round++) {
            for (int i = 0; i < 4; i++) assertTrue(rb.offer(round * 4 + i));
            assertTrue(rb.isFull());
            for (int i = 0; i < 4; i++) assertEquals(round * 4 + i, rb.poll());
            assertTrue(rb.isEmpty());
        }
    }

    @Test
    void invalidCapacityThrows() {
        assertThrows(IllegalArgumentException.class, () -> new SpscRingBuffer<>(0));
        assertThrows(IllegalArgumentException.class, () -> new SpscRingBuffer<>(-1));
        assertThrows(IllegalArgumentException.class, () -> new SpscRingBuffer<>(3));   // not power of two
        assertThrows(IllegalArgumentException.class, () -> new SpscRingBuffer<>(6));   // not power of two
        assertThrows(IllegalArgumentException.class, () -> new SpscRingBuffer<>(100)); // not power of two

        // These should succeed.
        assertDoesNotThrow(() -> new SpscRingBuffer<>(1));
        assertDoesNotThrow(() -> new SpscRingBuffer<>(2));
        assertDoesNotThrow(() -> new SpscRingBuffer<>(1024));
    }

    // ── Concurrent tests ────────────────────────────────────────────────

    @Test
    void spscStressTest() throws InterruptedException {
        final long itemCount = 10_000_000L;
        SpscRingBuffer<Long> rb = new SpscRingBuffer<>(1024 * 64);

        long[] violations = new long[1];
        long[] received = new long[1];

        Thread producer = Thread.ofPlatform().name("stress-producer").start(() -> {
            for (long i = 0; i < itemCount; i++) {
                while (!rb.offer(i)) {
                    Thread.onSpinWait();
                }
            }
        });

        Thread consumer = Thread.ofPlatform().name("stress-consumer").start(() -> {
            long expected = 0;
            while (expected < itemCount) {
                Long val = rb.poll();
                if (val == null) {
                    Thread.onSpinWait();
                    continue;
                }
                if (val != expected) {
                    violations[0]++;
                }
                expected++;
            }
            received[0] = expected;
        });

        producer.join();
        consumer.join();

        assertEquals(itemCount, received[0], "not all items received");
        assertEquals(0, violations[0], "FIFO order violated");
        assertTrue(rb.isEmpty(), "buffer not empty after test");
    }

    @Test
    void throughputMeasurement() throws InterruptedException {
        final long durationMs = 3_000;
        SpscRingBuffer<Long> rb = new SpscRingBuffer<>(1024 * 64);
        AtomicBoolean running = new AtomicBoolean(true);
        AtomicLong producerOps = new AtomicLong();
        AtomicLong consumerOps = new AtomicLong();

        Thread producer = Thread.ofPlatform().name("throughput-producer").start(() -> {
            long ops = 0;
            long val = 0;
            while (running.get()) {
                if (rb.offer(val)) {
                    val++;
                    ops++;
                } else {
                    Thread.onSpinWait();
                }
            }
            producerOps.set(ops);
        });

        Thread consumer = Thread.ofPlatform().name("throughput-consumer").start(() -> {
            long ops = 0;
            while (running.get()) {
                if (rb.poll() != null) {
                    ops++;
                } else {
                    Thread.onSpinWait();
                }
            }
            consumerOps.set(ops);
        });

        Thread.sleep(durationMs);
        running.set(false);
        producer.join();
        consumer.join();

        long totalOps = producerOps.get() + consumerOps.get();
        long opsPerSec = (long) (totalOps / (durationMs / 1000.0));
        System.out.printf("  SpscRingBuffer throughput: %,d ops/sec%n", opsPerSec);

        // Sanity: we expect at least some throughput.
        assertTrue(totalOps > 0, "no operations completed");
    }
}
