package lockfree.ringbuffer;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;

/**
 * A bounded, lock-free, single-producer / single-consumer (SPSC) ring buffer queue.
 *
 * <h2>Algorithm Overview</h2>
 * <p>The buffer is a fixed-size array indexed by two monotonically increasing counters:
 * {@code tail} (written exclusively by the producer) and {@code head} (written exclusively by
 * the consumer). The actual array slot is computed as {@code index & mask} where
 * {@code mask = capacity - 1} (capacity must be a power of two). Because each counter is
 * owned by exactly one thread, <b>no CAS operations are needed</b> — simple acquire/release
 * loads and stores are sufficient for correct inter-thread communication.</p>
 *
 * <h2>Offer (Producer only)</h2>
 * <ol>
 *   <li>Plain read own {@code tail} (only this thread writes it).</li>
 *   <li>Check space: if {@code tail - cachedHead >= capacity}, refresh {@code cachedHead}
 *       via {@code HEAD.getAcquire()}. If still full, return {@code false}.</li>
 *   <li>Plain store element into {@code buffer[tail & mask]}.</li>
 *   <li>{@code TAIL.setRelease(this, tail + 1)} — <b>linearization point</b>.
 *       The release fence ensures the buffer write is visible before the consumer sees
 *       the updated tail.</li>
 * </ol>
 *
 * <h2>Poll (Consumer only)</h2>
 * <ol>
 *   <li>Plain read own {@code head} (only this thread writes it).</li>
 *   <li>Check data: if {@code head == cachedTail}, refresh {@code cachedTail}
 *       via {@code TAIL.getAcquire()}. If still empty, return {@code null}.</li>
 *   <li>Plain load element from {@code buffer[head & mask]}, null out slot for GC.</li>
 *   <li>{@code HEAD.setRelease(this, head + 1)} — <b>linearization point</b>.
 *       The release fence makes the freed slot visible to the producer.</li>
 * </ol>
 *
 * <h2>Why No CAS?</h2>
 * <p>Each index ({@code head}, {@code tail}) is written by exactly one thread. There is no
 * concurrent writer to race against, so a plain store with release ordering is sufficient.
 * The "other" thread only ever <em>reads</em> the peer's index (with acquire ordering) to
 * detect available space or data. This is the key insight that makes SPSC ring buffers
 * dramatically faster than MPMC queues — typically 100x+ higher throughput.</p>
 *
 * <h2>Cache-Line Padding</h2>
 * <p>The {@code head} and {@code tail} fields are separated by manual {@code long} padding
 * fields (8 longs = 64 bytes each) to prevent <em>false sharing</em>. Without padding, both
 * indices could land on the same CPU cache line (typically 64 bytes), causing constant
 * cache-line bouncing between the producer and consumer cores. This is the same technique
 * used by LMAX Disruptor and JCTools.</p>
 *
 * <h2>Cached Indices</h2>
 * <p>Each thread maintains a local cache of the other thread's index ({@code cachedHead} for
 * the producer, {@code cachedTail} for the consumer). This avoids cross-core reads on every
 * operation — the volatile acquire read only happens when the local cache indicates the buffer
 * appears full or empty. In practice, this reduces cross-core traffic to O(1/capacity)
 * amortized per operation.</p>
 *
 * <h2>Memory Ordering</h2>
 * <p>{@link VarHandle} operations used in this implementation:</p>
 * <ul>
 *   <li>{@code getAcquire()} — acquire-load. Ensures all writes by the thread that performed
 *       the corresponding release-store are visible to this thread after the load.</li>
 *   <li>{@code setRelease()} — release-store. Ensures all prior writes by this thread are
 *       visible to any thread that subsequently performs an acquire-load of this variable.</li>
 * </ul>
 * <p>These are weaker than full volatile read/write (which add sequential consistency) but
 * are sufficient for the SPSC protocol and allow better CPU pipeline utilization.</p>
 *
 * @param <T> the element type; {@code null} elements are not permitted
 */
