# Progress

Definition of done per phase (from BRIEF.md): code runs and is demoable; clears the
production bar (tests green, lint clean, gates wired); the "break it" failure was watched
in measured terms and pinned; ADR + resume bullet written.

| Phase | Name | Status |
|-------|------|--------|
| 0 | Project Foundations | ✅ Done |
| 1 | Price-Level Order Book | ☐ Not started |
| 2 | Limit Order Matching | ☐ Not started |
| 3 | Market / IOC / FOK / Cancel | ☐ Not started |
| 4 | Property-Based Testing | ☐ Not started |
| 5 | LMAX Disruptor | ☐ Not started |
| 6 | Object Pooling | ☐ Not started |
| 7 | False Sharing & Cache-Line Padding | ☐ Not started |
| 8 | Off-Heap Serialization | ☐ Not started |
| 9 | The Numbers | ☐ Not started |

## Phase 0 — done

- Gradle wrapper pinned to 8.14; Java 21 toolchain auto-provisioned (Temurin 21.0.11 LTS).
- Version catalog; `java-library` + JUnit 5; `ToolchainSmokeTest` proves compile+run on 21.
- Quality gates wired into `./gradlew check`: Checkstyle (Google subset), SpotBugs +
  findsecbugs, JaCoCo, PIT (book/, 80%), OWASP dep-check (CVSS ≥ 7.0).
- JMH source set (`src/jmh`) via the JMH Gradle plugin.
- Async JSON logging (Logback AsyncAppender + Logstash encoder) with MDC correlation fields.
- GitHub Actions CI: ordered gates + artifact upload; separate JMH perf-budget workflow.
- **Break it:** SpotBugs caught a null deref (`NP_ALWAYS_NULL`) and failed the build; ADR 0000.
