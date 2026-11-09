package az.etiket.backend;

import az.etiket.core.NutritionLabel;
import az.etiket.core.Product;
import java.time.Duration;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/** Provider cache is separate from the starter and reviewed catalogues. */
@Component
final class OpenFoodFactsCatalogue {
    private final JdbcTemplate db;
    private final OpenFoodFactsClient source;
    private final boolean enabled;
    private final long cacheMillis;
    private final JsonMapper json = JsonMapper.builder().build();

    OpenFoodFactsCatalogue(JdbcTemplate db, OpenFoodFactsClient source, @Value("${off.cache-hours}") long hours,
            @Value("${off.enabled:false}") boolean enabled) {
        this.db = db;
        this.source = source;
        this.enabled = enabled;
        this.cacheMillis = Duration.ofHours(hours).toMillis();
    }

    boolean enabled() { return enabled; }

    Optional<OpenFoodFactsClient.Lookup> cached(String barcode) {
        if (!enabled) return Optional.empty();
        return stored(barcode, System.currentTimeMillis() - cacheMillis);
    }

    Optional<OpenFoodFactsClient.Lookup> lastReport(String barcode) {
        return stored(barcode, Long.MIN_VALUE);
    }

    private Optional<OpenFoodFactsClient.Lookup> stored(String barcode, long after) {
        var rows = db.query("SELECT payload FROM product_cache WHERE barcode=? AND stored_at>?",
            (rs, row) -> rs.getString(1), barcode, after);
        if (rows.isEmpty()) return Optional.empty();
        try {
            var tree = json.readTree(rows.getFirst());
            if (tree.has("product")) {
                var result = json.treeToValue(tree, OpenFoodFactsClient.Lookup.class);
                if (result.product() != null && barcode.equals(result.product().barcode())) return Optional.of(result);
            } else if (barcode.equals(tree.path("barcode").asString())) {
                // A v3.4 cache entry has useful identity data but an ambiguous nutrition basis.
                var product = json.treeToValue(tree, Product.class).withNutrition(NutritionLabel.empty(), null);
                return Optional.of(new OpenFoodFactsClient.Lookup(product, false));
            }
        } catch (RuntimeException ignored) {
            // Refresh malformed cached results on demand.
        }
        return Optional.empty();
    }

    OpenFoodFactsClient.Lookup lookup(String barcode) {
        if (!enabled) throw new LookupException(404, "PRODUCT_NOT_IN_CATALOGUE");
        return cached(barcode).orElseGet(() -> refresh(barcode));
    }

    private synchronized OpenFoodFactsClient.Lookup refresh(String barcode) {
        var existing = cached(barcode);
        if (existing.isPresent()) return existing.get();
        var result = source.fetch(barcode);
        String payload = json.writeValueAsString(result);
        long now = System.currentTimeMillis();
        int changed = db.update("UPDATE product_cache SET payload=?, stored_at=? WHERE barcode=?", payload, now, barcode);
        if (changed == 0) {
            try {
                db.update("INSERT INTO product_cache(barcode,payload,stored_at) VALUES (?,?,?)", barcode, payload, now);
            } catch (DuplicateKeyException otherInstance) {
                return cached(barcode).orElse(result);
            }
        }
        return result;
    }
}
