# Project Brief: Low-Latency Order Matching Engine (Production-Grade, Learn-As-You-Build)

## What we're building
A **limit order book matching engine** in **Java 21** — the component at the heart of every stock and
crypto exchange that matches buy and sell orders in **price-time priority** at microsecond latency.
Order events flow in over a lock-free **LMAX Disruptor ring buffer**; the engine matches them against a
two-sided price-level order book; fills are emitted as trade events; the whole system sustains
**3–5 M orders/sec at p99 < 10 µs** with **zero GC in steady state**, verified by JMH benchmarks
and HdrHistogram tail-latency distributions — not guessed at with `System.nanoTime()` averages.

This is a **systems engineering** project, not a data-structures exercise. The matching algorithm is
straightforward; the difficulty is eliminating every source of latency jitter: GC pauses, false
sharing between threads, lock contention, and heap allocation in the hot path. Every optimization is
*earned* by measurement, not asserted by intuition.

Target architecture:
```
Order stream ──(fixed-width off-heap binary)──┐
                                              ▼
                        ┌──── LMAX Disruptor Ring Buffer ────┐
                        │  Pre-allocated OrderEvent slots     │
                        │  Cache-line–padded sequence numbers │
                        │  Single-writer, zero lock contention│
                        └──────────────┬─────────────────────┘
                                       │ EventHandler (single consumer thread)
                        ┌──── Matching Engine ────────────────┐
                        │  Bid book: TreeMap<price↓, Deque>   │
                        │  Ask book: TreeMap<price↑, Deque>   │
                        │  Object-pooled Order instances       │
                        │  Limit / Market / Cancel / IOC / FOK│
                        └──────────────┬─────────────────────┘
                                       │ Fill / Trade events
                        ┌──── Trade Event Sink ───────────────┐
                        │  HdrHistogram latency recording      │
                        │  JMH throughput measurement          │
                        │  Structured JSON audit log           │
                        └─────────────────────────────────────┘

Observability: GC logs (-Xlog:gc*), async-profiler flame graphs, JMH result archives
CI: test → Checkstyle/SpotBugs → OWASP dep-check → build → JMH regression budget → artifact upload
```

## How I want you to work with me (the learning loop — IMPORTANT)
This is a **learning project** built to production standards. The goal is that *I* understand
low-latency JVM engineering **and** what "production-ready" actually means by the end. I am comfortable
with Java syntax but new to JVM internals, the Java Memory Model, lock-free data structures, and
hardware-level performance engineering; teach the mechanism alongside the concept, but never let
"I'm learning" lower the bar.

For every phase and every meaningful step:
1. **Teach first.** Before writing code, explain the concept in 4–8 sentences: the problem it solves,
   the trade-offs, and how real systems (LMAX Exchange, HFT firms, Aeron, Chronicle Map) handle it.
   Name the systems term explicitly (false sharing, write barrier, ring buffer, price-time priority,
   cache-line, safepoint, object pool, MESI protocol, coordinated omission, etc.).
2. **Build incrementally, to standard.** Write the smallest slice that works *and meets the production
   bar below* — tests, types, profiling points included, not bolted on later. One concept → one
   change → verify → next. Don't generate three files at once.
