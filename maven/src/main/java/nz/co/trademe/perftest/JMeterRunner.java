package nz.co.trademe.perftest;

import org.apache.jmeter.engine.StandardJMeterEngine;
import org.apache.jmeter.report.dashboard.ReportGenerator;
import org.apache.jmeter.reporters.ResultCollector;
import org.apache.jmeter.reporters.Summariser;
import org.apache.jmeter.samplers.SampleSaveConfiguration;
import org.apache.jmeter.save.SaveService;
import org.apache.jmeter.util.JMeterUtils;
import org.apache.jorphan.collections.HashTree;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Runs the Trade Me Category Details performance test through the <em>embedded</em>
 * JMeter engine - the Java replacement for the {@code jmeter -n -t ... -l ...} invocation
 * in {@code scripts/run-test.ps1}.
 *
 * <p>The test plan ({@code /testplan/TradeMe_CategoryDetails_PerfTest.jmx}) is loaded
 * verbatim from the classpath, so the Maven build and the CLI produce identical results.
 * All execution parameters are pushed into the JMeter property space before the run,
 * which is exactly what the {@code -J} CLI flags did in the PowerShell pipeline.
 *
 * <p>The JMeter HTML dashboard is generated only when {@code trademe.jmeterHome} points
 * at a JMeter installation, because the report template and
 * {@code reportgenerator.properties} ship with the distribution. Everything else works
 * without any local JMeter installation.
 */
public final class JMeterRunner {

    /** Artifacts of one completed run. */
    public record RunOutput(Path runDir, Path resultsJtl, Path promotionsCsv, Path htmlReport) {
    }

    private static final String TEST_PLAN_RESOURCE = "/testplan/TradeMe_CategoryDetails_PerfTest.jmx";
    private static final String CATEGORY_IDS_RESOURCE = "/category_ids.csv";
    private static final String JMETER_PROPERTIES_RESOURCE = "/jmeter.properties";

