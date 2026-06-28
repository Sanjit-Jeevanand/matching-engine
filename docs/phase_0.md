# Phase 0 — Build System & Project Structure

## What is Phase 0?

Before writing a single line of business logic, we need the project infrastructure:
- A build tool that compiles code, runs tests, and manages dependencies
- A pinned Java version so the build is reproducible on any machine
- A folder structure that Java and Gradle expect

---

## Tools

### Gradle
Gradle is the build tool. It does three things:
1. Downloads dependencies (JUnit, LMAX Disruptor, etc.) from Maven Central — the public Java library registry, equivalent to PyPI for Python
2. Compiles `.java` files into `.class` files (bytecode the JVM runs) — Java is not interpreted like Python
3. Runs tests, style checks, benchmarks via simple commands (`./gradlew test`, `./gradlew check`)

We use the **Kotlin DSL** (`build.gradle.kts`) for build scripts — type-safe, IDE-aware, catches typos at build time.

### Java 21
Hard requirement. We use:
- **Records** — immutable value types (for `Fill`, trade events)
- **Sealed interfaces** — exhaustive pattern matching (for `MatchResult`)
- **Panama `MemorySegment`** — off-heap serialization (Phase 8)

---

## Files created

### `settings.gradle.kts`
```kotlin
rootProject.name = "matching-engine"
```
Declares the project name. No submodules — single flat project.

### `build.gradle.kts`
```kotlin
plugins {
    java
    application
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

repositories {
    mavenCentral()
}

dependencies {
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.0")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:1.11.0")
}

tasks.test {
    useJUnitPlatform()
}
```

- `java` plugin — enables compile, test, jar tasks
- `application` plugin — enables `./gradlew run`
- `toolchain` — pins Java 21 for compilation regardless of what JDK is on the machine
- `mavenCentral()` — where Gradle downloads libraries from
- `testImplementation` — JUnit 5 only on the test classpath, not production
- `useJUnitPlatform()` — tells Gradle to use JUnit 5's test discovery engine

### `gradle.properties`
```properties
org.gradle.java.home=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home
```
Forces the Gradle daemon itself to run on Java 21, not whatever JDK is default on the machine.

---

## Folder structure

```
src/main/java/com/exchange/matching/model/    ← domain model (Order, Side, Fill...)
src/main/java/com/exchange/matching/book/     ← order book logic
src/test/java/com/exchange/matching/book/     ← tests for the book
```

Java requires the folder path to match the package name exactly:
- Package `com.exchange.matching.model` → folder `com/exchange/matching/model/`
- `src/main/java/` is Gradle's convention for production source roots

---

## First file: `Side.java`

```java
package com.exchange.matching.model;

public enum Side {
    BUY,
    SELL
}
```

**Why an enum, not a boolean or String?**
- `boolean isBuy` — unclear. `isBuy = false` means sell? Not obvious when reading code.
- `String side = "BUY"` — typo-prone. `"Buy"`, `"buy"`, `"BUY"` are all different strings.
- `enum Side` — only two possible values, enforced by the compiler. Impossible to pass an invalid side.

Every order in the system carries a `Side`. It appears in every method signature, every log line, every test. Getting the type right here pays off across 3,800 lines.

---

## `OrderType.java`

```java
public enum OrderType {
    LIMIT, MARKET, IOC, FOK
}
```

- `LIMIT` — buy/sell at a specific price or better; rests in the book if it doesn't cross immediately
- `MARKET` — fill at whatever price is available right now; never rests
- `IOC` — Immediate Or Cancel; fill what crosses now, cancel the rest
- `FOK` — Fill Or Kill; fill 100% or reject with zero fills

---

## `OrderStatus.java`

```java
public enum OrderStatus {
    OPEN, PARTIALLY_FILLED, FILLED, CANCELLED
}
```

Tracks an order's lifecycle. Transitions: `OPEN → PARTIALLY_FILLED → FILLED` or `OPEN → CANCELLED`.

---

## `Order.java`

The core domain object. Represents one instruction from a trader.

**Why a mutable class, not a record?**
Java records are immutable — once created, fields can't change. But in Phase 6 we'll build an object pool: pre-allocate a fixed set of `Order` objects at startup and reuse them across events. On each new order event, we borrow an `Order` from the pool, call `reset()` to populate its fields, use it, then return it to the pool. Zero allocation in the hot path = zero GC.

You can't do that with an immutable record — you'd allocate a new object every time, which defeats the purpose.

**Key fields:**
- `long price` — integer ticks, never `double` (IEEE 754 rounding causes missed fills)
- `long remainingQty` — starts equal to `quantity`; decremented on each partial fill
- `long timestampNanos` — nanosecond arrival time; breaks ties at the same price (time priority)
- `OrderStatus status` — starts `OPEN`; only `remainingQty` and `status` have setters

**`reset()` method** — populates all fields on pool borrow. Returns `this` so callers can write `pool.borrow().reset(...)` in one line.

---

## `Fill.java`

A `Fill` is a completed trade between two orders. Created every time a buy and sell cross.

**Why a record?**
Unlike `Order`, a fill never changes after it's created. Records are Java 21's immutable value types — the compiler auto-generates the constructor and getters. Perfect fit.

**Fields:**
- `long buyOrderId` / `long sellOrderId` — who traded
- `long price` — the maker's price (the resting order's price, not the aggressor's)
- `long quantity` — how much was traded (the smaller of the two sides)
- `long timestampNanos` — when the trade happened

Fills are what the outside world sees — the official trade tape, the audit log, the latency measurements, the throughput benchmarks. Everything downstream is driven by fills.
