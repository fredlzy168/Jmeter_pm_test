package nz.co.trademe.perftest;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Unit tests for {@link JtlReader} against a deterministic fixture
 * ({@code fixtures/sample-results.jtl}) - no network, no engine.
 */
@DisplayName("JTL parsing and run statistics")
class JtlParserTest {

    @TempDir
    Path tempDir;

    /** Stages the classpath fixture as a real file for the parser. */
    private Path fixture() throws IOException {
        try (InputStream in = getClass().getResourceAsStream("/fixtures/sample-results.jtl")) {
            Path target = tempDir.resolve("sample-results.jtl");
            Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
            return target;
        }
    }

    @Test
    @DisplayName("Parses every row, including quoted failure messages containing commas")
    void parsesAllRowsOfTheFixture() throws IOException {
        List<JtlReader.SampleRecord> samples = JtlReader.parse(fixture());

        assertEquals(10, samples.size());
        JtlReader.SampleRecord third = samples.get(2);
        assertEquals(1200, third.timeStamp());
        assertEquals(300, third.elapsed());
        assertEquals("GET Category Details", third.label());
        assertEquals("200", third.responseCode());
        assertEquals("Category Details Load 1-1", third.threadName());
        assertEquals(false, third.success());
        assertEquals("CategoryId parameter check failed - expected 6327, but the API returned 6328",
                third.failureMessage(), "the failure message contains a comma and must survive RFC 4180 parsing");
    }

    @Test
    @DisplayName("Run statistics match the hand-computed fixture values")
    void summarisesTheFixture() throws IOException {
        JtlReader.RunStats stats = JtlReader.summarise(JtlReader.parse(fixture()));

        assertEquals(10, stats.totalCalls());
        assertEquals(9, stats.successCount());
        assertEquals(1, stats.failureCount());
        assertEquals(100, stats.minMs());
        assertEquals(550, stats.meanMs());
        assertEquals(500, stats.p50Ms(), "nearest-rank p50 of 100..1000 step 100");
        assertEquals(900, stats.p90Ms(), "nearest-rank p90: rank ceil(0.9 * 10) = 9");
        assertEquals(1000, stats.p95Ms(), "nearest-rank p95: rank ceil(0.95 * 10) = 10");
        assertEquals(1000, stats.p99Ms());
        assertEquals(1000, stats.maxMs());
        assertEquals(3, stats.distinctThreads());
        assertEquals(3, stats.maxConcurrentThreads());
        assertEquals(2.0, stats.rampSpreadSeconds(), "first samples at t=1000, 2000, 3000 ms");
        assertEquals(187.5, stats.observedCallsPerMinute(), "10 calls over a 3200 ms active window");
    }

    @Test
    @DisplayName("Percentile helper: empty input and edge ranks")
    void percentileNearestRankEdgeCases() {
        assertEquals(0, JtlReader.percentileNearestRank(new int[0], 90));
        assertEquals(7, JtlReader.percentileNearestRank(new int[] {7}, 90));
        assertEquals(9, JtlReader.percentileNearestRank(new int[] {1, 2, 3, 4, 5, 6, 7, 8, 9, 10}, 85),
                "rank ceil(0.85 * 10) = 9 -> value 9");
        assertEquals(10, JtlReader.percentileNearestRank(new int[] {1, 2, 3, 4, 5, 6, 7, 8, 9, 10}, 100));
    }

    @Test
    @DisplayName("CSV data-row counting skips the header and honours quoted fields")
    void countsPromotionsCsvRows() throws IOException {
        Path promotionsCsv = tempDir.resolve("sample-promotions.csv");
        Files.writeString(promotionsCsv, String.join("\n",
                "CategoryId,Name,Path,PromotionId,Price",
                "6327,Gaming,/Gaming,36789,14.99",
                "6328,\"Toys, Games & Fun\",/Toys-Games,36801,24.99",
                "6331,Auctions,,,"));
        assertEquals(3, JtlReader.countCsvDataRows(promotionsCsv));
    }
}
