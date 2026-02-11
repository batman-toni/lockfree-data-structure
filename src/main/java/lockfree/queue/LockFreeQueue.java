package lockfree.queue;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * A lock-free FIFO queue based on the Michael-Scott algorithm (1996).
 *
 * <h2>Algorithm Overview</h2>
 * <p>The queue is a singly-linked list with two atomic pointers: {@code head} and {@code tail}.
 * A permanent <em>sentinel</em> (dummy) node separates the head pointer from actual data —
 * the first real element is always {@code head.next}. This sentinel trick eliminates the
 * special case of an empty queue and decouples enqueue from dequeue so they can operate on
 * different ends of the list concurrently without interfering with each other.
 *
 * <h2>Enqueue (two-phase commit)</h2>
 * <ol>
 *   <li><b>Link:</b> CAS the current tail node's {@code next} from {@code null} to the new node.
 *       This is the <em>linearization point</em> — the element is now logically in the queue.</li>
 *   <li><b>Swing:</b> CAS {@code tail} forward to the new node. If this fails, it means another
 *       thread already helped swing the tail (see "helping" below), which is fine.</li>
 * </ol>
 *
 * <h2>Dequeue</h2>
 * <ol>
 *   <li>Read {@code head} and its {@code next}. If {@code next == null}, the queue is empty.</li>
 *   <li>If the tail is lagging (tail == head but next != null), help swing the tail forward
 *       before retrying — this prevents the tail from falling behind.</li>
 *   <li>CAS {@code head} from the current sentinel to {@code next}. On success, the old
 *       sentinel is unlinked and the value from {@code next} is returned. The {@code next}
 *       node becomes the new sentinel. This CAS is the <em>linearization point</em>.</li>
 * </ol>
 *
 * <h2>Helping Mechanism</h2>
 * <p>A critical feature of the Michael-Scott queue is <em>cooperative helping</em>. After an
 * enqueuer links a new node (phase 1), it may be preempted before swinging the tail (phase 2).
 * Both enqueue and dequeue detect a lagging tail ({@code tail.next != null}) and attempt to
 * swing it forward. This ensures system-wide progress: even if the original enqueuer stalls,
 * other threads complete its work. This is what makes the algorithm <b>lock-free</b> rather
 * than merely obstruction-free.</p>
 *
 * <h2>ABA Problem</h2>
 * <p>The ABA problem occurs when a CAS target transitions A→B→A, making a stale CAS succeed
 * incorrectly. In this implementation, ABA is <b>not a concern</b> because:</p>
 * <ul>
 *   <li>Every enqueue allocates a <em>fresh</em> {@link Node} — object identity is unique.</li>
 *   <li>Java's garbage collector will not reclaim (and therefore cannot reuse the memory of)
 *       any node that a thread still references. A node's identity cannot be recycled while
 *       any thread holds a snapshot of it.</li>
 *   <li>The dequeue operation replaces the sentinel, but the old sentinel becomes unreachable
 *       only after all threads have moved past it.</li>
 * </ul>
 * <p>In languages without GC (C, C++) where nodes might be freed and reallocated at the same
 * address, hazard pointers or epoch-based reclamation would be required.</p>
 *
 * <h2>Memory Ordering</h2>
 * <p>{@link AtomicReference} operations in Java provide the following guarantees:</p>
 * <ul>
 *   <li>{@code get()} — volatile read (acquire semantics). All writes by the thread that
 *       performed the most recent volatile write to this variable are visible.</li>
 *   <li>{@code set()} — volatile write (release semantics). All prior writes by this thread
 *       are visible to any thread that subsequently reads this variable.</li>
 *   <li>{@code compareAndSet()} — full volatile read+write barrier. It atomically reads and
 *       conditionally writes, establishing a happens-before edge from the writing thread to
 *       any thread that later observes the written value.</li>
 *   <li>{@code lazySet()} — release-only store (used in {@link Node} initialization via
 *       constructor). Guarantees that the node's fields are visible before the reference
 *       is published, but does not prevent subsequent reads from being reordered before it.</li>
 * </ul>
 * <p>Because all CAS operations on {@code head}, {@code tail}, and {@code Node.next} use
 * {@code compareAndSet} (full fence), the algorithm has sequentially consistent memory
 * ordering at every critical step.</p>
 *
 * @param <T> the element type; {@code null} elements are not permitted
 * @see <a href="https://doi.org/10.1145/248052.248106">
 *      Michael &amp; Scott, "Simple, Fast, and Practical Non-Blocking and Blocking
 *      Concurrent Queue Algorithms", PODC 1996</a>
 */
