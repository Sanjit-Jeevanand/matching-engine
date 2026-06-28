# Build Plan — 10 Phases (Low-Latency Order Matching Engine in Java 21)

Each phase: **teach → build to standard → break it (as a benchmark regression) → understand → ADR + measured bullet.**
Every phase must clear the **production bar** in `BRIEF.md` (tests/PIT, Checkstyle/SpotBugs, OWASP,
observability, reliability, operability, delivery) before it counts as done. Don't skip the "break it"
step, and don't skip turning that failure into a regression threshold in CI.

**Core (the CV-defining work):** Phases 0–6 (foundations → order book → matching → full order types →
correctness simulator → Disruptor → object pooling). **Elevation to "exceptional":** Phases 7–9
(false sharing → off-heap serialization → measured numbers + profiling + writeup). If short on time,
ship 0–6, then 9 (numbers) — the correctness and optimization story is the signal.

---

## Phase 0 — Java foundations & quality gates
*Goal: a repo where it is impossible to merge broken, unvetted, or slow-by-regression Java.*

1. Gradle project, sensible layout:
   `src/main/java/com/exchange/matching/{book,model,disruptor,pool,bench,sim}` +
   `src/test/java/...` + `src/jmh/java/...` (JMH source set via the Gradle JMH plugin).
2. Wire Checkstyle (Google Java Style), SpotBugs (with `findsecbugs` plugin), OWASP Dependency-Check,
   JaCoCo coverage, and PIT mutation testing — all as Gradle tasks runnable via `./gradlew check`.
3. GitHub Actions CI: `test → checkstyle → spotbugs → dependencyCheckAnalyze → pitest → build`,
   gated on green. Archive JaCoCo and PIT reports as CI artifacts.
4. Structured JSON logging (SLF4J + Logback JSON encoder) with a `correlation_id` / `order_id` field
   from line one. Logging must be async (AsyncAppender) — synchronous log I/O in the hot path kills latency.
5. **Break it:** open a PR with a SpotBugs null-dereference violation and an OWASP-flagged dependency
   (a pinned old version of a library with a known CVE) → watch CI block the merge.
- **Learn:** Gradle build lifecycle (compile → processResources → test → check → build), why mutation
  testing (PIT) is stronger than line coverage % (coverage = executed, mutation score = actually
  tested the logic), OWASP Dependency-Check (CVSS scoring, transitive dependency risk), why
  synchronous logging in a latency-sensitive path is the silent latency killer, CI as enforcement.
- **Bullet:** "Set up a quality-gated Java CI pipeline (Checkstyle/SpotBugs/PIT/OWASP Dependency-Check)
  via Gradle + GitHub Actions; async JSON logging with correlation propagation from line one."

---

## Phase 1 — Order model & price-level data structure
*Goal: the core value types and the data structure that represents one side of the book, tested in isolation.*

1. `Order` record: `orderId` (long), `symbol` (String), `side` (enum BUY/SELL), `type`
   (enum LIMIT/MARKET/CANCEL/IOC/FOK), `price` (long, fixed-point in integer ticks — never `double`),
   `quantity` (long), `remainingQty` (long), `timestamp` (long, nanoseconds), `status`
   (enum OPEN/PARTIALLY_FILLED/FILLED/CANCELLED).
2. `PriceLevel`: a `Deque<Order>` of all orders resting at a single price — arrival order within the
   deque is time priority; FIFO dequeue preserves it.
3. `OrderBook`: two `TreeMap<Long, PriceLevel>` — bids (descending comparator: highest price first,
   so `firstKey()` is the best bid) and asks (natural ascending: `firstKey()` is the best ask).
   Explain *why* `TreeMap` gives O(log n) insert and O(1) best-price access via `firstKey()`/`firstEntry()`.
4. Unit tests: insert 100 orders across 10 price levels; assert `TreeMap` iteration order; assert
   Deque FIFO order within each level; assert `firstKey()` returns best price on each side.
5. **Break it:** replace the bid `TreeMap` with a natural ascending comparator → `firstKey()` returns
   the *worst* bid; the existing test catches it and fails; pin the comparator choice as a regression.
