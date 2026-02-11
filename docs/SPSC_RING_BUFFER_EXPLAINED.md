# How the SPSC Ring Buffer Works

## The Core Idea

A fixed-size array acts as a circular buffer with two counters:
- **`tail`** — where the producer writes next (only the producer touches this)
- **`head`** — where the consumer reads next (only the consumer touches this)

```
        head=2         tail=5
          |              |
  [ _ ] [ _ ] [ C ] [ D ] [ E ] [ _ ] [ _ ] [ _ ]
    0     1     2     3     4     5     6     7

  capacity=8, mask=7
  slot = index & mask  (fast modulo for power-of-two sizes)
```

## Why No CAS?

This is the key insight. In MPMC queues (like Michael-Scott), multiple threads race to update the same pointer, so you need CAS to resolve conflicts. In SPSC:

- Only **one thread** ever writes `tail` (the producer)
- Only **one thread** ever writes `head` (the consumer)

No race = no CAS. You just need to ensure the *other* thread can **see** your writes. That's a pure memory visibility problem, solved with acquire/release ordering.

## Offer (Producer)

```
1. Read tail (plain read — I'm the only writer)
2. Is there space?  →  check: tail - cachedHead < capacity
   If appears full → refresh cachedHead from actual head (acquire read)
   If still full   → return false
3. buffer[tail & mask] = value      ← plain store into array
4. TAIL.setRelease(tail + 1)        ← LINEARIZATION POINT
```

The **release store** on step 4 is critical: it guarantees that the array write in step 3 is visible to the consumer **before** the consumer sees the updated tail. Without this, the consumer could read `tail=5`, go to `buffer[4]`, and see stale/null data.

## Poll (Consumer)

```
1. Read head (plain read — I'm the only writer)
2. Is there data?  →  check: head != cachedTail
   If appears empty → refresh cachedTail from actual tail (acquire read)
   If still empty   → return null
3. value = buffer[head & mask]      ← plain load from array
   buffer[head & mask] = null       ← clear for GC
4. HEAD.setRelease(head + 1)        ← LINEARIZATION POINT
```

The **release store** on step 4 ensures the producer sees the freed slot only **after** the consumer has finished reading from it.

## The Acquire/Release Contract

```
Producer                          Consumer
────────                          ────────
buffer[4] = "hello"
TAIL.setRelease(5)    ──────→    cachedTail = TAIL.getAcquire()  // sees 5
         release fence              acquire fence
         (flush writes)             (sees all writes before release)
                                  value = buffer[4]  // guaranteed to see "hello"
```

This is weaker than `volatile` (which adds sequential consistency) but sufficient for SPSC and allows better CPU pipelining.

## Cached Peer Indices

Without caching, every `offer()` would need an acquire-read of `head` (cross-core round trip ~40-80ns). Instead:

```
Producer keeps: cachedHead (local copy of head)
Consumer keeps: cachedTail (local copy of tail)
```

The producer only refreshes `cachedHead` when the buffer **appears full**. If the buffer has 64K slots, that cross-core read happens once per ~64K operations — amortized to nearly zero.

## Cache-Line Padding

On modern CPUs, cache lines are 64 bytes. If `head` and `tail` sit on the same cache line:

```
Without padding:
  Core 0 (producer) writes tail → invalidates cache line on Core 1
  Core 1 (consumer) writes head → invalidates cache line on Core 0
  = constant bouncing, ~100M ops/sec → ~10M ops/sec
```

The fix is 64 bytes of padding between them:

```java
long p00, p01, p02, p03, p04, p05, p06, p07;  // 64 bytes
volatile long tail;
long p10, p11, p12, p13, p14, p15, p16, p17;  // 64 bytes
volatile long head;
long p20, p21, p22, p23, p24, p25, p26, p27;  // 64 bytes
```

Each index gets its own cache line. No false sharing.

## Why It's So Fast

| Factor | MPMC Queue | SPSC Ring Buffer |
|---|---|---|
| Synchronization | CAS retry loop (can fail + retry) | Single store (never fails) |
| Memory ordering | Full fence (sequential consistency) | Acquire/release (weaker, faster) |
| Allocation | New node per enqueue | Zero — reuses array slots |
| Cache behavior | Pointer chasing (linked list) | Sequential array access (prefetcher-friendly) |
| Cross-core reads | Every operation | Once per ~capacity operations |

The result: **~100-200M ops/sec** vs ~2-30M for CAS-based or lock-based queues.

## References

- Lamport, L. (1983). *Specifying Concurrent Program Modules*. ACM TOPLAS — foundation for the SPSC ring buffer protocol.
- LMAX Disruptor — high-performance inter-thread messaging library using ring buffers.
- JCTools — Java Concurrency Tools library with optimized SPSC queues.