3. **Make me reason.** Before each non-trivial decision, ask me what I'd do and why. If I'm wrong,
   correct me with the reasoning. Pose a "what breaks if…?" question (e.g., "what happens to p99.9
   if the matcher allocates a new Order on every event?").
4. **Force the failure — as an automated benchmark regression.** After the happy path, deliberately
   break it (strip cache-line padding, add allocation in the hot path, replace the Disruptor with a
   synchronized queue) so I *see* the failure mode in numbers — then capture the before/after as a
   benchmark regression threshold in CI so it can never silently return.
5. **Connect to interviews.** End each phase with: the resume bullet it earns (with a real measured
   metric, not a placeholder), the interview question it lets me answer, and a one-paragraph
   **Architecture Decision Record (ADR)** capturing the choice and trade-off.

## What makes this *exceptional*, not generic (the non-negotiable differentiators)
A `TreeMap` + `synchronized` queue that "goes fast" is forgettable. These four make it defensible:
1. **Build the hard primitive yourself.** The order book, matching logic, and lock-free intake are
   hand-built and *understood* — not a library call. The point is knowing *why* a TreeMap keyed on
   price works, why a Deque per level preserves time priority, and why the Disruptor's single-writer
   principle eliminates the need for locks — and being able to explain any of it cold in an interview.
2. **Measure honestly.** HdrHistogram captures p99.9 and p99.99 without the coordinated-omission lie;
   JMH controls JVM warm-up, dead-code elimination, and benchmark harness bias. "I measured p99.9 < 8 µs
   under sustained 4 M orders/sec on documented hardware" is defensible. "It's fast" is not.
3. **Earn the optimization story.** Each optimization (object pooling, cache-line padding, off-heap
   serialization) is first *broken*, then *fixed*, then *measured before and after*. The before/after
   numbers and the reasoning behind them are the signal to a senior reviewer.
4. **Ship it like production.** CI pipeline with a performance regression budget, async-profiler flame
   graphs, a benchmark report with hardware spec and methodology, and a "failures I induced and how the
   system survived" section. Half of "exceptional" is that a reviewer sees the depth in 30 seconds.

## The production bar (non-negotiable — applies to every phase)
Nothing is "done" until it clears all of these. Build them in from the start, not bolted on later.

- **Testing.** JUnit 5 unit tests for all matching logic; property-based tests (jqwik) for invariants
  (price-time priority preserved, no fill at wrong price, no quantity leak, no phantom orders); JMH
  benchmarks treated as regression tests with pinned thresholds; mutation testing (PIT) on the matching
  core (>80% mutation score). CI is the gate — red build never merges.
- **Type safety & style.** Checkstyle (Google Java Style) + SpotBugs clean; no raw types; explicit
  `@Nullable` / `@NonNull` where ambiguous; no unchecked casts in production paths; no `//NOPMD`
  without a justified comment.
- **Security.** Input validation on all order fields (price > 0, quantity > 0, valid symbol); OWASP
  Dependency-Check in CI (fail on CVSS ≥ 7.0); no secrets in code or logs; no external dependencies
  pulled without a pinned version and a justified choice.
- **Observability.** Structured JSON logs with a `correlation_id` / order ID propagated end-to-end;
  JMH benchmark result JSON archived as CI artifacts on every run; HdrHistogram output (`hgrm` file)
  captured per benchmark; GC log analysis (`-Xlog:gc*`) attached to benchmark reports; async-profiler
  flame graphs for each optimization phase.
- **Reliability.** Graceful shutdown (drain in-flight orders before exit, no silent data loss); explicit
  overflow handling on the ring buffer (back-pressure policy documented and tested); bounded object
  pool with pool-exhaustion warning logged; cancel of an unknown order ID returns a well-typed error,
  never panics or silently no-ops.
- **Operability.** Config via environment variables (ring buffer size, pool capacity, symbol list,
  GC policy flags); configuration changes versioned; runbooks for each failure mode; ADRs for each
  significant decision captured in `docs/adr/`.
- **Delivery.** GitHub Actions CI pipeline with ordered quality gates:
  `test → Checkstyle → SpotBugs → OWASP dep-check → PIT → build → JMH regression budget → artifact upload`.
  Performance regression budget: fail PR if throughput drops >10% or p99 regresses >20% vs pinned baseline.

## Non-functional requirements / SLOs
*Numbers below are build-to targets. Phase 9 measures the real ones on documented hardware; update the
resume bullets to the measured values. Report the true number even if lower.*

- **Throughput:** ≥ 3 M orders/sec sustained; target 5 M with object pooling + Disruptor
- **Latency:** p99 < 10 µs, p99.9 < 50 µs under sustained load (HdrHistogram, no coordinated omission)
- **GC:** Zero GC events in the steady-state benchmark window (verified with `-Xlog:gc*`)
- **False sharing:** ≥ 20% measurable throughput improvement after cache-line padding vs naive layout
- **Correctness:** Every limit order fill respects price-time priority; no fill at a price worse than
  the limit; filled quantities sum exactly to `min(buy qty, sell qty)`; no order ID appears twice in
  the book simultaneously
- **Resilience:** Ring buffer overflow handled explicitly (block or drop with documented policy);
  pool exhaustion logged and producer back-pressured; corrupt/invalid order events rejected with a
  typed error, never silently corrupting book state

## Tech constraints
- **Language:** Java 21. Use records for immutable value types (Order, Fill, Symbol). Do not use
  virtual threads in the hot matching path — they add safepoint overhead at microsecond granularity;
  virtual threads belong only in configuration/admin paths. Teach Java 21 idiom alongside the concept.
- **Ring buffer:** LMAX Disruptor. Understand *why* it works: single-writer principle, sequence
  barriers instead of locks, cache-line–padded sequence numbers, pre-allocated ring slots. Do not use
  `java.util.concurrent.LinkedBlockingQueue` or `ArrayBlockingQueue` in the hot path — they allocate
  nodes or contend on locks.
- **Benchmarking:** JMH only — no manual `System.nanoTime()` loops. Understand warm-up, dead-code
  elimination (Blackhole), and why fork count matters. Benchmark results must be reproducible.
- **Latency measurement:** HdrHistogram. Understand coordinated omission and use `Recorder` +
  corrected interval histogram correctly. No `mean` or `stdev` of latency — report p50/p99/p99.9/p99.99.
- **Serialization:** `java.nio.ByteBuffer` (direct) and/or `java.lang.foreign.MemorySegment` (Panama,
  Java 21). No Java object serialization (`ObjectOutputStream`) in any hot path — it allocates.
- **Build:** Gradle with the JMH plugin, Checkstyle, SpotBugs, PIT, OWASP Dependency-Check, JaCoCo.
- **Profiling:** async-profiler (wall-clock + allocation modes). Output flame graphs as SVG, archive
  as CI artifacts at the Phase 9 milestone.
- Prefer stdlib / one obvious library over frameworks that hide the mechanism I am trying to learn —
  but never at the cost of the production bar above.

## Definition of done (per phase)
Code runs and is demoable; it clears the entire production bar (tests green, lint clean, benchmarks
archived, GC logs attached); I can explain *why* every component exists and what I rejected; I've
watched the relevant failure happen in measured numbers and pinned it as a regression threshold; and
I've written the ADR + resume bullet (with a measured metric). Track progress in `PROGRESS.md`.
Don't advance phases until I confirm I understand.

## My background
Strong Python/ML; comfortable with Java syntax; **new to JVM internals, the Java Memory Model, CPU
cache hierarchy, and lock-free algorithms**. Targeting MAANG new-grad SDE. Studying system design in
parallel — tie every concept to it. This project is meant to demonstrate *systems thinking*: I
understand why GC pauses matter at microsecond granularity, what false sharing is and how to fix it,
and how a lock-free ring buffer eliminates contention — and I can defend every one of those words.