- **Learn:** price-time priority (the two-dimensional sort every exchange uses), why bids are a max
  structure and asks are a min structure, **why floating-point is banned in financial systems**
  (IEEE 754 rounding: `0.1 + 0.2 ≠ 0.3`; fixed-point tick arithmetic is exact), Java records
  (immutable, structural equality), `Deque` vs `Queue` (Deque supports O(1) removal from middle
  via iterator, needed for cancel).
- **Bullet:** (reserved for Phase 2 — the model alone doesn't earn a CV metric; matching does)

---

## Phase 2 — Limit order matching (the core algorithm)
*Goal: two orders cross → a fill is produced; the book state is always consistent.*

1. Matching loop: for an incoming buy, compare its price against `asks.firstKey()` — if buy price ≥
   best ask, they cross. Fill `min(buy.remainingQty, ask.remainingQty)` at the **ask's (maker's)
   price**. Advance to the next ask level if the buy is not yet fully filled. Mirror for sells.
2. `Fill` record: `(buyOrderId, sellOrderId, price, quantity, timestamp)` — emitted for every partial
   or full match, never mutated after creation.
3. Book state after a fill: full fill → remove from Deque; empty Deque → remove the PriceLevel from
   the TreeMap (critical: stale empty levels corrupt `firstKey()`). Partial fill → update
   `remainingQty` in place.
4. Resting orders: a limit buy at 100 ticks where no ask ≤ 100 rests at its price level in the bid
   book — no fill emitted.
5. Unit tests: (a) crossing orders fill at maker's price; (b) partial fill leaves correct remainder
   in book; (c) non-crossing limit rests correctly; (d) matching across multiple price levels (a large
   buy sweeps three ask levels, generates three fills).
6. **Break it:** fill at the *taker's* price instead of the maker's → the unit test for fill price
   fails; pin as a regression to lock in the maker-price rule forever.
- **Learn:** maker vs taker (maker provides liquidity by resting, taker takes it by aggressing),
  why fills execute at the **maker's price** (the maker set the price they were willing to trade at;
  the taker agreed to it), the matching loop invariant (process one price level at a time, never allow
  bid/ask to cross after the loop exits), why empty levels must be purged (memory leak + broken
  `firstKey()`), the concept of an "order book spread" (best ask − best bid).
- **Bullet:** "Built a price-time priority limit order book in Java; matching loop fills at maker's
  price across multiple levels, verified by 60+ unit tests covering all fill/partial/rest/sweep cases."

---

## Phase 3 — Full order type support + edge cases
*Goal: market, cancel, IOC, FOK — each with exhaustive edge-case tests and clean error types.*

1. **Market order:** aggress at any price (ignore the price field) until fully filled or book is
   empty; if the book runs dry mid-fill, emit fills for the matched portion and return a typed
   `INSUFFICIENT_LIQUIDITY` result for the remainder — never silently lose quantity.
2. **Cancel order:** maintain a `HashMap<Long, Order>` side-table (orderId → Order) for O(1) lookup;
   remove from its Deque, clean up empty PriceLevels, mark status CANCELLED. Return a typed
   `UNKNOWN_ORDER_ID` error for unknown IDs — no silent no-op.
3. **IOC (Immediate-Or-Cancel):** run the matching loop; whatever didn't fill immediately is cancelled —
   never rests in the book.
4. **FOK (Fill-Or-Kill):** *before* entering the matching loop, check whether sufficient liquidity
   exists to fully fill the order (scan ask levels without mutating them); if not, reject the whole
   order with zero fills emitted. Only enter the loop if full fill is guaranteed.
5. Unit tests: market order sweeps the book then returns liquidity error; cancel removes from correct
   level; cancel of unknown ID returns typed error; IOC partial match then cancel; FOK rejects when
   insufficient, fills fully when sufficient.
6. **Break it:** implement FOK *without* the pre-check (attempt to match, then rollback) → a naive
   partial-fill-then-cancel slips out; the unit test for FOK zero-fills catches it.
- **Learn:** why exchanges need IOC/FOK (algorithmic traders need atomicity guarantees — a partial
  fill can leave a position risk they can't hedge), the **self-trade prevention** problem (two orders
  from the same party crossing — real exchanges filter this; note it as a future extension), why
  cancel needs O(1) lookup (a single cancel in a 10M-order book must be µs, not O(n)), why empty
  PriceLevel cleanup is a correctness issue not just a memory issue.