    /** Runs the test once. Blocks until the JMeter engine has finished. */
    public RunOutput run(TestConfig config) throws IOException {
        // 1. Timestamped run directory (same naming scheme as run-test.ps1).
        String stamp = new SimpleDateFormat("yyyyMMdd_HHmmss").format(new Date());
        String label = config.runLabel() == null ? "" : config.runLabel().trim().replace(' ', '-');
        Path outputRoot = Path.of(config.outputRoot()).toAbsolutePath();
        Path runDir = outputRoot.resolve("run_" + stamp + (label.isBlank() ? "" : "_" + label));
        Files.createDirectories(runDir);

        // 2. Stage the test plan, test data and jmeter.properties from the classpath.
        Path embedDir = outputRoot.resolve(".jmeter-embed");
        Files.createDirectories(embedDir);
        Path testPlan = stageResource(TEST_PLAN_RESOURCE, embedDir.resolve("test-plan.jmx"));
        Path testDataDir = embedDir.resolve("testdata");
        Files.createDirectories(testDataDir);
        stageResource(CATEGORY_IDS_RESOURCE, testDataDir.resolve("category_ids.csv"));
        Path jmeterProperties = stageResource(JMETER_PROPERTIES_RESOURCE, embedDir.resolve("jmeter.properties"));

        // 3. Initialise the embedded engine with the same configuration the CLI uses.
        // NameUpdater resolves class-name upgrades from <JMETER_HOME>/bin/upgrade.properties
        // on the file system, so without a real JMeter installation we stage a minimal
        // synthetic home from the classpath-bundled properties files.
        Path jmeterHome;
        if (!config.jmeterHome().isBlank()) {
            jmeterHome = Path.of(config.jmeterHome());
        } else {
            Path syntheticHome = embedDir.resolve("jmeter-home");
            Path syntheticBin = syntheticHome.resolve("bin");
            Files.createDirectories(syntheticBin);
            stageResource("/upgrade.properties", syntheticBin.resolve("upgrade.properties"));
            stageResource("/saveservice.properties", syntheticBin.resolve("saveservice.properties"));
            jmeterHome = syntheticHome;
        }
        JMeterUtils.setJMeterHome(jmeterHome.toString());
        JMeterUtils.loadJMeterProperties(jmeterProperties.toString());
        JMeterUtils.setLocale(Locale.ENGLISH);
        JMeterUtils.initLocale();

        // 4. Push the execution parameters (equivalent of the -J CLI flags in run-test.ps1).
        JMeterUtils.setProperty("protocol", config.protocol());
        JMeterUtils.setProperty("host", config.host());
        if (!config.port().isBlank()) {
            JMeterUtils.setProperty("port", config.port());
        }
        JMeterUtils.setProperty("threads", Integer.toString(config.effectiveThreads()));
        JMeterUtils.setProperty("rampup", Integer.toString(config.effectiveRampUpSeconds()));
        JMeterUtils.setProperty("steady_state", Integer.toString(config.steadyStateSeconds()));
        JMeterUtils.setProperty("throughput", TestConfig.trimNumber(config.effectiveThroughputPerMinute()));
        JMeterUtils.setProperty("loops", Integer.toString(config.loopsPerVUser()));
        JMeterUtils.setProperty("outputDir", runDir.toString());
        JMeterUtils.setProperty("testDataPath", testDataDir.toString());
        JMeterUtils.setProperty("jmeter.save.saveservice.output_format", "csv");
        JMeterUtils.setProperty("jmeter.save.saveservice.print_field_names", "true");
        JMeterUtils.setProperty("jmeter.save.saveservice.assertion_results_failure_message", "true");

        System.out.println("==============================================================");
        System.out.println(" Trade Me Category Details - Performance Test (embedded JMeter "
                + JMeterUtils.getJMeterVersion() + ")");
        System.out.println("==============================================================");
        System.out.println(" Category IDs      : " + String.join(", ", config.categoryIds()));
        System.out.println(" VUsers (threads)  : " + config.effectiveThreads()
                + "   (NFR-01: half of " + config.categoryIds().size() + " IDs)");
        System.out.println(" Ramp-up           : " + config.effectiveRampUpSeconds() + " s  (NFR-02: "
                + TestConfig.trimNumber(config.vUsersPerSecond()) + " VUser per second)");
        System.out.println(" Steady state      : " + config.steadyStateSeconds() + " s");
        System.out.println(" Throughput target : " + TestConfig.trimNumber(config.effectiveThroughputPerMinute())
                + " calls/min in total (NFR-03)");
        System.out.println(" Loops per VUser   : " + config.loopsPerVUser()
                + "  (ceil(" + config.categoryIds().size() + " / " + config.effectiveThreads() + ") + 1 buffer)");
        System.out.println(" Results directory : " + runDir);
        System.out.println("--------------------------------------------------------------");

        // 5. Load the test plan and attach the results writer (what -l does on the CLI).
        HashTree testTree = SaveService.loadTree(testPlan.toFile());
        Summariser summariser = new Summariser("TradeMe-Load");
        ResultCollector resultWriter = new ResultCollector(summariser);
        Path resultsJtl = runDir.resolve("results.jtl");
        resultWriter.setFilename(resultsJtl.toString());
        SampleSaveConfiguration saveConfig = resultWriter.getSaveConfig();
        saveConfig.setAsXml(false);
        saveConfig.setFieldNames(true);
        saveConfig.setAssertionResultsFailureMessage(true);
        testTree.add(testTree.getArray()[0], resultWriter);

        // 6. Execute.
        StandardJMeterEngine engine = new StandardJMeterEngine();
        engine.configure(testTree);
        engine.run();

        // 7. Optional HTML dashboard (needs a real JMeter distribution for its templates).
        Path htmlReport = null;
        if (config.htmlReport() && !config.jmeterHome().isBlank()) {
            try {
                Path reportDir = runDir.resolve("html-report");
                Files.createDirectories(reportDir);
                // This property is what the CLI -o flag sets under the hood.
                JMeterUtils.setProperty("jmeter.reportgenerator.outputdir", reportDir.toString());
                ReportGenerator generator = new ReportGenerator(resultsJtl.toString(), null);
                generator.generate();
                htmlReport = reportDir;
                System.out.println("HTML dashboard written to " + reportDir);
            } catch (Exception e) {
                System.out.println("WARN: HTML dashboard generation failed (" + e.getMessage() + ").");
                System.out.println("      The raw results.jtl remains available; set trademe.jmeterHome");
                System.out.println("      to a JMeter 5.6.3 installation to enable the dashboard.");
            }
        } else if (config.htmlReport()) {
            System.out.println("INFO: HTML dashboard skipped - set -Dtrademe.jmeterHome=<JMeter install> to enable it.");
        }

        return new RunOutput(runDir, resultsJtl, runDir.resolve("category_promotions.csv"), htmlReport);
    }

    private static Path stageResource(String resource, Path target) throws IOException {
        try (InputStream in = JMeterRunner.class.getResourceAsStream(resource)) {
            if (in == null) {
                throw new IOException("Required classpath resource not found: " + resource);
            }
            Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
        }
        return target;
    }
}
