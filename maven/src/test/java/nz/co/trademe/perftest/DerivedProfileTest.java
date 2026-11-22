package nz.co.trademe.perftest;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Unit tests for the run-profile derivation rules - the Java port of section 1 of
 * {@code scripts/run-test.ps1} (NFR-01 / NFR-02 / NFR-03 defaults and the loop buffer).
 */
@DisplayName("Derived run profile (port of run-test.ps1 derivation)")
class DerivedProfileTest {

    private static final List<String> TEN_IDS = IntStream.rangeClosed(6327, 6336)
            .mapToObj(String::valueOf).toList();

    @Test
    @DisplayName("Assignment defaults: 10 IDs -> 5 VUsers, 5 s ramp-up, 10 calls/min, 3 loops")
    void assignmentDefaultsAreDerivedFromTheDataSet() {
        TestConfig config = TestConfig.forTesting(TEN_IDS);

        assertEquals(5, config.effectiveThreads(), "NFR-01: half of 10 IDs, rounded up");
        assertEquals(5, config.effectiveRampUpSeconds(), "NFR-02: one VUser per second");
        assertEquals(10.0, config.effectiveThroughputPerMinute(), "NFR-03: one call per ID per minute");
        assertEquals(3, config.loopsPerVUser(), "ceil(10 / 5) + 1 buffer loop");
    }

    @Test
    @DisplayName("Odd ID count rounds the VUser count up: 11 IDs -> 6 VUsers")
    void oddNumberOfCategoryIdsRoundsThreadsUp() {
        List<String> elevenIds = new java.util.ArrayList<>(TEN_IDS);
        elevenIds.add("6337");
        TestConfig config = TestConfig.forTesting(elevenIds);

        assertEquals(6, config.effectiveThreads());
        assertEquals(6, config.effectiveRampUpSeconds());
        assertEquals(11.0, config.effectiveThroughputPerMinute());
        assertEquals(3, config.loopsPerVUser(), "ceil(11 / 6) + 1 buffer loop");
    }

    @Test
    @DisplayName("Explicit overrides win over derived values")
    void explicitOverridesWinOverDerivedValues() {
        TestConfig config = TestConfig.builder(TEN_IDS)
                .threads(4)
                .rampUpSeconds(4)
                .throughputPerMinute(20)
                .build();

        assertEquals(4, config.effectiveThreads());
        assertEquals(4, config.effectiveRampUpSeconds());
        assertEquals(20.0, config.effectiveThroughputPerMinute());
        assertEquals(4, config.loopsPerVUser(), "ceil(10 / 4) + 1 buffer loop");
    }

    @Test
    @DisplayName("A custom ramp rate changes the ramp-up length but not the VUser count")
    void rampRateOnlyAffectsRampUp() {
        TestConfig config = TestConfig.builder(TEN_IDS)
                .vUsersPerSecond(2.0)
                .build();

        assertEquals(5, config.effectiveThreads());
        assertEquals(3, config.effectiveRampUpSeconds(), "ceil(5 / 2)");
    }
}