public class LockFreeQueue<T> {

    /**
     * A node in the queue's linked list.
     *
     * <p>The {@code value} field is effectively final after construction — it is written once
     * in the constructor and never modified. The {@code next} pointer is an {@link AtomicReference}
     * because it must be CAS-updated by the enqueue operation (from {@code null} to the new node).
     *
     * <p>The sentinel (dummy) node has {@code value == null}; all other nodes have non-null values.
     *
     * @param <T> element type
     */
    static final class Node<T> {

        /** The element stored in this node. {@code null} only for the sentinel/dummy node. */
        final T value;

        /**
         * Pointer to the next node. Updated atomically via CAS during enqueue.
         * Initialized to {@code null} (this node is the last in the list).
         */
        final AtomicReference<Node<T>> next;

        /** Creates a sentinel (dummy) node with no value. */
        Node() {
            this.value = null;
            this.next = new AtomicReference<>(null);
        }

        /** Creates a data node carrying the given non-null value. */
        Node(T value) {
            this.value = value;
            this.next = new AtomicReference<>(null);
        }
    }

    /**
     * Points to the sentinel (dummy) node. The first real element is {@code head.get().next}.
     *
     * <p>Updated by dequeue: CAS swings head from the current sentinel to its successor,
     * making the successor the new sentinel.
     */
    private final AtomicReference<Node<T>> head;

    /**
     * Points to the last (or near-last) node in the list.
     *
     * <p>Updated by enqueue (phase 2) or by helping threads that detect a lagging tail.
     * Invariant: {@code tail} is always at or behind the true last node — never ahead of it.
     */
    private final AtomicReference<Node<T>> tail;

    /**
     * Approximate element count.
     *
     * <p>Incremented after a successful enqueue CAS, decremented after a successful dequeue CAS.
     * Because these counter updates are not atomic with the structural CAS, the count may
     * transiently disagree with the true number of elements. See {@link #size()} for details.
     */
    private final AtomicInteger size;

    /**
     * Creates an empty lock-free queue.
     * Initializes the sentinel node and points both head and tail to it.
     */
    public LockFreeQueue() {
        Node<T> sentinel = new Node<>();    // dummy node
        this.head = new AtomicReference<>(sentinel);
        this.tail = new AtomicReference<>(sentinel);
        this.size = new AtomicInteger(0);
    }

    /**
     * Inserts an element at the tail of the queue.
     *
     * <p>This is the Michael-Scott two-phase enqueue:
     * <ol>
     *   <li>CAS {@code tail.next} from {@code null} → {@code newNode}
     *       (<b>linearization point</b>: the element is now reachable from the list).</li>
     *   <li>CAS {@code tail} from the old tail to {@code newNode}
     *       (best-effort swing; if it fails, a helper will complete it).</li>
     * </ol>
     *
     * @param value the element to insert; must not be {@code null}
     * @throws IllegalArgumentException if {@code value} is {@code null}
     */
    public void enqueue(T value) {
        if (value == null) {
            throw new IllegalArgumentException("null elements are not permitted");
        }

        Node<T> newNode = new Node<>(value);

        while (true) {
            Node<T> curTail = tail.get();               // T1: read tail
            Node<T> tailNext = curTail.next.get();       // T2: read tail.next

            // Verify tail hasn't moved since T1 (consistency check).
            if (curTail != tail.get()) {
                continue;                                // tail moved; re-read
            }

            if (tailNext == null) {
                // Tail is genuinely the last node. Attempt phase 1: link new node.
                // ---------------------------------------------------------------
                // LINEARIZATION POINT (on success): the new node becomes reachable
                // from the linked list. Any subsequent traversal from head will
                // eventually reach it.
                // ---------------------------------------------------------------
                if (curTail.next.compareAndSet(null, newNode)) {
                    // Phase 2: try to swing tail forward. If this CAS fails, another
                    // thread (enqueuer or dequeuer) has already helped swing it — safe
                    // to ignore the failure.
                    tail.compareAndSet(curTail, newNode);
                    size.incrementAndGet();
                    return;
                }
                // CAS failed → another enqueuer linked its node first; retry.
            } else {
                // Tail is lagging: tail.next is non-null, meaning an enqueue linked a
                // node but hasn't yet swung the tail pointer.
                // HELPING: swing tail forward so the lagging enqueuer (and everyone else)
                // can make progress. This is the key to lock-freedom.
                tail.compareAndSet(curTail, tailNext);
                // Whether or not this CAS succeeds, loop back and retry with updated tail.
            }
        }
    }

