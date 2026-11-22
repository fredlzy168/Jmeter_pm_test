package nz.co.trademe.perftest;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Properties;

/**
 * Immutable configuration for the Trade Me Category Details performance test.
 *
 * <p>Java port of the parameter handling in {@code scripts/run-test.ps1}. Every
 * setting is resolved with this precedence (highest wins):
 * <ol>
 *   <li>JVM system property, e.g. {@code mvn test -Dtrademe.threads=10}</li>
 *   <li>Environment variable, e.g. {@code TRADEME_THREADS=10}</li>
 *   <li>the bundled {@code /test.properties} classpath resource</li>
 *   <li>the built-in default (identical to the PowerShell defaults)</li>
 * </ol>
 *
 * <p>The derived run profile mirrors {@code run-test.ps1} exactly:
 * <ul>
 *   <li>NFR-01: {@code threads = ceil(CategoryIdCount / 2)}</li>
 *   <li>NFR-02: {@code rampUp = ceil(threads / vUsersPerSecond)}</li>
 *   <li>NFR-03: {@code throughputPerMinute = CategoryIdCount}</li>
 *   <li>{@code loops = ceil(CategoryIdCount / threads) + 1} so every Category ID is
 *       called exactly once and the buffer loop is skipped by the EOF guard.</li>
 * </ul>
 */
public final class TestConfig {

    private final String protocol;
    private final String host;
    private final String port;
    private final int threads;            // 0 = derive (NFR-01)
    private final int rampUpSeconds;      // 0 = derive (NFR-02)
    private final double vUsersPerSecond;
    private final int steadyStateSeconds;
    private final double throughputPerMinute; // 0 = derive (NFR-03)
    private final int p90ThresholdMs;     // NFR-04
    private final String jmeterHome;      // optional, enables the HTML dashboard
    private final boolean htmlReport;
    private final String outputRoot;
    private final String runLabel;
    private final List<String> categoryIds;

    private TestConfig(Builder builder) {
        this.protocol = builder.protocol;
        this.host = builder.host;
        this.port = builder.port;
        this.threads = builder.threads;
        this.rampUpSeconds = builder.rampUpSeconds;
        this.vUsersPerSecond = builder.vUsersPerSecond;
        this.steadyStateSeconds = builder.steadyStateSeconds;
        this.throughputPerMinute = builder.throughputPerMinute;
        this.p90ThresholdMs = builder.p90ThresholdMs;
        this.jmeterHome = builder.jmeterHome;
        this.htmlReport = builder.htmlReport;
        this.outputRoot = builder.outputRoot;
        this.runLabel = builder.runLabel;
        this.categoryIds = List.copyOf(builder.categoryIds);
    }

    /** Loads the configuration from system properties, environment and {@code /test.properties}. */
    public static TestConfig load() {
        Properties bundled = readResourceProperties("/test.properties");
        Builder b = new Builder();
        b.protocol = resolve("trademe.protocol", bundled, "https");
        b.host = resolve("trademe.host", bundled, "api.tmsandbox.co.nz");
        b.port = resolve("trademe.port", bundled, "");
        b.threads = resolveInt("trademe.threads", bundled, 0);
        b.rampUpSeconds = resolveInt("trademe.rampupSeconds", bundled, 0);
        b.vUsersPerSecond = resolveDouble("trademe.vusersPerSecond", bundled, 1.0);
        b.steadyStateSeconds = resolveInt("trademe.steadyStateSeconds", bundled, 60);
        b.throughputPerMinute = resolveDouble("trademe.throughputPerMinute", bundled, 0.0);
        b.p90ThresholdMs = resolveInt("trademe.p90ThresholdMs", bundled, 500);
        b.jmeterHome = resolveJMeterHome(bundled);
        b.htmlReport = Boolean.parseBoolean(resolve("trademe.htmlReport", bundled, "true"));
        b.outputRoot = resolve("trademe.outputRoot", bundled, "target/results");
        b.runLabel = resolve("trademe.runLabel", bundled, "");
        b.categoryIds = readCategoryIds();
        return new TestConfig(b);
    }

    /** Deterministic configuration for unit tests, using the shipped defaults. */
    public static TestConfig forTesting(List<String> categoryIds) {
        Builder b = new Builder();
        b.categoryIds = categoryIds;
        return new TestConfig(b);
    }

    public static Builder builder(List<String> categoryIds) {
        Builder b = new Builder();
        b.categoryIds = categoryIds;
        return b;
    }

    public String protocol() {
        return protocol;
    }

    public String host() {
        return host;
    }

    public String port() {
        return port;
    }

    public int threads() {
        return threads;
    }

    public int rampUpSeconds() {
        return rampUpSeconds;
    }

    public double vUsersPerSecond() {
        return vUsersPerSecond;
    }

    public int steadyStateSeconds() {
        return steadyStateSeconds;
    }

    public double throughputPerMinute() {
        return throughputPerMinute;
    }

    public int p90ThresholdMs() {
        return p90ThresholdMs;
    }

    public String jmeterHome() {
        return jmeterHome;
    }

    public boolean htmlReport() {
        return htmlReport;
    }

    public String outputRoot() {
        return outputRoot;
    }

    public String runLabel() {
        return runLabel;
    }

    public List<String> categoryIds() {
        return categoryIds;
    }

    /** NFR-01: VUsers equal half the number of Category IDs, rounded up. */
    public int effectiveThreads() {
        int derived = threads > 0 ? threads : (int) Math.ceil(categoryIds.size() / 2.0);
        return Math.max(1, derived);
    }

    /** NFR-02: ramp-up delivers one VUser per second. */
    public int effectiveRampUpSeconds() {
        if (rampUpSeconds > 0) {
            return rampUpSeconds;
        }
        return (int) Math.ceil(effectiveThreads() / vUsersPerSecond);
    }

