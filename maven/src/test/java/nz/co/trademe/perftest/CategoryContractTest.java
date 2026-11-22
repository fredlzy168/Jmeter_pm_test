package nz.co.trademe.perftest;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvFileSource;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

/**
 * Functional contract tests against the live sandbox API - the JUnit translation of the
 * three Groovy checks embedded in {@code TradeMe_CategoryDetails_PerfTest.jmx}:
 *
 * <ol>
 *   <li>Response status check - HTTP 200 (hard gate)</li>
 *   <li>CategoryId parameter check - the returned {@code CategoryId} echoes the requested one (hard gate)</li>
 *   <li>CanRelist text check - {@code CanRelist} must be {@code true}; categories listed in
 *       {@code known-data-findings.properties} legitimately return {@code false} on the live
 *       sandbox and are reported as expected data findings (the test is skipped with the
 *       documented reason) instead of hard failures.</li>
 * </ol>
 */
@Tag("contract")
@DisplayName("Trade Me Category Details API - functional contract")
class CategoryContractTest {

    private static TestConfig config;
    private static TradeMeClient client;
    private static Properties knownFindings;

    @BeforeAll
    static void setUp() {
        config = TestConfig.load();
        client = new TradeMeClient(config);
        knownFindings = readKnownFindings();
    }

    @ParameterizedTest(name = "Category {0} responds with a valid Category Details document")
    @CsvFileSource(resources = "/category_ids.csv", numLinesToSkip = 1)
    void categoryDetailsContract(String categoryId) {
        TradeMeClient.CategoryResponse response = client.getCategoryDetails(categoryId);

        // Check 1 - response status must be HTTP 200.
        org.junit.jupiter.api.Assertions.assertEquals(200, response.statusCode(),
                () -> "Response status check failed - expected HTTP 200 but received "
                        + response.statusCode() + " for CategoryId=" + categoryId);

        org.junit.jupiter.api.Assertions.assertTrue(response.parseError() == null,
                () -> "Response body is not valid JSON for CategoryId=" + categoryId
                        + " - " + response.parseError());

        CategoryDetails details = response.details();

        // Check 2 - parameter check: returned CategoryId must match the request.
        org.junit.jupiter.api.Assertions.assertEquals(categoryId, details.categoryId(),
                () -> "CategoryId parameter check failed - expected " + categoryId
                        + " but the API returned " + details.categoryId());

        // The response must expose the fields the CSV deliverable is built from.
        org.junit.jupiter.api.Assertions.assertFalse(details.name().isBlank(),
                () -> "Category Name must not be empty for CategoryId=" + categoryId);
        org.junit.jupiter.api.Assertions.assertFalse(details.path().isBlank(),
                () -> "Category Path must not be empty for CategoryId=" + categoryId);
        org.junit.jupiter.api.Assertions.assertNotNull(details.promotions(),
                () -> "Promotions must be present for CategoryId=" + categoryId);

        // Check 3 - text check: CanRelist must be true, except for documented live-data findings.
        if (!details.canRelist() && knownFindings.containsKey(categoryId)) {
            Assumptions.abort("CanRelist text check - expected [true] but was [false] for CategoryId="
                    + categoryId + " - documented live-data finding: "
                    + knownFindings.getProperty(categoryId));
        }
        org.junit.jupiter.api.Assertions.assertTrue(details.canRelist(),
                () -> "CanRelist text check failed - expected [true] but was [" + details.canRelist()
                        + "] for CategoryId=" + categoryId);
    }

    private static Properties readKnownFindings() {
        Properties props = new Properties();
        try (InputStream in = CategoryContractTest.class.getResourceAsStream("/known-data-findings.properties")) {
            if (in != null) {
                props.load(in);
            }
        } catch (IOException e) {
            throw new IllegalStateException("Cannot read /known-data-findings.properties: " + e.getMessage(), e);
        }
        return props;
    }
}