    /**
     * Removes and returns the element at the head of the queue.
     *
     * <p>Steps:
     * <ol>
     *   <li>Read head (sentinel), tail, and head.next.</li>
     *   <li>If head.next is null, the queue is empty — return null
     *       (linearization point: the read that observed null).</li>
     *   <li>If head == tail and tail is lagging, help swing the tail forward.</li>
     *   <li>CAS head from sentinel → sentinel.next
     *       (<b>linearization point</b>: the old first element is no longer reachable
     *       via head, so it is logically removed). The successor becomes the new sentinel.</li>
     * </ol>
     *
     * @return the head element, or {@code null} if the queue is empty
     */
    public T dequeue() {
        while (true) {
            Node<T> curHead = head.get();                // H1: read head (sentinel)
            Node<T> curTail = tail.get();                // H2: read tail
            Node<T> headNext = curHead.next.get();       // H3: read head.next (first real element)

            // Consistency check: has head moved since H1?
            if (curHead != head.get()) {
                continue;                                // stale snapshot; retry
            }

            if (headNext == null) {
                // -----------------------------------------------------------------
                // LINEARIZATION POINT (empty queue): head.next is null, so there are
                // no data nodes in the list.
                // -----------------------------------------------------------------
                return null;
            }

            if (curHead == curTail) {
                // Head and tail point to the same sentinel, but head.next is non-null.
                // This means an enqueue linked a node but hasn't swung the tail yet.
                // HELPING: swing the tail forward before we attempt to dequeue, so the
                // tail invariant is maintained.
                tail.compareAndSet(curTail, headNext);
                continue;                                // retry with updated pointers
            }

            // Read the value *before* CAS. After a successful CAS, headNext becomes
            // the new sentinel and another thread's dequeue may immediately overwrite
            // its reachability — so we must capture the value first.
            T value = headNext.value;

            // -----------------------------------------------------------------
            // LINEARIZATION POINT (successful dequeue): CAS swings head from the
            // old sentinel to headNext. headNext becomes the new sentinel; its
            // value has already been captured above.
            // -----------------------------------------------------------------
            if (head.compareAndSet(curHead, headNext)) {
                size.decrementAndGet();
                return value;
            }
            // CAS failed → another dequeuer won; retry.
        }
    }

    /**
     * Returns the element at the head of the queue without removing it.
     *
     * <p>This is a <b>non-linearizable</b> snapshot. By the time this method returns, the
     * head may have been dequeued by another thread. Use this for monitoring and debugging,
     * not for correctness-critical decisions.
     *
     * @return the head element, or {@code null} if the queue appears empty
     */
    public T peek() {
        Node<T> curHead = head.get();
        Node<T> firstNode = curHead.next.get();
        return (firstNode != null) ? firstNode.value : null;
    }

    /**
     * Returns {@code true} if the queue appears to contain no elements.
     *
     * <p>Like {@link #peek()}, this is a <b>point-in-time snapshot</b> that may be
     * stale by the time the caller acts on it.
     *
     * @return {@code true} if the queue appears empty
     */
    public boolean isEmpty() {
        return head.get().next.get() == null;
    }

    /**
     * Returns an approximate number of elements in the queue.
     *
     * <p>The counter is updated <em>after</em> each successful structural CAS, so it is
     * <b>not linearizable</b> with respect to concurrent enqueue/dequeue operations.
     * The returned value may transiently be negative (if a dequeue's decrement is observed
     * before the corresponding enqueue's increment was visible); we clamp to zero.
     *
     * <p>This is suitable for monitoring, logging, and heuristic decisions (e.g., back-pressure).
     * Do not use it for correctness-critical control flow.
     *
     * @return a non-negative approximate size
     */
    public int size() {
        return Math.max(size.get(), 0);
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder("LockFreeQueue[");
        Node<T> cursor = head.get().next.get();
        boolean first = true;
        int limit = 20;  // avoid traversing an enormous queue
        while (cursor != null && limit-- > 0) {
            if (!first) sb.append(", ");
            sb.append(cursor.value);
            cursor = cursor.next.get();
            first = false;
        }
        if (cursor != null) sb.append(", ...");
        sb.append(']');
        return sb.toString();
    }
}
