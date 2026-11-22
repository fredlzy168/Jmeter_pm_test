# Trade Me Category Details — JUnit / Maven Performance Test

This module is a **JUnit 5 + Maven transformation** of the PowerShell-driven pipeline
(`scripts/run-test.ps1` + `scripts/validate-nfr.ps1`). The behaviour, the test plan and the
outputs are identical — only the orchestration layer changed:

| PowerShell pipeline | This module (Java) |
| --- | --- |
| `run-test.ps1` parameter derivation | `TestConfig` (NFR-01/02/03 defaults, override precedence, loop buffer) |
| `jmeter -n -t ... -l ... -e -o ...` CLI | `JMeterRunner` — the **embedded** JMeter 5.6.3 engine (`StandardJMeterEngine`), no JMeter installation required |
| `validate-nfr.ps1` | `NfrValidator` + `JtlReader` (same gates, same thresholds, same `nfr_summary.txt`) |
| exit code 0/1/2 | JUnit verdict: 9 acceptance tests, one per gate |
| `testdata/category_ids.csv` | `src/test/resources/category_ids.csv` |
| `test-plan/TradeMe_CategoryDetails_PerfTest.jmx` | `src/test/resources/testplan/…jmx` (**byte-identical copy**) |

The JMeter test plan itself is unchanged: same HTTP sampler, same Groovy functional
assertions (status 200 / CategoryId echo / CanRelist=true), same CSV writer deliverable
(`CategoryId,Name,Path,PromotionId,Price`), same Constant Throughput Timer pacing.

## Prerequisites

* **JDK 17+** (built and verified on Temurin 21)
* **Maven 3.9+**
* **JMeter installation required.** The engine runs from the official
  bundled under `src/test/resources/`.

A local JMeter 5.6.3 installation is only needed for the optional **HTML dashboard**
Point at it with `-Dtrademe.jmeterHome=C:/Java/apache-jmeter-5.6.3` (or `JMETER_HOME`).

## Quick start

```bash
cd maven   # this directory

# 1. Fast suite: functional contract (live API, 10 categories) + unit tests (~5 s)
mvn test

# 2. Full NFR acceptance suite: embedded JMeter load run + all gates (~40 s)
mvn test "-Djunit.groups=load" "-Djunit.excludedGroups="

# 3. Everything in one build
mvn test "-Djunit.excludedGroups="

# With the optional JMeter HTML dashboard:
mvn test "-Djunit.groups=load" "-Djunit.excludedGroups=" ^
    -Dtrademe.jmeterHome=C:/Java/apache-jmeter-5.6.3
```

## What each test proves

| Test class | Test | Requirement |
| --- | --- | --- |
| `CategoryContractTest` | one parameterised test per Category ID: HTTP 200, CategoryId echo, CanRelist=true (documented findings are skipped with the reason, not failed) | functional checks from the JMX assertions |
| `DerivedProfileTest` | derivation rules: 10 IDs → 5 VUsers (NFR-01), 1 VUser/s (NFR-02), 10 calls/min (NFR-03), loop buffer | run-test.ps1 §1 port |
| `JtlParserTest` | RFC 4180 JTL parsing, nearest-rank percentiles, thread counting against a fixed fixture | validate-nfr.ps1 port |
| `NfrAcceptanceTest` (`@Tag("load")`) | one embedded engine run in `@BeforeAll`, then one test per gate: **NFR-01**, **NFR-02**, **NFR-03**, **NFR-04**, functional assertions, CanRelist findings, CSV deliverable + header, overall verdict | all NFRs end-to-end |

## Configuration

Resolution order (highest wins): JVM system property → environment variable →
`src/test/resources/test.properties` → built-in default.