public class SpscRingBuffer<T> {

    /** Backing array. Indexed by {@code counter & mask}. */
    private final T[] buffer;

    /** Buffer capacity (always a power of two). */
    private final int capacity;

    /** Bitmask for fast modulo: {@code index & mask == index % capacity}. */
    private final int mask;

    // ── Cache-line padding before tail ──────────────────────────────────
    @SuppressWarnings("unused") private long p00, p01, p02, p03, p04, p05, p06, p07;

    /**
     * Producer write index. Only the producer thread writes this field;
     * the consumer reads it (via {@code TAIL.getAcquire()}) to detect available data.
     */
    private volatile long tail;

    // ── Cache-line padding between tail and head ────────────────────────
    @SuppressWarnings("unused") private long p10, p11, p12, p13, p14, p15, p16, p17;

    /**
     * Consumer read index. Only the consumer thread writes this field;
     * the producer reads it (via {@code HEAD.getAcquire()}) to detect available space.
     */
    private volatile long head;

    // ── Cache-line padding after head ───────────────────────────────────
    @SuppressWarnings("unused") private long p20, p21, p22, p23, p24, p25, p26, p27;

    /**
     * Producer's local cache of the consumer's {@code head}. Avoids a cross-core acquire
     * read on every {@link #offer}; refreshed only when the buffer appears full.
     */
    private long cachedHead;

    /**
     * Consumer's local cache of the producer's {@code tail}. Avoids a cross-core acquire
     * read on every {@link #poll}; refreshed only when the buffer appears empty.
     */
    private long cachedTail;

    /** VarHandle for acquire/release access to {@link #tail}. */
    private static final VarHandle TAIL;

    /** VarHandle for acquire/release access to {@link #head}. */
    private static final VarHandle HEAD;

