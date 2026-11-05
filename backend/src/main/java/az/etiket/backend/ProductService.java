package az.etiket.backend;

import az.etiket.core.*;
import java.time.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;

@Service
final class ProductService {
    private final JdbcTemplate db;
    private final OpenFoodFactsClient source;
    private final CatalogueStore catalogue;
    private final SubmissionService submissions;
    private final boolean externalEnabled;
    private final long cacheMillis;
    private final JsonMapper json = JsonMapper.builder().build();
    ProductService(JdbcTemplate db, OpenFoodFactsClient source, CatalogueStore catalogue, SubmissionService submissions,
            @Value("${off.cache-hours}") long hours, @Value("${off.enabled:false}") boolean externalEnabled) {
        this.db = db; this.source = source; this.catalogue = catalogue;
        this.submissions = submissions;
        this.externalEnabled = externalEnabled; cacheMillis = Duration.ofHours(hours).toMillis();
    }
    synchronized Product lookup(String input) {
        String code = Barcode.normalize(input);
        for (Product demo : DemoCatalog.products()) if (demo.barcode().equals(code)) return demo;
        var reviewed = submissions.findReviewed(code);
        if (reviewed.isPresent()) return reviewed.get();
        var local = catalogue.find(code);
        if (local.isPresent()) return local.get();
        if (!externalEnabled) throw new LookupException(404, "PRODUCT_NOT_IN_CATALOGUE");
        var cached = db.query("SELECT payload FROM product_cache WHERE barcode=? AND stored_at>?",
            (rs, row) -> rs.getString(1), code, System.currentTimeMillis() - cacheMillis);
        if (!cached.isEmpty()) {
            try { return json.readValue(cached.getFirst(), Product.class); }
            catch (RuntimeException ignored) { db.update("DELETE FROM product_cache WHERE barcode=?", code); }
        }
        Product product = source.fetch(code);
        String payload = json.writeValueAsString(product);
        long now = System.currentTimeMillis();
        int updated = db.update("UPDATE product_cache SET payload=?, stored_at=? WHERE barcode=?", payload, now, code);
        if (updated == 0) db.update("INSERT INTO product_cache(barcode,payload,stored_at) VALUES (?,?,?)", code, payload, now);
        return product;
    }
}
