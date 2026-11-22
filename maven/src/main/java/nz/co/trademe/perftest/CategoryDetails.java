package nz.co.trademe.perftest;

import java.util.List;

/**
 * Canonical view of one {@code GET /v1/Categories/{id}/Details.json} response, mirroring
 * the fields used by the Groovy assertions inside the JMeter test plan.
 */
public record CategoryDetails(String categoryId, String name, String path, boolean canRelist,
                              List<Promotion> promotions) {

    /** One entry of the response's {@code Promotions} array (CSV deliverable fields). */
    public record Promotion(String id, String price) {
    }
}