- **Bullet:** "Implemented limit/market/cancel/IOC/FOK order types with typed error results; cancel
  O(1) via HashMap side-table; zero silent quantity leaks across all order type combinations."

---

## Phase 4 — Property-based correctness verification (prove it, don't assert it)
*Goal: stop verifying correctness by hand-crafted examples; mechanically prove invariants under adversarial sequences.*

1. A seeded, deterministic order sequence generator (jqwik `@Provide` + `Arbitrary`): generates
   interleaved limit buys, limit sells, market orders, cancels, IOC, and FOK at random prices,
   quantities, and timings — but reproducible from a seed.
2. Express invariants as jqwik `@Property` tests:
   (a) bid/ask never cross after any matching step (spread ≥ 0 or book side is empty),
   (b) sum of fills per match = `min(buy qty, sell qty)` — no quantity leak,
   (c) every order in the book has `status = OPEN` or `PARTIALLY_FILLED` (no filled/cancelled orders linger),
   (d) no `orderId` appears twice in the book simultaneously,
   (e) after a cancel, the `orderId` is absent from both book and side-table.
3. **Shrinking:** when a property fails, jqwik minimizes the sequence to the smallest reproducing
   interleaving; capture each found bug as a permanent named regression test with the shrunk seed.
4. Run 50,000+ seeds per CI run as a required quality gate.
5. **Break it:** deliberately introduce an off-by-one on `remainingQty` update (subtract one too few)
   → jqwik finds it and shrinks to a 3-order reproducer; pin that reproducer as a named regression test.
- **Learn:** property-based testing vs example-based testing (examples test specific cases; properties
  test specifications — "for ALL inputs, X holds"), jqwik shrinking (the found counterexample is often
  large; shrinking finds the *minimal* one, making the bug obvious), **invariant thinking** (specifying
  WHAT must hold, not HOW it holds — this is how you design a system, not just test one), why
  randomized testing finds the bugs you didn't think to write examples for.
- **Bullet:** "Property-tested the matching engine with jqwik across 50,000+ randomized order sequences;
  shrinking reduced a found quantity-leak bug to a 3-order minimal reproducer, now a permanent CI gate."

---

## Phase 5 — LMAX Disruptor ring buffer (lock-free order intake)
*Goal: replace synchronous order submission with a lock-free ring buffer — zero contention between producer and consumer.*

1. **Teach the Disruptor:** a fixed-size, pre-allocated ring buffer where each slot holds an
   `OrderEvent` (mutable, reused across turns); a `Sequence` counter per producer and consumer;
   a `SequenceBarrier` that the consumer spins/waits on instead of acquiring a lock. The
   **single-writer principle** means only one thread ever writes to a given slot — no CAS loop,
   no lock, no cache-line ping-pong on the slot itself.
2. Define `OrderEvent` (mutable fields filled by producer): `orderId`, `side`, `type`, `price`,
   `quantity`, `timestamp`. The ring buffer is sized as a power of two (use bit-masking for modulo,
   not division). Pre-allocate all `OrderEvent` instances at startup.
3. Wire an `EventHandler<OrderEvent>` that reads the event and calls the matching engine; the handler
   runs in a dedicated pinned thread (one CPU core, one handler). Choose `YieldingWaitStrategy` for
   low-latency (spins then yields) and document the CPU-cost trade-off vs `BlockingWaitStrategy`.
4. Integration test: publish 1 M orders with a fixed random seed; verify all fills match the output
   of the equivalent single-threaded engine run with the same seed (deterministic correctness).
5. **Break it:** replace the Disruptor with `LinkedBlockingQueue` → run both under JMH throughput
   benchmark; the queue version shows lock contention (measure with async-profiler) and lower
   throughput; pin the Disruptor throughput number as the regression floor.
