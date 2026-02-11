package lockfree.stack;

import org.junit.jupiter.api.Test;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * JUnit 5 tests for {@link LockFreeStack}.
 */
class LockFreeStackTest {

    @Test
    void popOnEmptyReturnsNull() {
        LockFreeStack<Integer> stack = new LockFreeStack<>();
        assertNull(stack.pop());
        assertEquals(0, stack.sizeApprox());
    }

    @Test
    void pushPopLifo() {
        LockFreeStack<Integer> stack = new LockFreeStack<>();
        stack.push(1);
        stack.push(2);
        stack.push(3);
        assertEquals(3, stack.sizeApprox());
        assertEquals(3, stack.pop());
        assertEquals(2, stack.pop());
        assertEquals(1, stack.pop());
        assertNull(stack.pop());
    }

    @Test
    void pushNullThrows() {
        LockFreeStack<String> stack = new LockFreeStack<>();
        assertThrows(IllegalArgumentException.class, () -> stack.push(null));
    }

    @Test
    void singleElementPushPop() {
        LockFreeStack<String> stack = new LockFreeStack<>();
        stack.push("only");
        assertEquals("only", stack.pop());
        assertNull(stack.pop());
        assertEquals(0, stack.sizeApprox());
    }

    @Test
    void interleavedPushPop() {
        LockFreeStack<Integer> stack = new LockFreeStack<>();
        stack.push(10);
        stack.push(20);
        assertEquals(20, stack.pop());
        stack.push(30);
        assertEquals(30, stack.pop());
        assertEquals(10, stack.pop());
        assertNull(stack.pop());
    }

    @Test
    void concurrentStressTest() throws InterruptedException {
        final int numThreads = Runtime.getRuntime().availableProcessors();
        final long durationMs = 2_000;

        LockFreeStack<Long> stack = new LockFreeStack<>();
        ConcurrentHashMap<Long, AtomicInteger> pushed = new ConcurrentHashMap<>();
        ConcurrentHashMap<Long, AtomicInteger> popped = new ConcurrentHashMap<>();

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
                        stack.push(v);
                        pushed.computeIfAbsent(v, k -> new AtomicInteger()).incrementAndGet();
                    } else {
                        Long v = stack.pop();
                        if (v != null) {
                            popped.computeIfAbsent(v, k -> new AtomicInteger()).incrementAndGet();
                        }
                    }
                }
            });
        }

        for (Thread th : threads) th.join();

        // Drain
        Long v;
        while ((v = stack.pop()) != null) {
            popped.computeIfAbsent(v, k -> new AtomicInteger()).incrementAndGet();
        }

        // Verify: every popped value was pushed, count matches
        for (var entry : popped.entrySet()) {
            int popCount = entry.getValue().get();
            AtomicInteger pushCounter = pushed.get(entry.getKey());
            int pushCount = (pushCounter == null) ? 0 : pushCounter.get();
            assertTrue(popCount <= pushCount,
                    "value " + entry.getKey() + " popped " + popCount + "x but pushed " + pushCount + "x");
        }

        long totalPushed = pushed.values().stream().mapToInt(AtomicInteger::get).sum();
        long totalPopped = popped.values().stream().mapToInt(AtomicInteger::get).sum();
        assertEquals(totalPushed, totalPopped, "total push/pop count mismatch after drain");
    }
}
