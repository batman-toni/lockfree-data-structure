package lockfree.queue;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Standalone demo that stress-tests {@link LockFreeQueue} and prints throughput,
 * then compares against JDK queues. Run via {@code gradle runQueueDemo}.
 */
public class LockFreeQueueDemo {

    @FunctionalInterface
    interface QueueOp {
        void run(boolean enqueue, long value);
    }

    public static void main(String[] args) throws InterruptedException {
        stressTest();
        performanceComparison();
    }

    static void stressTest() throws InterruptedException {
        System.out.println("\n=== Lock-Free Queue Stress Test ===");

        final int numThreads = Runtime.getRuntime().availableProcessors();
        final long durationMs = 3_000;

        LockFreeQueue<Long> queue = new LockFreeQueue<>();
        ConcurrentHashMap<Long, AtomicInteger> enqueued = new ConcurrentHashMap<>();
        ConcurrentHashMap<Long, AtomicInteger> dequeued = new ConcurrentHashMap<>();
        AtomicLong totalOps = new AtomicLong(0);

        long deadline = System.currentTimeMillis() + durationMs;
        Thread[] threads = new Thread[numThreads];

        for (int t = 0; t < numThreads; t++) {
            final long base = (long) t * 1_000_000_000L;
            threads[t] = Thread.ofPlatform().name("stress-" + t).start(() -> {
                long ops = 0;
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
                    ops++;
                }
                totalOps.addAndGet(ops);
            });
        }

        for (Thread th : threads) th.join();

        Long v;
        while ((v = queue.dequeue()) != null) {
            dequeued.computeIfAbsent(v, k -> new AtomicInteger()).incrementAndGet();
        }

        int violations = 0;
        for (var entry : dequeued.entrySet()) {
            int dCount = entry.getValue().get();
            AtomicInteger eCounter = enqueued.get(entry.getKey());
            int eCount = (eCounter == null) ? 0 : eCounter.get();
            if (dCount > eCount) {
                System.err.println("  VIOLATION: " + entry.getKey()
                        + " dequeued " + dCount + "x but enqueued " + eCount + "x");
                violations++;
            }
        }

        long totalEnq = enqueued.values().stream().mapToInt(AtomicInteger::get).sum();
        long totalDeq = dequeued.values().stream().mapToInt(AtomicInteger::get).sum();

        System.out.println("  Threads:      " + numThreads);
        System.out.println("  Duration:     " + durationMs + " ms");
        System.out.println("  Total ops:    " + totalOps.get());
        System.out.printf("  Throughput:   %,.0f ops/sec%n", totalOps.get() / (durationMs / 1000.0));
        System.out.println("  Enqueued:     " + totalEnq);
        System.out.println("  Dequeued:     " + totalDeq);
        System.out.println("  Violations:   " + violations);

        if (violations > 0 || totalEnq != totalDeq) {
            System.err.println("  FAILED");
            System.exit(1);
        }
        System.out.println("  PASSED");
    }

    static long benchmarkQueue(String label, int threads, long durationMs, QueueOp op)
            throws InterruptedException {
        AtomicLong totalOps = new AtomicLong(0);
        long deadline = System.currentTimeMillis() + durationMs;
        Thread[] workers = new Thread[threads];
        for (int t = 0; t < threads; t++) {
            final long base = (long) t * 1_000_000_000L;
            workers[t] = Thread.ofPlatform().name(label + "-" + t).start(() -> {
                long ops = 0;
                long nextVal = base;
                ThreadLocalRandom rng = ThreadLocalRandom.current();
                while (System.currentTimeMillis() < deadline) {
                    op.run(rng.nextBoolean(), nextVal++);
                    ops++;
                }
                totalOps.addAndGet(ops);
            });
        }
        for (Thread w : workers) w.join();
        long opsPerSec = (long) (totalOps.get() / (durationMs / 1000.0));
        System.out.printf("  %-30s %,12d ops/sec%n", label, opsPerSec);
        return opsPerSec;
    }

    static void performanceComparison() throws InterruptedException {
        System.out.println("\n=== Performance Comparison ===");
        final int threads = Runtime.getRuntime().availableProcessors();
        final long durationMs = 3_000;
        System.out.println("  Threads: " + threads + ", Duration: " + durationMs + " ms each\n");

        LockFreeQueue<Long> lfq = new LockFreeQueue<>();
        long lfqOps = benchmarkQueue("LockFreeQueue (ours)", threads, durationMs,
                (enq, val) -> { if (enq) lfq.enqueue(val); else lfq.dequeue(); });

        ConcurrentLinkedQueue<Long> clq = new ConcurrentLinkedQueue<>();
        long clqOps = benchmarkQueue("ConcurrentLinkedQueue (JDK)", threads, durationMs,
                (enq, val) -> { if (enq) clq.offer(val); else clq.poll(); });

        LinkedBlockingQueue<Long> lbq = new LinkedBlockingQueue<>();
        long lbqOps = benchmarkQueue("LinkedBlockingQueue (JDK)", threads, durationMs,
                (enq, val) -> { if (enq) lbq.offer(val); else lbq.poll(); });

        System.out.println();
        System.out.printf("  LockFreeQueue vs ConcurrentLinkedQueue:  %.2fx%n", (double) lfqOps / clqOps);
        System.out.printf("  LockFreeQueue vs LinkedBlockingQueue:    %.2fx%n", (double) lfqOps / lbqOps);
    }
}