    static {
        try {
            MethodHandles.Lookup lookup = MethodHandles.lookup();
            TAIL = lookup.findVarHandle(SpscRingBuffer.class, "tail", long.class);
            HEAD = lookup.findVarHandle(SpscRingBuffer.class, "head", long.class);
        } catch (ReflectiveOperationException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    /**
     * Creates a new SPSC ring buffer with the given capacity.
     *
     * @param capacity the fixed capacity; must be a positive power of two
     * @throws IllegalArgumentException if {@code capacity} is not a positive power of two
     */
    @SuppressWarnings("unchecked")
    public SpscRingBuffer(int capacity) {
        if (capacity <= 0 || (capacity & (capacity - 1)) != 0) {
            throw new IllegalArgumentException(
                    "capacity must be a positive power of two, got: " + capacity);
        }
        this.capacity = capacity;
        this.mask = capacity - 1;
        this.buffer = (T[]) new Object[capacity];
    }

    /**
     * Inserts an element at the tail of the buffer.
     *
     * <p><b>Must be called from the producer thread only.</b></p>
     *
     * <p>If the buffer is full (all slots occupied), this method returns {@code false}
     * without blocking. Otherwise the element is stored and the method returns {@code true}.
     *
     * @param value the element to insert; must not be {@code null}
     * @return {@code true} if the element was added, {@code false} if the buffer is full
     * @throws IllegalArgumentException if {@code value} is {@code null}
     */
    public boolean offer(T value) {
        if (value == null) {
            throw new IllegalArgumentException("null elements are not permitted");
        }

        long currentTail = tail;            // plain read — only this thread writes tail
        long nextTail = currentTail + 1;

        // Check if buffer appears full using our cached copy of head.
        if (nextTail - cachedHead > capacity) {
            // Refresh cachedHead from the consumer's actual head.
            cachedHead = (long) HEAD.getAcquire(this);
            if (nextTail - cachedHead > capacity) {
                return false;               // genuinely full
            }
        }

        buffer[(int) (currentTail & mask)] = value;

        // ─── LINEARIZATION POINT ────────────────────────────────────────
        // Release-store ensures the buffer write above is visible to the
        // consumer before it sees the updated tail.
        // ─────────────────────────────────────────────────────────────────
        TAIL.setRelease(this, nextTail);
        return true;
    }

    /**
     * Removes and returns the element at the head of the buffer.
     *
     * <p><b>Must be called from the consumer thread only.</b></p>
     *
     * <p>If the buffer is empty (no elements available), this method returns {@code null}
     * without blocking. Otherwise the head element is removed and returned.
     *
     * @return the head element, or {@code null} if the buffer is empty
     */
    public T poll() {
        long currentHead = head;            // plain read — only this thread writes head

        // Check if buffer appears empty using our cached copy of tail.
        if (currentHead == cachedTail) {
            // Refresh cachedTail from the producer's actual tail.
            cachedTail = (long) TAIL.getAcquire(this);
            if (currentHead == cachedTail) {
                return null;                // genuinely empty
            }
        }

        int slot = (int) (currentHead & mask);
        T value = buffer[slot];
        buffer[slot] = null;                // null out for GC

        // ─── LINEARIZATION POINT ────────────────────────────────────────
        // Release-store ensures the buffer read and null-out above are
        // visible (slot freed) before the producer sees the updated head.
        // ─────────────────────────────────────────────────────────────────
        HEAD.setRelease(this, currentHead + 1);
        return value;
    }

    /**
     * Returns the element at the head of the buffer without removing it.
     *
     * <p>This is a <b>non-linearizable</b> snapshot. By the time this method returns,
     * the element may have been consumed by the consumer thread. Use for monitoring
     * and debugging, not for correctness-critical decisions.</p>
     *
     * @return the head element, or {@code null} if the buffer appears empty
     */
    public T peek() {
        long currentHead = (long) HEAD.getAcquire(this);
        long currentTail = (long) TAIL.getAcquire(this);
        if (currentHead == currentTail) {
            return null;
        }
        return buffer[(int) (currentHead & mask)];
    }

    /**
     * Returns an approximate number of elements in the buffer.
     *
     * <p>Because {@code tail} and {@code head} are read independently (not atomically
     * together), the result is a <b>point-in-time snapshot</b> that may be stale.
     * The value is clamped to {@code [0, capacity]}.
     *
     * @return approximate element count
     */
    public int size() {
        long currentTail = (long) TAIL.getAcquire(this);
        long currentHead = (long) HEAD.getAcquire(this);
        return (int) Math.max(0, Math.min(currentTail - currentHead, capacity));
    }

    /**
     * Returns the fixed capacity of the buffer.
     *
     * @return the capacity (always a power of two)
     */
    public int capacity() {
        return capacity;
    }

    /**
     * Returns {@code true} if the buffer appears to contain no elements.
     *
     * <p>Point-in-time snapshot; may be stale by the time the caller acts on it.
     *
     * @return {@code true} if the buffer appears empty
     */
    public boolean isEmpty() {
        return (long) TAIL.getAcquire(this) == (long) HEAD.getAcquire(this);
    }

    /**
     * Returns {@code true} if the buffer appears to be full.
     *
     * <p>Point-in-time snapshot; may be stale by the time the caller acts on it.
     *
     * @return {@code true} if the buffer appears full
     */
    public boolean isFull() {
        return (long) TAIL.getAcquire(this) - (long) HEAD.getAcquire(this) >= capacity;
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder("SpscRingBuffer[");
        long h = (long) HEAD.getAcquire(this);
        long t = (long) TAIL.getAcquire(this);
        boolean first = true;
        int limit = 20;
        for (long i = h; i < t && limit-- > 0; i++) {
            if (!first) sb.append(", ");
            sb.append(buffer[(int) (i & mask)]);
            first = false;
        }
        if (t - h > 20) sb.append(", ...");
        sb.append(']');
        return sb.toString();
    }
}