    /** NFR-03: one call per Category ID per minute across all VUsers. */
    public double effectiveThroughputPerMinute() {
        return throughputPerMinute > 0 ? throughputPerMinute : categoryIds.size();
    }

    /**
     * One loop consumes {@code ceil(ID count / VUsers)} data rows; the extra loop is a
     * buffer that the EOF guard inside the test plan skips without issuing a call.
     */
    public int loopsPerVUser() {
        return (int) Math.ceil(categoryIds.size() / (double) effectiveThreads()) + 1;
    }

    public String describeProfile() {
        return effectiveThreads() + " VUsers, ramp-up " + effectiveRampUpSeconds() + " s ("
                + trimNumber(vUsersPerSecond) + " VUser/s), steady state " + steadyStateSeconds
                + " s, " + trimNumber(effectiveThroughputPerMinute()) + " calls/min total";
    }

    static String trimNumber(double value) {
        return value == Math.rint(value)
                ? String.valueOf((long) value)
                : String.valueOf(value);
    }

    private static String resolveJMeterHome(Properties bundled) {
        String value = System.getProperty("trademe.jmeterHome");
        if (value == null) {
            value = System.getenv("TRADEME_JMETER_HOME");
        }
        if (value == null) {
            value = System.getenv("JMETER_HOME");
        }
        if (value == null) {
            value = bundled.getProperty("trademe.jmeterHome", "");
        }
        return value == null ? "" : value.trim();
    }

    private static String resolve(String name, Properties bundled, String defaultValue) {
        String value = System.getProperty(name);
        if (value == null) {
            value = System.getenv(name.toUpperCase(Locale.ROOT).replace('.', '_'));
        }
        if (value == null) {
            value = bundled.getProperty(name, defaultValue);
        }
        return value == null ? defaultValue : value.trim();
    }

    private static int resolveInt(String name, Properties bundled, int defaultValue) {
        String raw = resolve(name, bundled, String.valueOf(defaultValue));
        try {
            return Integer.parseInt(raw.isBlank() ? String.valueOf(defaultValue) : raw);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(
                    "Invalid integer for -D" + name + "='" + raw + "': " + e.getMessage(), e);
        }
    }

    private static double resolveDouble(String name, Properties bundled, double defaultValue) {
        String raw = resolve(name, bundled, String.valueOf(defaultValue));
        try {
            return Double.parseDouble(raw.isBlank() ? String.valueOf(defaultValue) : raw);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(
                    "Invalid number for -D" + name + "='" + raw + "': " + e.getMessage(), e);
        }
    }

    private static Properties readResourceProperties(String resource) {
        Properties props = new Properties();
        try (InputStream in = TestConfig.class.getResourceAsStream(resource)) {
            if (in != null) {
                props.load(in);
            }
        } catch (IOException e) {
            throw new IllegalStateException("Cannot read " + resource + ": " + e.getMessage(), e);
        }
        return props;
    }

    /** Reads the Category IDs from the bundled {@code /category_ids.csv} (header skipped). */
    private static List<String> readCategoryIds() {
        List<String> ids = new ArrayList<>();
        try (InputStream in = TestConfig.class.getResourceAsStream("/category_ids.csv")) {
            if (in == null) {
                throw new IllegalStateException("Bundled test data not found: /category_ids.csv");
            }
            List<String> lines = new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8)
                    .lines().toList();
            for (int i = 1; i < lines.size(); i++) { // skip the header row
                String line = lines.get(i).trim();
                if (line.matches("[0-9]+")) {
                    ids.add(line);
                }
            }
        } catch (IOException e) {
            throw new IllegalStateException("Cannot read /category_ids.csv: " + e.getMessage(), e);
        }
        if (ids.isEmpty()) {
            throw new IllegalStateException("No numeric Category IDs found in /category_ids.csv");
        }
        return ids;
    }

    /** Mutable builder used by {@link #load()} and by unit tests that override single values. */
    public static final class Builder {
        private String protocol = "https";
        private String host = "api.tmsandbox.co.nz";
        private String port = "";
        private int threads = 0;
        private int rampUpSeconds = 0;
        private double vUsersPerSecond = 1.0;
        private int steadyStateSeconds = 60;
        private double throughputPerMinute = 0.0;
        private int p90ThresholdMs = 500;
        private String jmeterHome = "";
        private boolean htmlReport = true;
        private String outputRoot = "target/results";
        private String runLabel = "";
        private List<String> categoryIds = List.of();

        public Builder protocol(String value) {
            this.protocol = value;
            return this;
        }

        public Builder host(String value) {
            this.host = value;
            return this;
        }

        public Builder port(String value) {
            this.port = value;
            return this;
        }

        public Builder threads(int value) {
            this.threads = value;
            return this;
        }

        public Builder rampUpSeconds(int value) {
            this.rampUpSeconds = value;
            return this;
        }

        public Builder vUsersPerSecond(double value) {
            this.vUsersPerSecond = value;
            return this;
        }

        public Builder steadyStateSeconds(int value) {
            this.steadyStateSeconds = value;
            return this;
        }

        public Builder throughputPerMinute(double value) {
            this.throughputPerMinute = value;
            return this;
        }

        public Builder p90ThresholdMs(int value) {
            this.p90ThresholdMs = value;
            return this;
        }

        public Builder jmeterHome(String value) {
            this.jmeterHome = value;
            return this;
        }

        public Builder htmlReport(boolean value) {
            this.htmlReport = value;
            return this;
        }

        public Builder outputRoot(String value) {
            this.outputRoot = value;
            return this;
        }

        public Builder runLabel(String value) {
            this.runLabel = value;
            return this;
        }

        public TestConfig build() {
            return new TestConfig(this);
        }
    }
}
