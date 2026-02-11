# Lock-Free Data Structures

High-performance, lock-free concurrent data structures implemented in Java 21 with no external dependencies.

## Data Structures

### Treiber Stack (`lockfree.stack`)
A classic lock-free LIFO stack using CAS (compare-and-swap) on a singly-linked list.

- **Concurrency:** Multi-producer, multi-consumer (MPMC)
- **Algorithm:** Treiber (1986) — CAS retry loop on the head pointer
- **Bounded:** No (grows with allocation)
- **Includes:** ABA-resistant variant using `AtomicStampedReference`

### Michael-Scott Queue (`lockfree.queue`)
A lock-free FIFO queue using a sentinel node and two-phase CAS enqueue with cooperative helping.

- **Concurrency:** Multi-producer, multi-consumer (MPMC)
- **Algorithm:** Michael & Scott (1996) — CAS on head/tail with helping mechanism
- **Bounded:** No (grows with allocation)
- **Lock-freedom:** Guaranteed — lagging threads are helped by others

### SPSC Ring Buffer (`lockfree.ringbuffer`)
A bounded, zero-allocation ring buffer optimized for exactly one producer and one consumer thread.

- **Concurrency:** Single-producer, single-consumer (SPSC) only
- **Synchronization:** `VarHandle` acquire/release — **no CAS needed**
- **Bounded:** Yes (fixed power-of-two capacity)
- **Allocation:** Zero in the hot path
- **Optimizations:** Cache-line padding to prevent false sharing, cached peer indices to minimize cross-core reads

## Performance Comparison (SPSC mode, 1 producer + 1 consumer)

| Implementation | Typical Throughput | Relative |
|---|---|---|
| **SPSC Ring Buffer** | ~95–185M ops/sec | **baseline** |
| ConcurrentLinkedQueue (JDK) | ~113M ops/sec | ~1x |
| ArrayBlockingQueue (JDK) | ~40M ops/sec | ~0.4x |
| LockFreeQueue (ours) | ~27M ops/sec | ~0.3x |
| LinkedBlockingQueue (JDK) | ~26M ops/sec | ~0.3x |

*Results vary by hardware, JIT warmup, and OS. Run the benchmarks on your own machine for accurate numbers.*

## Requirements

- Java 21+
- Maven 3.8+

## Build & Test

```bash
# Run all tests (23 tests across 3 data structures)
mvn clean test

# Run just the ring buffer tests
mvn test -Dtest="lockfree.ringbuffer.SpscRingBufferTest"
```

## Run Benchmarks

```bash
# SPSC Ring Buffer — correctness + throughput comparison
mvn -q exec:java -Dexec.mainClass="lockfree.ringbuffer.SpscRingBufferDemo"

# Lock-Free Queue — MPMC stress test + throughput comparison
mvn -q exec:java -Dexec.mainClass="lockfree.queue.LockFreeQueueDemo"

# Treiber Stack — MPMC stress test
mvn -q exec:java -Dexec.mainClass="lockfree.stack.LockFreeStack"
```

## Project Structure

```
src/
├── main/java/lockfree/
│   ├── queue/
│   │   ├── LockFreeQueue.java          # Michael-Scott queue
│   │   └── LockFreeQueueDemo.java      # MPMC benchmark
│   ├── ringbuffer/
│   │   ├── SpscRingBuffer.java         # SPSC ring buffer
│   │   └── SpscRingBufferDemo.java     # SPSC benchmark
│   └── stack/
│       └── LockFreeStack.java          # Treiber stack + demo
└── test/java/lockfree/
    ├── queue/
    │   └── LockFreeQueueTest.java      # 8 tests
    ├── ringbuffer/
    │   └── SpscRingBufferTest.java     # 9 tests
    └── stack/
        └── LockFreeStackTest.java      # 6 tests
```

## Key Concepts

| Concept | Where Used | Why |
|---|---|---|
| **CAS retry loops** | Stack, Queue | Multiple writers race; CAS resolves conflicts without locks |
| **Acquire/Release ordering** | Ring Buffer | Single writer per index — no CAS needed, just memory visibility |
| **Cache-line padding** | Ring Buffer | Prevents false sharing between producer and consumer cores |
| **Sentinel/dummy node** | Queue | Decouples head and tail so enqueue/dequeue don't interfere |
| **Cooperative helping** | Queue | Threads complete each other's work — guarantees lock-freedom |
| **Cached peer indices** | Ring Buffer | Amortizes cross-core volatile reads from O(1) per op to O(1/capacity) |

## References

- Treiber, R.K. (1986). *Systems Programming: Coping with Parallelism*. IBM Research Report RJ 5118.
- Michael, M.M. & Scott, M.L. (1996). [Simple, Fast, and Practical Non-Blocking and Blocking Concurrent Queue Algorithms](https://doi.org/10.1145/248052.248106). PODC '96.
- Lamport, L. (1983). *Specifying Concurrent Program Modules*. ACM TOPLAS — foundation for the SPSC ring buffer protocol.
