package nz.co.trademe.perftest;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Java port of {@code scripts/validate-nfr.ps1}: validates a completed run against the
 * non-functional requirements and the functional assertions, and writes
 * {@code nfr_summary.txt} next to the raw results (same layout as the PowerShell version).
 *
 * <p>Gates:
 * <ul>
 *   <li>Functional assertions - "Response status check failed" and "CategoryId parameter
 *       check failed" samples are hard gates; any other unexpected failure is a hard gate.</li>
 *   <li>"CanRelist text check failed" samples are reported as expected live-data findings
 *       (warnings) and never fail the run - see README.md, Assumptions.</li>
 *   <li>NFR-01: distinct VUsers that executed equal the expected thread count.</li>
 *   <li>NFR-02: first-sample spread across VUsers fits the ramp-up window.</li>
 *   <li>NFR-03: total API calls equal the expected count.</li>
 *   <li>NFR-04: 90th percentile response time at most {@code p90ThresholdMs}.</li>
 *   <li>CSV deliverable: {@code category_promotions.csv} exists and covers every
 *       successful call.</li>
 * </ul>
 */
public final class NfrValidator {

    /** One gate evaluation with its evidence string. */
    public record CheckResult(String id, String label, boolean passed, boolean warningOnly, String evidence) {
    }

    /** Full validation report of one run. */
    public record NfrReport(String configuredProfile, Path resultsJtl, JtlReader.RunStats stats,
                            CheckResult assertionsCheck, CheckResult canRelistCheck, int canRelistFindingCount,
                            List<CheckResult> nfrChecks, CheckResult csvCheck, boolean allHardGatesPass) {

        /** Returns the check with the given id (e.g. "NFR-01"). */
        public CheckResult byId(String id) {
            for (CheckResult check : nfrChecks) {
                if (check.id().equals(id)) {
                    return check;
                }
            }
            throw new IllegalArgumentException("Unknown check id: " + id);
        }

        /** Writes {@code nfr_summary.txt} (same layout as the PowerShell validator) to stdout as well. */
        public void writeSummary(Path runDir) throws IOException {
            List<String> lines = render();
            Files.write(runDir.resolve("nfr_summary.txt"), lines, StandardCharsets.UTF_8);
            for (String line : lines) {
                System.out.println(line);
            }
        }

        private List<String> render() {
            List<String> lines = new ArrayList<>();
            lines.add("==============================================================");
            lines.add(" Trade Me Category Details - NFR Validation Summary");
            lines.add(" Generated          : " + LocalDateTime.now()
                    .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));
            lines.add(" Results file       : " + resultsJtl);
            lines.add("--------------------------------------------------------------");
            lines.add(" Configured profile : " + configuredProfile);
            lines.add(" Total API calls    : " + stats.totalCalls());
            lines.add(" Observed profile   : " + stats.observedCallsPerMinute() + " calls/min over the active window");
            lines.add(" Response time (ms) : min " + stats.minMs() + " / mean " + stats.meanMs()
                    + " / p50 " + stats.p50Ms() + " / p90 " + stats.p90Ms()
                    + " / p95 " + stats.p95Ms() + " / p99 " + stats.p99Ms() + " / max " + stats.maxMs());
            lines.add(" VUsers observed    : " + stats.distinctThreads() + " distinct, max concurrent "
                    + stats.maxConcurrentThreads());
            lines.add(" Ramp-up spread     : " + stats.rampSpreadSeconds() + " s");
            lines.add("--------------------------------------------------------------");
            lines.add(formatCheck(assertionsCheck));
            lines.add(formatCheck(canRelistCheck));
            for (CheckResult check : nfrChecks) {
                lines.add(formatCheck(check));
            }
            lines.add(formatCheck(csvCheck));
            lines.add("--------------------------------------------------------------");
            String overall = allHardGatesPass ? "PASS" : "FAIL";
            lines.add(" OVERALL: " + overall + (allHardGatesPass && canRelistFindingCount > 0
                    ? " (CanRelist findings reported above)" : ""));
            lines.add("==============================================================");
            return lines;
        }

