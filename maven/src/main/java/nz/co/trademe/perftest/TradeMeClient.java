package nz.co.trademe.perftest;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Minimal HTTP client for the Trade Me sandbox Categories Details API, used by the
 * pure-JUnit functional contract tests. Mirrors the request configured in the JMeter
 * test plan: {@code GET {protocol}://{host}/v1/Categories/{id}/Details.json?catalogue=false}.
 */
public final class TradeMeClient {

    /** Raw HTTP outcome plus the parsed document ({@code null} when the status is not 200). */
    public record CategoryResponse(int statusCode, CategoryDetails details, String parseError) {
    }

    private final HttpClient http;
    private final String baseUrl;
    private final Gson gson = new Gson();

    public TradeMeClient(TestConfig config) {
        StringBuilder url = new StringBuilder(config.protocol()).append("://").append(config.host());
        if (!config.port().isBlank()) {
            url.append(':').append(config.port());
        }
        this.baseUrl = url.toString();
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    public CategoryResponse getCategoryDetails(String categoryId) {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/v1/Categories/" + categoryId + "/Details.json?catalogue=false"))
                .timeout(Duration.ofSeconds(30))
                .header("Accept", "application/json")
                .header("User-Agent", "JUnit-PerformanceTest/1.0")
                .GET()
                .build();
        HttpResponse<String> response;
        try {
            response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw new IllegalStateException("Request failed for CategoryId=" + categoryId + ": " + e.getMessage(), e);
        }
        if (response.statusCode() != 200) {
            return new CategoryResponse(response.statusCode(), null, null);
        }
        try {
            return new CategoryResponse(200, parse(response.body()), null);
        } catch (RuntimeException e) {
            return new CategoryResponse(200, null, e.getMessage());
        }
    }

    private CategoryDetails parse(String body) {
        JsonObject root = JsonParser.parseString(body).getAsJsonObject();
        String categoryId = asText(root, "CategoryId");
        String name = asText(root, "Name");
        String path = asText(root, "Path");
        boolean canRelist = root.has("CanRelist")
                && root.get("CanRelist").isJsonPrimitive()
                && root.get("CanRelist").getAsBoolean();

        List<CategoryDetails.Promotion> promotions = new ArrayList<>();
        JsonArray array = root.has("Promotions") && root.get("Promotions").isJsonArray()
                ? root.getAsJsonArray("Promotions") : new JsonArray();
        for (JsonElement element : array) {
            JsonObject promotion = element.getAsJsonObject();
            promotions.add(new CategoryDetails.Promotion(asText(promotion, "Id"), asText(promotion, "Price")));
        }
        return new CategoryDetails(categoryId, name, path, canRelist, promotions);
    }

    private static String asText(JsonObject object, String field) {
        if (!object.has(field) || object.get(field).isJsonNull()) {
            return "";
        }
        JsonElement value = object.get(field);
        return value.isJsonPrimitive() ? value.getAsString() : value.toString();
    }

    @Override
    public String toString() {
        return "TradeMeClient[" + baseUrl + ']';
    }
}
