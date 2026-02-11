package lockfree.stack;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicStampedReference;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

/**
 * A lock-free stack (Treiber stack) using compare-and-swap (CAS).
 *
 * <p>This is a classic lock-free data structure where {@code push} and {@code pop}
 * are linearizable. The stack is implemented as a singly-linked list with a CAS-updated
 * head pointer. All operations are non-blocking: no thread can be indefinitely prevented
 * from making progress by the suspension of other threads.
 *
 * <h3>ABA Problem</h3>
 * <p>The ABA problem occurs when a thread reads head=A, gets preempted, another thread
 * pops A then B, then pushes A back. The first thread's CAS succeeds because head is A
 * again, but the stack structure underneath has changed. In this Treiber stack the ABA
 * problem is <b>benign</b> because:
 * <ul>
 *   <li>Nodes are never reused — each {@code push} allocates a fresh {@link Node}.</li>
 *   <li>Since Java is garbage-collected, a node cannot be freed and its memory recycled
 *       while any thread still holds a reference to it.</li>
 * </ul>
 * <p>Therefore the same Node object identity cannot reappear at the head unless it truly
 * is the same logical node, making the CAS correct.
 *
 * <p>For environments where node recycling is used (e.g., memory pools in C/C++), see
 * {@link AbaResistantLockFreeStack} which uses {@link AtomicStampedReference} to prevent
 * ABA by attaching a monotonic stamp to the head pointer.
 *
 * @param <T> the element type (must not be {@code null})
 */
public class LockFreeStack<T> {

    /**
     * Immutable node in the stack's linked list.
     * All fields are final — once constructed, a node never changes.
     */
    private static final class Node<T> {
        final T value;
        final Node<T> next;

        Node(T value, Node<T> next) {
            this.value = value;
            this.next = next;
        }
    }

    /** Head pointer, updated atomically via CAS. */
    private final AtomicReference<Node<T>> head = new AtomicReference<>(null);

    /** Approximate element count. Incremented/decremented non-atomically with head updates. */
    private final AtomicInteger size = new AtomicInteger(0);

    /**
     * Pushes a value onto the top of the stack.
     *
     * <p><b>Linearization point:</b> the successful {@code compareAndSet} on {@code head}
     * that swings the head from the old top to the new node.
     *
     * @param value the value to push (must not be {@code null})
     * @throws IllegalArgumentException if {@code value} is {@code null}
     */
    public void push(T value) {
        if (value == null) {
            throw new IllegalArgumentException("null values are not permitted");
        }

        // CAS retry loop: read current head, build a new node pointing to it,
        // then attempt to swing head to the new node. If another thread modified
        // head between our read and CAS, the CAS fails and we retry with the
        // updated head. The new Node is allocated outside the loop body only
        // when we can — but since `next` must match the snapshot, we allocate
        // inside the loop (unavoidable without a mutable next field).
        Node<T> newHead;
        Node<T> oldHead;
        do {
            oldHead = head.get();           // snapshot
            newHead = new Node<>(value, oldHead);
        } while (!head.compareAndSet(oldHead, newHead));  // linearization point

        size.incrementAndGet();
    }

    /**
     * Pops and returns the value at the top of the stack.
     *
     * <p><b>Linearization point:</b> the successful {@code compareAndSet} on {@code head}
     * that swings the head from the current top node to its successor. If the stack is
     * empty, the linearization point is the read of {@code head} that observed {@code null}.
     *
     * @return the value at the top, or {@code null} if the stack is empty
     */
    public T pop() {
        Node<T> oldHead;
        Node<T> newHead;
        do {
            oldHead = head.get();           // snapshot
            if (oldHead == null) {
                return null;                // linearization point (empty stack)
            }
            newHead = oldHead.next;
        } while (!head.compareAndSet(oldHead, newHead));  // linearization point

        size.decrementAndGet();
        return oldHead.value;
    }

    /**
     * Returns an <em>approximate</em> number of elements in the stack.
     *
     * <p>This value is <b>not</b> linearizable with respect to concurrent pushes and pops.
     * The counter is updated after the CAS on {@code head}, so a concurrent observer may
     * momentarily see a size that does not match the true number of reachable nodes.
     * It is useful for monitoring and heuristics, not for precise control flow.
     *
     * @return a non-negative approximate size
     */
    public int sizeApprox() {
        return Math.max(size.get(), 0);
    }

    // -------------------------------------------------------------------------
    // ABA-resistant variant using AtomicStampedReference
    // -------------------------------------------------------------------------