        private static String formatCheck(CheckResult check) {
            String verdict = check.warningOnly ? "WARN" : check.passed() ? "PASS" : "FAIL";
            String label = check.label();
            String evidence = check.evidence() == null ? "" : " - " + check.evidence();
            return "[" + verdict + "] " + label + evidence;
        }
    }

    private static final Pattern CANRELIST_CATEGORY = Pattern.compile("CategoryId=([0-9]+)");

    private final TestConfig config;

    public NfrValidator(TestConfig config) {
        this.config = config;
    }

    public NfrReport validate(List<JtlReader.SampleRecord> samples, JtlReader.RunStats stats,
                              Path resultsJtl, Path promotionsCsv) throws IOException {
        int totalCalls = stats.totalCalls();

        // --- functional assertion classification (messages come from the Groovy assertion) ---
        List<JtlReader.SampleRecord> failed = samples.stream()
                .filter(sample -> !sample.success()).toList();
        List<JtlReader.SampleRecord> statusFailures = byMessagePrefix(failed, "Response status check failed");
        List<JtlReader.SampleRecord> parameterFailures = byMessagePrefix(failed, "CategoryId parameter check failed");
        List<JtlReader.SampleRecord> canRelistFindings = byMessagePrefix(failed, "CanRelist text check failed");
        List<JtlReader.SampleRecord> otherFailures = failed.stream()
                .filter(sample -> !matchesAnyPrefix(sample, "Response status check failed",
                        "CategoryId parameter check failed", "CanRelist text check failed"))
                .toList();

        TreeSet<String> canRelistCategoryIds = new TreeSet<>();
        for (JtlReader.SampleRecord finding : canRelistFindings) {
            Matcher matcher = CANRELIST_CATEGORY.matcher(finding.failureMessage());
            if (matcher.find()) {
                canRelistCategoryIds.add(matcher.group(1));
            }
        }

        // --- gate evaluation (identical thresholds to validate-nfr.ps1) ---
        boolean nfr01Pass = stats.distinctThreads() == config.effectiveThreads()
                && stats.maxConcurrentThreads() <= config.effectiveThreads();
        double rampToleranceSeconds = config.effectiveRampUpSeconds() + config.vUsersPerSecond() + 1.0;
        boolean nfr02Pass = stats.rampSpreadSeconds() <= rampToleranceSeconds;
        boolean nfr03Pass = totalCalls == config.categoryIds().size();
        boolean nfr04Pass = stats.p90Ms() <= config.p90ThresholdMs();
        boolean assertionsInfraPass = statusFailures.isEmpty() && parameterFailures.isEmpty()
                && otherFailures.isEmpty();

        boolean csvExists = promotionsCsv != null && Files.exists(promotionsCsv);
        int csvRowCount = csvExists ? JtlReader.countCsvDataRows(promotionsCsv) : 0;
        boolean csvPass = csvExists && csvRowCount >= stats.successCount();

        // --- checks ---
        CheckResult assertionsCheck = new CheckResult("ASSERTIONS",
                "Response status + CategoryId parameter assertions",
                assertionsInfraPass, false,
                "status failures=" + statusFailures.size() + ", parameter failures=" + parameterFailures.size()
                        + ", other failures=" + otherFailures.size());

        String findingsSuffix = canRelistCategoryIds.isEmpty() ? ""
                : " (CategoryId: " + String.join(", ", canRelistCategoryIds) + ")";
        CheckResult canRelistCheck = new CheckResult("CANRELIST", "CanRelist=true text check", true, true,
                canRelistFindings.size() + " of " + totalCalls + " responses reported CanRelist=false"
                        + findingsSuffix + " - expected live data finding, see README.md");

        List<CheckResult> nfrChecks = new ArrayList<>();
        nfrChecks.add(new CheckResult("NFR-01", "NFR-01 VUsers = half the Category ID count", nfr01Pass, false,
                "threads=" + stats.distinctThreads() + ", expected=" + config.effectiveThreads()));
        nfrChecks.add(new CheckResult("NFR-02", "NFR-02 Ramp-up at one VUser per second", nfr02Pass, false,
                "first-sample spread=" + stats.rampSpreadSeconds() + " s, configured="
                        + config.effectiveRampUpSeconds() + " s, tolerance=" + round1(rampToleranceSeconds) + " s"));
        nfrChecks.add(new CheckResult("NFR-03", "NFR-03 API calls within the 1-minute steady state", nfr03Pass, false,
                "calls=" + totalCalls + ", expected=" + config.categoryIds().size()));
        nfrChecks.add(new CheckResult("NFR-04",
                "NFR-04 90th percentile response time at most " + config.p90ThresholdMs() + " ms", nfr04Pass, false,
                "p90=" + stats.p90Ms() + " ms"));

        CheckResult csvCheck = new CheckResult("CSV",
                "CSV deliverable category_promotions.csv", csvPass, false,
                "rows=" + csvRowCount + ", successful calls=" + stats.successCount());

        boolean allPass = assertionsInfraPass && nfr01Pass && nfr02Pass && nfr03Pass && nfr04Pass && csvPass;
        return new NfrReport(config.describeProfile(), resultsJtl.toAbsolutePath(), stats,
                assertionsCheck, canRelistCheck, canRelistFindings.size(),
                List.copyOf(nfrChecks), csvCheck, allPass);
    }

    private static List<JtlReader.SampleRecord> byMessagePrefix(List<JtlReader.SampleRecord> records,
                                                                String prefix) {
        return records.stream().filter(sample -> sample.failureMessage() != null
                && sample.failureMessage().startsWith(prefix)).toList();
    }

    private static boolean matchesAnyPrefix(JtlReader.SampleRecord sample, String... prefixes) {
        String message = sample.failureMessage() == null ? "" : sample.failureMessage();
        for (String prefix : prefixes) {
            if (message.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    private static double round1(double value) {
        return Math.round(value * 10.0) / 10.0;
    }
}
