package lockfree.queue;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

/**
 * JUnit 5 tests for {@link LockFreeQueue}.
 */
class LockFreeQueueTest {

    @Test
    void dequeueOnEmptyReturnsNull() {
        LockFreeQueue<Integer> q = new LockFreeQueue<>();
        assertNull(q.dequeue());
        assertNull(q.peek());
        assertTrue(q.isEmpty());
        assertEquals(0, q.size());
    }

    @Test
    void enqueueDequeueFifo() {
        LockFreeQueue<Integer> q = new LockFreeQueue<>();
        for (int i = 1; i <= 5; i++) q.enqueue(i);

        assertEquals(5, q.size());
        assertFalse(q.isEmpty());
        assertEquals(1, q.peek());

        for (int i = 1; i <= 5; i++) {
            assertEquals(i, q.dequeue());
        }
        assertTrue(q.isEmpty());
        assertNull(q.dequeue());
    }

    @Test
    void enqueueNullThrows() {
        LockFreeQueue<String> q = new LockFreeQueue<>();
        assertThrows(IllegalArgumentException.class, () -> q.enqueue(null));
    }

    @Test
    void singleElement() {
        LockFreeQueue<String> q = new LockFreeQueue<>();
        q.enqueue("only");
        assertEquals("only", q.peek());
        assertEquals("only", q.dequeue());
        assertNull(q.dequeue());
        assertEquals(0, q.size());
    }

    @Test
    void interleavedEnqueueDequeue() {
        LockFreeQueue<Integer> q = new LockFreeQueue<>();
        q.enqueue(10);
        q.enqueue(20);
        assertEquals(10, q.dequeue());
        q.enqueue(30);
        assertEquals(20, q.dequeue());
        assertEquals(30, q.dequeue());
        assertTrue(q.isEmpty());
    }

    @Test
    void concurrentStressTest() throws InterruptedException {
        final int numThreads = Runtime.getRuntime().availableProcessors();
        final long durationMs = 2_000;

        LockFreeQueue<Long> queue = new LockFreeQueue<>();
        ConcurrentHashMap<Long, AtomicInteger> enqueued = new ConcurrentHashMap<>();
        ConcurrentHashMap<Long, AtomicInteger> dequeued = new ConcurrentHashMap<>();

        long deadline = System.currentTimeMillis() + durationMs;
        Thread[] threads = new Thread[numThreads];

        for (int t = 0; t < numThreads; t++) {
            final long base = (long) t * 1_000_000_000L;
            threads[t] = Thread.ofPlatform().start(() -> {
                long nextVal = base;
                ThreadLocalRandom rng = ThreadLocalRandom.current();
                while (System.currentTimeMillis() < deadline) {
                    if (rng.nextBoolean()) {
                        long v = nextVal++;
                        queue.enqueue(v);
                        enqueued.computeIfAbsent(v, k -> new AtomicInteger()).incrementAndGet();
                    } else {
                        Long v = queue.dequeue();
                        if (v != null) {
                            dequeued.computeIfAbsent(v, k -> new AtomicInteger()).incrementAndGet();
                        }
                    }
                }
            });
        }

        for (Thread th : threads) th.join();

        // Drain
        Long v;
        while ((v = queue.dequeue()) != null) {
            dequeued.computeIfAbsent(v, k -> new AtomicInteger()).incrementAndGet();
        }

        for (var entry : dequeued.entrySet()) {
            int dCount = entry.getValue().get();
            AtomicInteger eCounter = enqueued.get(entry.getKey());
            int eCount = (eCounter == null) ? 0 : eCounter.get();
            assertTrue(dCount <= eCount,
                    "value " + entry.getKey() + " dequeued " + dCount + "x but enqueued " + eCount + "x");
        }

        long totalEnq = enqueued.values().stream().mapToInt(AtomicInteger::get).sum();
        long totalDeq = dequeued.values().stream().mapToInt(AtomicInteger::get).sum();
        assertEquals(totalEnq, totalDeq, "total enqueue/dequeue count mismatch after drain");
    }

    @Test
    void fifoOrderingPerProducer() throws InterruptedException {
        final int producers = 4;
        final int consumers = 4;
        final int itemsPerProducer = 100_000;
        final long totalItems = (long) producers * itemsPerProducer;

        LockFreeQueue<Long> queue = new LockFreeQueue<>();
        AtomicLong consumed = new AtomicLong(0);

        @SuppressWarnings("unchecked")
        List<Long>[] perConsumer = new List[consumers];
        for (int i = 0; i < consumers; i++) perConsumer[i] = new ArrayList<>();

        Thread[] prodThreads = new Thread[producers];
        Thread[] consThreads = new Thread[consumers];

        for (int p = 0; p < producers; p++) {
            final long base = (long) p * 1_000_000_000L;
            prodThreads[p] = Thread.ofPlatform().start(() -> {
                for (long i = 0; i < itemsPerProducer; i++) {
                    queue.enqueue(base + i);
                }
            });
        }

        for (int c = 0; c < consumers; c++) {
            final int cid = c;
            consThreads[c] = Thread.ofPlatform().start(() -> {
                List<Long> local = perConsumer[cid];
                while (consumed.get() < totalItems) {
                    Long val = queue.dequeue();
                    if (val == null) {
                        Thread.onSpinWait();
                        continue;
                    }
                    local.add(val);
                    consumed.incrementAndGet();
                }
            });
        }

        for (Thread t : prodThreads) t.join();
        for (Thread t : consThreads) t.join();

        // Verify per-producer FIFO ordering within each consumer's observation
        int violations = 0;
        for (int c = 0; c < consumers; c++) {
            long[] lastSeq = new long[producers];
            for (int i = 0; i < producers; i++) lastSeq[i] = -1;
            for (long val : perConsumer[c]) {
                int pid = (int) (val / 1_000_000_000L);
                long seq = val % 1_000_000_000L;
                if (seq <= lastSeq[pid]) violations++;
                lastSeq[pid] = seq;
            }
        }

        assertEquals(0, violations, "FIFO order violated for per-producer sequences");
    }

    @Test
    void toStringShowsElements() {
        LockFreeQueue<Integer> q = new LockFreeQueue<>();
        assertEquals("LockFreeQueue[]", q.toString());
        q.enqueue(1);
        q.enqueue(2);
        assertEquals("LockFreeQueue[1, 2]", q.toString());
    }
}
