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

/** Persistent, source-isolated positive/negative cache. Reviewed labels are never stored here. */
@Component
final class OpenFoodFactsCatalogue {
    private final JdbcTemplate db;
    private final OpenFoodFactsClient source;
    private final boolean enabled;
    private final long cacheMillis, missMillis, staleMillis;
    private final JsonMapper json = JsonMapper.builder().build();

    OpenFoodFactsCatalogue(JdbcTemplate db, OpenFoodFactsClient source,
            @Value("${off.cache-hours:24}") long hours,
            @Value("${off.enabled:false}") boolean enabled,
            @Value("${off.miss-cache-hours:6}") long missHours,
            @Value("${off.stale-cache-days:7}") long staleDays) {
        if (hours < 1 || missHours < 1 || staleDays < 0)
            throw new IllegalArgumentException("Invalid OFF cache configuration");
        this.db = db;
        this.source = source;
        this.enabled = enabled;
        cacheMillis = Duration.ofHours(hours).toMillis();
        missMillis = Duration.ofHours(missHours).toMillis();
        staleMillis = Duration.ofDays(staleDays).toMillis();
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
                // Older 0.3 cache entries have no reliable nutrition basis.
                var product = json.treeToValue(tree, Product.class).withNutrition(NutritionLabel.empty(), null);
                return Optional.of(new OpenFoodFactsClient.Lookup(product, false));
            }
        } catch (RuntimeException ignored) {
            // Malformed cache entries are refreshed, never surfaced to users.
        }
        return Optional.empty();
    }

    private boolean recentlyMissing(String barcode) {
        return !db.query("SELECT checked_at FROM product_lookup_misses WHERE barcode=? AND checked_at>?",
            (rs, row) -> rs.getLong(1), barcode, System.currentTimeMillis() - missMillis).isEmpty();
    }

    OpenFoodFactsClient.Lookup lookup(String barcode) {
        if (!enabled) throw new LookupException(404, "PRODUCT_NOT_IN_CATALOGUE");
        var hit = cached(barcode);
        if (hit.isPresent()) return hit.get();
        if (recentlyMissing(barcode)) throw new LookupException(404, "PRODUCT_NOT_FOUND");
        return refresh(barcode);
    }

    /** Single-flight for concurrent requests in this JVM; persistence survives server restarts. */
    private synchronized OpenFoodFactsClient.Lookup refresh(String barcode) {
        var hit = cached(barcode);
        if (hit.isPresent()) return hit.get();
        if (recentlyMissing(barcode)) throw new LookupException(404, "PRODUCT_NOT_FOUND");
        // Only use *bounded* stale data after temporary failures, never after confirmed 404.
        var stale = staleMillis == 0 ? Optional.<OpenFoodFactsClient.Lookup>empty()
            : stored(barcode, System.currentTimeMillis() - staleMillis);
        OpenFoodFactsClient.Lookup result;
        try {
            result = source.fetch(barcode);
        } catch (LookupException error) {
            if (error.status == 404) {
                rememberMissing(barcode);
                db.update("DELETE FROM product_cache WHERE barcode=?", barcode);
            } else if ((error.status == 429 || error.status == 503) && stale.isPresent()) {
                return stale.get();
            }
            throw error;
        }
        String payload = json.writeValueAsString(result);
        long now = System.currentTimeMillis();
        int changed = db.update("UPDATE product_cache SET payload=?, stored_at=? WHERE barcode=?", payload, now, barcode);
        if (changed == 0) {
            try {
                db.update("INSERT INTO product_cache(barcode,payload,stored_at) VALUES (?,?,?)", barcode, payload, now);
            } catch (DuplicateKeyException otherInstance) {
                // Another server instance may have written the same verified-barcode response.
                return cached(barcode).orElse(result);
            }
        }
        db.update("DELETE FROM product_lookup_misses WHERE barcode=?", barcode);
        return result;
    }

    private void rememberMissing(String barcode) {
        long now = System.currentTimeMillis();
        if (db.update("UPDATE product_lookup_misses SET checked_at=? WHERE barcode=?", now, barcode) == 0) {
            try {
                db.update("INSERT INTO product_lookup_misses(barcode, checked_at) VALUES (?,?)", barcode, now);
            } catch (DuplicateKeyException anotherInstance) {
                db.update("UPDATE product_lookup_misses SET checked_at=? WHERE barcode=?", now, barcode);
            }
        }
    }
}
