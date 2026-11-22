package nz.co.trademe.perftest;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import java.io.IOException;
import java.nio.file.Files;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Full end-to-end acceptance suite - the JUnit replacement of
 * {@code scripts/run-test.ps1} + {@code scripts/validate-nfr.ps1}:
 *
 * <ol>
 *   <li>{@link #runLoadTestOnce()} executes the unchanged JMeter test plan through the
 *       embedded JMeter 5.6.3 engine with the derived assignment profile
 *       (5 VUsers, 5 s ramp-up, 60 s steady state, 10 calls/min).</li>
 *   <li>Every non-functional requirement, the functional assertions and the CSV
 *       deliverable are then asserted as individual JUnit tests.</li>
 * </ol>
 *
 * <p>The suite is tagged {@code load} because it drives real traffic against the sandbox
 * and takes about 70 seconds. The results (results.jtl, category_promotions.csv,
 * nfr_summary.txt and - when a JMeter installation is configured - the HTML dashboard)
 * are written to {@code target/results/run_<timestamp>[_<label>]/}.
 *
 * <p>Run it with:
 * <pre>
 *   mvn test -Djunit.groups=load -Djunit.excludedGroups=     # only this suite
 *   mvn test -Djunit.excludedGroups=                         # full build incl. this suite
 * </pre>
 */
@Tag("load")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("NFR acceptance - embedded JMeter run of TradeMe_CategoryDetails_PerfTest.jmx")
class NfrAcceptanceTest {

    private TestConfig config;
    private JMeterRunner.RunOutput runOutput;
    private JtlReader.RunStats stats;
    private NfrValidator.NfrReport report;

    @BeforeAll
    void runLoadTestOnce() throws IOException {
        config = TestConfig.load();
        runOutput = new JMeterRunner().run(config);

        List<JtlReader.SampleRecord> samples = JtlReader.parse(runOutput.resultsJtl());
        stats = JtlReader.summarise(samples);
        report = new NfrValidator(config).validate(samples, stats,
                runOutput.resultsJtl(), runOutput.promotionsCsv());
        report.writeSummary(runOutput.runDir());
    }

    @Test
    @DisplayName("NFR-01 - VUsers equal half the Category ID count (5 of 10)")
    void nfr01_vUsersEqualHalfTheCategoryIds() {
        NfrValidator.CheckResult check = report.byId("NFR-01");
        assertTrue(check.passed(), () -> check.label() + " - " + check.evidence());
    }

    @Test
    @DisplayName("NFR-02 - ramp-up delivers one VUser per second")
    void nfr02_rampUpAtOneVUserPerSecond() {
        NfrValidator.CheckResult check = report.byId("NFR-02");
        assertTrue(check.passed(), () -> check.label() + " - " + check.evidence());
    }

    @Test
    @DisplayName("NFR-03 - exactly one call per Category ID across the 1-minute steady state")
    void nfr03_exactlyTenCallsInTheSteadyState() {
        NfrValidator.CheckResult check = report.byId("NFR-03");
        assertTrue(check.passed(), () -> check.label() + " - " + check.evidence());
    }

    @Test
    @DisplayName("NFR-04 - 90th percentile response time is at most 500 ms")
    void nfr04_p90AtMost500Ms() {
        NfrValidator.CheckResult check = report.byId("NFR-04");
        assertTrue(check.passed(), () -> check.label() + " - " + check.evidence());
    }

    @Test
    @DisplayName("Functional assertions - response status and CategoryId parameter checks pass")
    void functionalStatusAndParameterAssertionsPass() {
        NfrValidator.CheckResult check = report.assertionsCheck();
        assertTrue(check.passed(), () -> check.label() + " - " + check.evidence());
    }

    @Test
    @DisplayName("CanRelist findings are limited to the documented data findings")
    void canRelistFindingsMatchTheDocumentedList() {
        assertTrue(report.canRelistFindingCount() <= 1,
                () -> "unexpected CanRelist findings: " + report.canRelistCheck().evidence());
    }

    @Test
    @DisplayName("CSV deliverable - category_promotions.csv covers every successful call")
    void csvDeliverableCoversEverySuccessfulCall() {
        NfrValidator.CheckResult check = report.csvCheck();
        assertTrue(check.passed(), () -> check.label() + " - " + check.evidence());
    }

    @Test
    @DisplayName("CSV deliverable - header matches the assignment columns")
    void csvDeliverableHeaderMatchesTheAssignment() throws IOException {
        String header = Files.readAllLines(runOutput.promotionsCsv()).get(0);
        assertEquals("CategoryId,Name,Path,PromotionId,Price", header);
    }

    @Test
    @DisplayName("Every hard gate of the run passes")
    void overallVerdictIsPass() {
        assertTrue(report.allHardGatesPass(),
                () -> "one or more hard gates failed - see nfr_summary.txt in " + runOutput.runDir());
    }
}