    /**
     * A Treiber stack that mitigates the ABA problem using {@link AtomicStampedReference}.
     *
     * <p>Each CAS carries a monotonically increasing stamp. Even if a node with the same
     * identity were placed back at the head, the stamp would differ, causing the CAS to
     * fail correctly. This is primarily useful when nodes can be recycled (e.g., in native
     * memory pools); in standard Java with GC it is unnecessary but shown for completeness.
     *
     * @param <T> the element type
     */
    public static final class AbaResistantLockFreeStack<T> {

        private static final class Node<T> {
            final T value;
            final Node<T> next;

            Node(T value, Node<T> next) {
                this.value = value;
                this.next = next;
            }
        }

        private final AtomicStampedReference<Node<T>> head =
                new AtomicStampedReference<>(null, 0);

        public void push(T value) {
            if (value == null) {
                throw new IllegalArgumentException("null values are not permitted");
            }
            Node<T> newHead;
            int[] stampHolder = new int[1];
            Node<T> oldHead;
            do {
                oldHead = head.get(stampHolder);
                newHead = new Node<>(value, oldHead);
            } while (!head.compareAndSet(oldHead, newHead, stampHolder[0], stampHolder[0] + 1));
        }

        public T pop() {
            int[] stampHolder = new int[1];
            Node<T> oldHead;
            Node<T> newHead;
            do {
                oldHead = head.get(stampHolder);
                if (oldHead == null) {
                    return null;
                }
                newHead = oldHead.next;
            } while (!head.compareAndSet(oldHead, newHead, stampHolder[0], stampHolder[0] + 1));
            return oldHead.value;
        }
    }

    // -------------------------------------------------------------------------
    // Demo main
    // -------------------------------------------------------------------------

    /**
     * Starts {@code N} threads that randomly push/pop for a fixed duration and then
     * verifies basic invariants and prints throughput.
     */
    public static void main(String[] args) throws InterruptedException {
        final int numThreads = Runtime.getRuntime().availableProcessors();
        final long durationMs = 3_000;

        final LockFreeStack<Long> stack = new LockFreeStack<>();

        final ConcurrentHashMap<Long, AtomicInteger> pushed = new ConcurrentHashMap<>();
        final ConcurrentHashMap<Long, AtomicInteger> popped = new ConcurrentHashMap<>();
        final AtomicInteger totalOps = new AtomicInteger(0);

        final long deadline = System.currentTimeMillis() + durationMs;

        Thread[] threads = new Thread[numThreads];
        for (int t = 0; t < numThreads; t++) {
            final int threadId = t;
            threads[t] = Thread.ofPlatform().name("worker-" + t).start(() -> {
                int ops = 0;
                ThreadLocalRandom rng = ThreadLocalRandom.current();
                long nextVal = (long) threadId * 1_000_000_000L;
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
                    ops++;
                }
                totalOps.addAndGet(ops);
            });
        }

        for (Thread th : threads) th.join();

        Long v;
        while ((v = stack.pop()) != null) {
            popped.computeIfAbsent(v, k -> new AtomicInteger()).incrementAndGet();
        }

        int violations = 0;
        for (var entry : popped.entrySet()) {
            int popCount = entry.getValue().get();
            AtomicInteger pushCounter = pushed.get(entry.getKey());
            int pushCount = pushCounter == null ? 0 : pushCounter.get();
            if (popCount > pushCount) {
                System.err.println("VIOLATION: value " + entry.getKey()
                        + " popped " + popCount + " times but pushed " + pushCount);
                violations++;
            }
        }

        long totalPushed = pushed.values().stream().mapToInt(AtomicInteger::get).sum();
        long totalPopped = popped.values().stream().mapToInt(AtomicInteger::get).sum();

        System.out.println("Threads:        " + numThreads);
        System.out.println("Duration:       " + durationMs + " ms");
        System.out.println("Total ops:      " + totalOps.get());
        System.out.printf("Throughput:     %,.0f ops/sec%n", totalOps.get() / (durationMs / 1000.0));
        System.out.println("Total pushed:   " + totalPushed);
        System.out.println("Total popped:   " + totalPopped);
        System.out.println("Approx size:    " + stack.sizeApprox());
        System.out.println("Violations:     " + violations);

        if (violations > 0) {
            System.err.println("TEST FAILED — found integrity violations!");
            System.exit(1);
        }
        if (totalPushed != totalPopped) {
            System.err.println("TEST FAILED — push/pop count mismatch after drain!");
            System.exit(1);
        }
        System.out.println("TEST PASSED");
    }
}