- **Learn:** why lock-free ≠ wait-free (Disruptor spins on the sequence barrier; it's lock-free but
  not wait-free), the **single-writer principle** (only one thread writes each slot → no CAS → no
  contention on the data), how the ring buffer maps naturally to CPU cache lines (sequential memory
  access = hardware prefetcher wins), `WaitStrategy` trade-offs (spinning burns CPU but minimizes
  latency jitter; blocking conserves CPU but adds µs of delay), why ring size must be a power of two
  (bitwise AND modulo is one instruction; `%` on arbitrary sizes is a division).
- **Bullet:** "Replaced synchronized order intake with LMAX Disruptor ring buffer (single-writer,
  pre-allocated slots, YieldingWaitStrategy); throughput increased Nx vs LinkedBlockingQueue baseline
  as measured by JMH; lock contention eliminated (verified via async-profiler)."

---

## Phase 6 — Object pooling & GC elimination
*Goal: zero heap allocation in the steady-state hot path — no GC pauses at microsecond latency targets.*

1. **Teach GC pressure:** even with G1/ZGC, concurrent marking and occasional stop-the-world pauses
   jitter latency unpredictably — p99.9 spikes are almost always GC events. The only guaranteed fix
   is **zero allocation** after warm-up: no new objects in the hot path.
2. Object pool for `Order` instances: a fixed-capacity `ArrayDeque<Order>` acting as a stack of
   pre-allocated orders; `borrow()` pops one, `return_(order)` pushes it back after the order is
   filled or cancelled. Pool capacity = expected max live orders. Pool is single-threaded (the
   matching engine is single-threaded) — no synchronization needed.
3. Integrate the pool into the matching engine: on a new order event, borrow from pool, populate
   fields, insert into book. On fill completion or cancel, clear fields and return to pool.
4. Verify with `-Xlog:gc*` and async-profiler allocation mode: run the Disruptor + engine under JMH
   for 10 seconds; assert zero GC events after the warm-up phase; attach the GC log as a CI artifact.
5. Track pool exhaustion: if `borrow()` returns `null`, log a structured warning (`pool_exhausted=true,
   pool_size=N`), back-pressure the producer via Disruptor's `BlockingWaitStrategy`, and never
   allocate as a fallback — allocation in the hot path would defeat the entire purpose.
6. **Break it:** remove the pool, allocate `new Order(...)` per event → run the same JMH benchmark →
   observe GC events in the log and p99.9 tail spikes; capture the GC-free run's throughput as a
   regression floor.
- **Learn:** JVM allocation mechanics (TLAB fast-path allocation is nearly free, but the object still
  lives on the heap and triggers GC when the nursery fills), **why escape analysis doesn't save you**
  here (Order objects escape into the TreeMap — the JIT cannot eliminate that allocation), G1 vs ZGC
  vs Shenandoah trade-offs for low-latency (ZGC has sub-ms pauses but still has them), JVM safepoints
  (even concurrent GC requires safepoints to enter — every thread must reach a safepoint before GC
  can proceed, causing latency spikes), when object pooling is the wrong choice (complex lifecycle,
  thread-safety overhead, use-after-return bugs).
- **Bullet:** "Eliminated hot-path heap allocation via object pooling; zero GC events in steady-state
  10-second benchmark window verified with -Xlog:gc*; p99.9 reduced Zx vs no-pooling baseline."

---

## Phase 7 — Cache-line padding & false sharing elimination
*Goal: producer and consumer threads on different cores must not invalidate each other's cache lines on every write.*

1. **Teach false sharing:** a CPU cache line is 64 bytes. If the Disruptor's producer `Sequence` and
   consumer `Sequence` share a cache line, every write by the producer (to advance its sequence)
   triggers a **MESI protocol invalidation** of the consumer's L1 cache line — and vice versa. The
   result is that two threads communicating only through a ring-buffer sequence counter are
   *constantly invalidating each other's caches*, as if they were writing to the same variable.
2. Show the Disruptor's `PaddedLong`: 7 longs of padding + 1 value field = 64 bytes → the value
   occupies its own cache line, invisible to threads on other cores. Implement the same pattern for
   any shared counter in the engine.
3. Java's `@Contended` annotation (JEP 142): annotating a field with `@sun.misc.Contended` (requires
   `-XX:-RestrictContended` on JVM startup) tells the JVM to add padding automatically; more
   reliable than manual padding because the JVM may reorder fields in a class layout. Document the
   trade-off (requires unlocking a JVM flag).