| Property / env (`TRADEME_*`) | Default | Meaning |
| --- | --- | --- |
| `trademe.threads` | `0` (derive: ceil(IDs/2) = 5) | NFR-01 VUser count |
| `trademe.rampupSeconds` | `0` (derive: 1 VUser/s = 5 s) | NFR-02 ramp-up |
| `trademe.vusersPerSecond` | `1` | NFR-02 ramp rate |
| `trademe.steadyStateSeconds` | `60` | steady-state window |
| `trademe.throughputPerMinute` | `0` (derive: IDs count = 10) | NFR-03 total calls/min |
| `trademe.p90ThresholdMs` | `500` | NFR-04 gate |
| `trademe.protocol` / `trademe.host` / `trademe.port` | `https` / `api.tmsandbox.co.nz` / — | target API |
| `trademe.jmeterHome` (`JMETER_HOME`) | — | enables the HTML dashboard |
| `trademe.htmlReport` | `true` | generate dashboard when a JMeter home is configured |
| `trademe.outputRoot` | `target/results` | run output root |
| `trademe.runLabel` | — | suffix for the run directory name |

Example — the same override profile as `run-test.ps1 -Threads 10 -RampUpSeconds 10
-SteadyStateSeconds 120 -ThroughputPerMinute 20`:

```PowerShell
mvn test "-Djunit.groups=load" "-Djunit.excludedGroups=" ^
    "-Dtrademe.threads=10" "-Dtrademe.rampupSeconds=10" ^
    "-Dtrademe.steadyStateSeconds=120" "-Dtrademe.throughputPerMinute=20"
```

## Outputs of every run

`target/results/run_<yyyyMMdd_HHmmss>[_<label>]/`

* `results.jtl` — raw JMeter CSV log (same columns as the CLI runs)
* `category_promotions.csv` — the assignment CSV deliverable (37 rows for the shipped data)
* `nfr_summary.txt` — the gate summary, byte-for-byte the same layout as the PowerShell validator
* `html-report/` — only when a JMeter home is configured

## Measured evidence (live sandbox, 2026-11-22)

| Run | Runner | Profile | p90 | NFR-04 | Notes |
| --- | --- | --- | --- | --- | --- |
| `run_20261122_122203_maven-junit-pass` | embedded JMeter | 5 VU / 5 s / 60 s / 10 cpm | 472 ms | PASS | |

Reference points from the original CLI pipeline on 2026-11-22: 391 ms / 398 ms p90 (PASS).
Run timing cross-check: the embedded engine reproduced the CLI call schedule almost
exactly (active window 33.98 s vs 33.14 s; 17.7 vs 17.6 observed calls/min) — the ramp-up
burst followed by ~7 s spacing is the Constant Throughput Timer sharing 10 calls/min
across the ramping VUsers, identical in both runners.

**NFR-04 is sensitive to live-sandbox latency.** 
The gate itself is deterministic: it
fails when (and only when) the measured p90 exceeds 500 ms. When the sandbox is slow,
re-running is the normal response; every run keeps its own `nfr_summary.txt` so no
evidence is overwritten. Category **6331** legitimately returns `CanRelist=false` and is
always reported as a data finding (WARN in the summary, skipped contract test), never a
hard failure — see the parent README, *Assumptions*.

## CI example

```yaml
- name: Set up JDK 21
  uses: actions/setup-java@v4
  with: { distribution: temurin, java-version: '21' }

- name: Contract + unit tests
  run: mvn -B test

- name: NFR acceptance (embedded JMeter load run)
  run: mvn -B test "-Djunit.groups=load" "-Djunit.excludedGroups="

- name: NFR acceptance + Jmeter report
   mvn -B test "-Djunit.groups=load" "-Djunit.excludedGroups=" "-Dtrademe.jmeterHome=C:\Java\apache-jmeter-5.6.3"
- uses: actions/upload-artifact@v4
  if: always()
  with: { name: results, path: maven/target/results/ }
```

## Troubleshooting

* **`Response body is not valid JSON` or non-200 in contract tests** — the sandbox is
  unreachable or returning an error; retry or point `trademe.host` at a mirror.
* **Groovy `JsonSlurper` errors** — keep `org.codehaus.groovy:groovy-json` on the
  classpath (already in `pom.xml`, pinned to the JMeter-bundled 3.0.20).
* **Proxy environments** — set `-Dtrademe.protocol/-Dtrademe.host/-Dtrademe.port` and the
  usual `https.proxyHost`/`https.proxyPort` JVM properties.
