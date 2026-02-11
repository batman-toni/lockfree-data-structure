package lockfree.ringbuffer;

import lockfree.queue.LockFreeQueue;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Standalone demo that verifies {@link SpscRingBuffer} correctness and benchmarks
 * throughput against JDK queues and our {@link LockFreeQueue}, all in SPSC (1 producer,
 * 1 consumer) mode. Run via:
 * <pre>
 *   mvn exec:java -Dexec.mainClass="lockfree.ringbuffer.SpscRingBufferDemo"
 * </pre>
 */
public class SpscRingBufferDemo {

    private static final int RING_CAPACITY = 1024 * 64; // 64K slots

    public static void main(String[] args) throws InterruptedException {
        correctnessTest();
        performanceComparison();
    }

    // ── Correctness Test ────────────────────────────────────────────────

    static void correctnessTest() throws InterruptedException {
        System.out.println("\n=== SPSC Ring Buffer Correctness Test ===");

        final long itemCount = 10_000_000L;
        SpscRingBuffer<Long> ring = new SpscRingBuffer<>(RING_CAPACITY);

        Thread producer = Thread.ofPlatform().name("producer").start(() -> {
            for (long i = 0; i < itemCount; i++) {
                while (!ring.offer(i)) {
                    Thread.onSpinWait();
                }
            }
        });

        long[] received = new long[1]; // count
        long[] violations = new long[1];

        Thread consumer = Thread.ofPlatform().name("consumer").start(() -> {
            long expected = 0;
            while (expected < itemCount) {
                Long val = ring.poll();
                if (val == null) {
                    Thread.onSpinWait();
                    continue;
                }
                if (val != expected) {
                    violations[0]++;
                }
                expected++;
                received[0] = expected;
            }
        });

        producer.join();
        consumer.join();

        System.out.println("  Items sent:     " + itemCount);
        System.out.println("  Items received: " + received[0]);
        System.out.println("  FIFO violations:" + violations[0]);

        if (violations[0] > 0 || received[0] != itemCount) {
            System.err.println("  FAILED");
            System.exit(1);
        }
        System.out.println("  PASSED");
    }

    // ── Performance Comparison ──────────────────────────────────────────

    @FunctionalInterface
    interface SpscOps {
        /** Called once; should run producer/consumer and return total ops. */
        long run() throws InterruptedException;
    }

    static void performanceComparison() throws InterruptedException {
        System.out.println("\n=== SPSC Throughput Comparison ===");
        final long durationMs = 3_000;
        System.out.println("  Duration: " + durationMs + " ms each, 1 producer + 1 consumer\n");

        // ── SpscRingBuffer ──────────────────────────────────────────────
        long ringOps = benchmarkSpsc("SpscRingBuffer (ours)", durationMs, () -> {
            SpscRingBuffer<Long> ring = new SpscRingBuffer<>(RING_CAPACITY);
            return spscBench(durationMs,
                    val -> { while (!ring.offer(val)) Thread.onSpinWait(); },
                    () -> ring.poll());
        });

        // ── ArrayBlockingQueue ──────────────────────────────────────────
        long abqOps = benchmarkSpsc("ArrayBlockingQueue (JDK)", durationMs, () -> {
            ArrayBlockingQueue<Long> abq = new ArrayBlockingQueue<>(RING_CAPACITY);
            return spscBench(durationMs,
                    val -> { while (!abq.offer(val)) Thread.onSpinWait(); },
                    abq::poll);
        });

        // ── LinkedBlockingQueue ─────────────────────────────────────────
        long lbqOps = benchmarkSpsc("LinkedBlockingQueue (JDK)", durationMs, () -> {
            LinkedBlockingQueue<Long> lbq = new LinkedBlockingQueue<>(RING_CAPACITY);
            return spscBench(durationMs,
                    val -> { while (!lbq.offer(val)) Thread.onSpinWait(); },
                    lbq::poll);
        });

        // ── LockFreeQueue (ours, unbounded) ─────────────────────────────
        long lfqOps = benchmarkSpsc("LockFreeQueue (ours)", durationMs, () -> {
            LockFreeQueue<Long> lfq = new LockFreeQueue<>();
            return spscBench(durationMs,
                    val -> lfq.enqueue(val),
                    lfq::dequeue);
        });

        // ── ConcurrentLinkedQueue (JDK, unbounded) ──────────────────────
        long clqOps = benchmarkSpsc("ConcurrentLinkedQueue (JDK)", durationMs, () -> {
            ConcurrentLinkedQueue<Long> clq = new ConcurrentLinkedQueue<>();
            return spscBench(durationMs,
                    val -> clq.offer(val),
                    clq::poll);
        });

        System.out.println();
        System.out.printf("  SpscRingBuffer vs ArrayBlockingQueue:    %.2fx%n", (double) ringOps / abqOps);
        System.out.printf("  SpscRingBuffer vs LinkedBlockingQueue:   %.2fx%n", (double) ringOps / lbqOps);
        System.out.printf("  SpscRingBuffer vs LockFreeQueue:         %.2fx%n", (double) ringOps / lfqOps);
        System.out.printf("  SpscRingBuffer vs ConcurrentLinkedQueue: %.2fx%n", (double) ringOps / clqOps);
    }

    // ── Helpers ─────────────────────────────────────────────────────────

    @FunctionalInterface
    interface ProducerOp {
        void accept(long value);
    }

    @FunctionalInterface
    interface ConsumerOp {
        Long get();
    }

    /**
     * Runs a 1-producer / 1-consumer benchmark for the given duration and returns
     * total operations (producer offers + consumer polls that returned non-null).
     */
    static long spscBench(long durationMs, ProducerOp produce, ConsumerOp consume)
            throws InterruptedException {
        AtomicBoolean running = new AtomicBoolean(true);
        AtomicLong producerOps = new AtomicLong();
        AtomicLong consumerOps = new AtomicLong();

        Thread producer = Thread.ofPlatform().name("bench-producer").start(() -> {
            long ops = 0;
            long val = 0;
            while (running.get()) {
                produce.accept(val++);
                ops++;
            }
            producerOps.set(ops);
        });

        Thread consumer = Thread.ofPlatform().name("bench-consumer").start(() -> {
            long ops = 0;
            while (running.get()) {
                if (consume.get() != null) {
                    ops++;
                }
            }
            consumerOps.set(ops);
        });

        Thread.sleep(durationMs);
        running.set(false);
        producer.join();
        consumer.join();

        return producerOps.get() + consumerOps.get();
    }

    static long benchmarkSpsc(String label, long durationMs, SpscOps ops)
            throws InterruptedException {
        long totalOps = ops.run();
        long opsPerSec = (long) (totalOps / (durationMs / 1000.0));
        System.out.printf("  %-38s %,12d ops/sec%n", label, opsPerSec);
        return opsPerSec;
    }
}