4. Benchmark: measure single-producer / single-consumer throughput (JMH `@Benchmark`) with and
   without padding on the shared sequence; record the delta as the "false-sharing tax."
5. **Break it:** strip all padding from the `Sequence` class → run the JMH benchmark → throughput
   drops measurably; pin the padded run's throughput as the regression floor.
- **Learn:** CPU cache hierarchy (L1: ~4 cycles / 256 KB, L2: ~12 cycles / 1 MB, L3: ~40 cycles /
  shared; main memory: ~200 cycles), the **MESI coherence protocol** (Modified-Exclusive-Shared-
  Invalid — a write on one core transitions all other copies to Invalid, forcing a reload),
  **mechanical sympathy** (Martin Thompson's term: write code that respects the hardware it runs on),
  why false sharing matters only in multi-threaded code (single-threaded: you own the cache line;
  multi-threaded: you fight for it), why this is the Disruptor's secret weapon (every hot variable
  padded to its own line).
- **Bullet:** "Eliminated false sharing between producer/consumer threads via cache-line padding;
  measured Nx throughput improvement and Y% p99 reduction vs unpadded layout under JMH."

---

## Phase 8 — Off-heap serialization (ByteBuffer / MemorySegment)
*Goal: encode and decode order events without touching the Java heap — GC-free I/O.*

1. **Teach off-heap memory:** `ByteBuffer.allocateDirect()` and `MemorySegment.allocateNative()` both
   allocate in **native (off-heap) memory** — outside the Java heap, invisible to the GC. Reading or
   writing them does not allocate Java objects and cannot trigger GC. The trade-off: no GC safety net
   (you manage the lifetime), and Java's JIT has fewer optimization opportunities for off-heap access.
2. Fixed-width binary order layout (42 bytes per order):
   `orderId (8B) | symbol (8B, encoded as long hash) | side (1B) | type (1B) | price (8B) | quantity (8B) | timestamp (8B)`
   Write and read via `DirectByteBuffer`: `buf.putLong(offset, value)` / `buf.getLong(offset)`.
3. Upgrade to `MemorySegment` (Panama, `java.lang.foreign`): safer bounds checking, `VarHandle`-based
   typed reads/writes, explicit `MemorySession` lifetime management. Compare ergonomics vs raw
   `ByteBuffer`.
4. Measure allocation rate with async-profiler (`-e alloc`) during the I/O path: assert zero heap
   allocation per encode/decode cycle after warm-up.
5. **Break it:** replace with `ObjectOutputStream` serialization → async-profiler shows allocation on
   every encode; GC events appear in the log; pin the allocation-free path's allocation rate as a
   regression threshold.
- **Learn:** heap vs off-heap memory (Java heap = GC-managed; native memory = unmanaged; `ByteBuffer.wrap()`
  is on-heap, `allocateDirect()` is off-heap), **Project Panama** (MemorySegment, the modern, safe,
  JEP-standardized off-heap API replacing `sun.misc.Unsafe`), `VarHandle` vs `Unsafe` (VarHandle is
  the sanctioned API with full JIT support; `Unsafe` is unsupported and may be restricted in future
  JVMs), **endianness** (x86 is little-endian; network convention is big-endian; pick one and document
  it), why fixed-width encoding is faster than variable-length (seek to any field in O(1), no parsing).
- **Bullet:** "Replaced on-heap order serialization with off-heap ByteBuffer/MemorySegment encoding;
  async-profiler confirms zero heap allocation in the I/O path; GC events eliminated during sustained
  encode/decode benchmarks."

---

## Phase 9 — JMH benchmarks, HdrHistogram measurement, profiling & CI budget
*Goal: real, defensible numbers with documented hardware and methodology — and a performance regression
budget that makes regressions impossible to ship.*

1. **JMH benchmark suite:**
   - Throughput benchmark (`Mode.Throughput`): single-producer → Disruptor → engine, measure
     orders matched/sec at 50%, 75%, 90%, and 100% of ring buffer capacity.
   - Latency benchmark (`Mode.AverageTime`): end-to-end timestamp from event publish to fill callback,
     captured into an `HdrHistogram.Recorder`; report p50/p99/p99.9/p99.99 at end of measurement.
   - Allocation benchmark (`Mode.SingleShotTime` + `-prof gc`): assert zero allocations per op in
     steady state (JMH's built-in GC profiler reports allocated bytes/op).
2. **HdrHistogram correctly:** use `Recorder` (thread-safe writer) + `Histogram` (reader) separated
   by a double-buffer swap; understand **coordinated omission** (if your benchmark loop slows down
   while waiting, you under-count high-latency events — HdrHistogram's `recordValueWithExpectedInterval`
   corrects for this).
3. **async-profiler flame graph:** run the full benchmark under async-profiler wall-clock mode; identify
   the hottest call site in the matching loop; make one targeted, measured fix; capture before/after
   flame graphs as SVG artifacts.
4. **Performance regression budget in CI:** a lightweight JMH smoke benchmark runs on every PR
   (2 forks, 3 warm-up iterations, 5 measurement iterations); the pipeline compares the result to a
   pinned baseline JSON; fail the PR if throughput drops >10% or p99 regresses >20%. Archive full
   benchmark results as CI artifacts on every run.
5. **Write the README and benchmark report:** architecture diagram (ASCII or Mermaid), ADR index
   (link to each `docs/adr/*.md`), benchmark report with: CPU model + core count, JVM version + GC
   policy, JMH parameters, HdrHistogram output, flame graphs, and a **"failures I induced and how
   the system survived"** section (each Phase's "break it" result, what the regression caught, what
   the fix was). This section is what separates "I built a matching engine" from "I understand why
   every design decision exists."
6. **Break it:** insert a `Thread.sleep(1)` in the matching loop event handler → CI regression budget
   fires (p99 blows past the threshold) and blocks the merge; remove it and verify the benchmark
   clears; capture the failing and passing benchmark JSONs as examples in the README.
- **Learn:** **JMH internals** (`@State` scope, `@Benchmark` annotation, `Blackhole.consume()` for
  dead-code elimination, why forking matters for JIT profile isolation), **coordinated omission** (the
  classic latency measurement lie — Gil Tene's talk is required reading; HdrHistogram fixes it),
  **flame graph reading** (width = time on CPU, depth = call stack depth; the "flat top" is the hot
  function; wide flat tops are your target), **Amdahl's law** (the theoretical ceiling on speedup from
  parallelism — knowing it means you can tell an interviewer why adding more cores won't help beyond
  a point), how to write a benchmark report a senior distributed-systems engineer would trust.
- **Bullet:** "Load-tested to X M orders/sec at p99 < Y µs, p99.9 < Z µs (HdrHistogram, no
  coordinated omission, documented hardware); async-profiler flame-graph–guided optimization cut
  matching loop hot path Nx; CI performance regression budget blocks throughput regression >10% /
  p99 regression >20%."

---

## Suggested pace
~3–4 weeks at 1–2 hrs/day (Phases 5–7 are the conceptual time sinks — the Disruptor, GC mechanics,
and cache hierarchy require slowing down and actually measuring). Budget extra time for Phase 9: the
benchmark report and flame-graph analysis are where the CV signal is densest.

**Phases 0–6 are the core** (quality gates → order book → matching → full types → correctness proof →
lock-free intake → GC elimination). **7–9 turn a strong project into one that reads as production
HFT infrastructure** to a reviewer with systems depth. If short on time, ship 0–6, then 9 (numbers) —
the optimization story without the numbers is incomplete.

## The payoff
By the end you'll have *built*, to a standard you can defend in a code review: a price-time priority
limit order book, a lock-free ring-buffer intake, GC-eliminated hot path with object pooling,
false-sharing–eliminated cache-line–padded sequences, off-heap serialization, a property-based
correctness proof across 50,000+ random sequences, and JMH/HdrHistogram numbers you can cite cold —
with flame graphs and a benchmark report to prove it. That closes the Java internals gap (JMM, GC,
cache hierarchy, lock-free algorithms), demonstrates **systems thinking** rather than API wiring,
and produces metrics precise enough that a senior systems engineer can't wave them away.
