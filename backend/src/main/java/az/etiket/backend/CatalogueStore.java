package az.etiket.backend;

import az.etiket.core.*;
import java.io.IOException;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.boot.sql.init.dependency.DependsOnDatabaseInitialization;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** The public catalogue combines reviewed submissions, CSV imports and the starter dataset. */
@Component
@DependsOnDatabaseInitialization
final class CatalogueStore implements InitializingBean {
    static final String DATASET = "bazarstore-starter";
    private static final String VISIBLE = """
        (SELECT barcode,name,has_ingredients,payload,TRUE AS label_reviewed,CAST(NULL AS TEXT) AS import_source
            FROM reviewed_products
        UNION ALL
        SELECT i.barcode,i.name,i.has_ingredients,i.payload,TRUE AS label_reviewed,i.source_record
            FROM imported_catalogue_products i
            WHERE NOT EXISTS (SELECT 1 FROM reviewed_products r WHERE r.barcode=i.barcode)
        UNION ALL
        SELECT c.barcode,c.name,c.has_ingredients,c.payload,FALSE AS label_reviewed,CAST(NULL AS TEXT)
            FROM catalogue_products c
            WHERE NOT EXISTS (SELECT 1 FROM reviewed_products r WHERE r.barcode=c.barcode)
              AND NOT EXISTS (SELECT 1 FROM imported_catalogue_products i WHERE i.barcode=c.barcode)
        ) visible_products
        """;
    private final JdbcTemplate db;
    private final TransactionTemplate transactions;
    private final JsonMapper json = JsonMapper.builder().build();

    CatalogueStore(JdbcTemplate db, TransactionTemplate transactions) {
        this.db = db; this.transactions = transactions;
    }

    @Override public void afterPropertiesSet() throws Exception { importBundledCatalogue(); }

    void importBundledCatalogue() throws IOException {
        byte[] bytes;
        try (var in = new ClassPathResource("catalogue/starter-catalogue.json").getInputStream()) {
            bytes = in.readAllBytes();
        }
        JsonNode root = json.readTree(bytes);
        if (root.path("schemaVersion").asInt() != 1 || !root.path("records").isArray())
            throw new IllegalStateException("Unsupported catalogue format");
        String digest;
        try { digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (Exception e) { throw new IllegalStateException(e); }
        List<Row> rows = new ArrayList<>();
        Set<String> codes = new HashSet<>();
        for (JsonNode record : root.path("records")) {
            Product p = json.treeToValue(record.path("product"), Product.class);
            if (!p.barcode().equals(Barcode.normalize(p.barcode())) || !codes.add(p.barcode())
                    || !p.barcode().equals(record.path("sourceRecord").path("barcode").asString())
                    || p.demo() || !"BAZARSTORE".equals(p.source())
                    || p.evidence().labelPhotoChecked()
                    || !"WEB_SOURCED_UNVERIFIED".equals(p.evidence().reviewStatus()))
                throw new IllegalStateException("Invalid or mislabelled starter record");
            rows.add(new Row(p, json.writeValueAsString(p), json.writeValueAsString(record.path("sourceRecord"))));
        }
        if (rows.isEmpty()) throw new IllegalStateException("Catalogue is empty");
        transactions.executeWithoutResult(status -> {
            String previous = lockCatalogueImports();
            Integer existing = db.queryForObject("SELECT COUNT(*) FROM catalogue_products WHERE dataset_id=?", Integer.class, DATASET);
            if (digest.equals(previous) && existing != null && existing == rows.size()) return;
            db.update("DELETE FROM catalogue_products WHERE dataset_id=?", DATASET);
            for (Row row : rows) {
                Product p = row.product();
                db.update("INSERT INTO catalogue_products(barcode,dataset_id,name,brand,has_ingredients,payload,source_record) "
                    + "VALUES (?,?,?,?,?,?,?)", p.barcode(), DATASET, p.name(), p.brand(), !p.ingredients().isBlank(), row.payload(), row.sourceRecord());
            }
            db.update("UPDATE catalogue_imports SET digest=?,record_count=?,imported_at=? WHERE dataset_id=?",
                digest, rows.size(), Instant.now().toString(), DATASET);
        });
    }

    Optional<Product> find(String code) {
        return db.query("SELECT payload FROM " + VISIBLE + " WHERE barcode=?",
            (rs, row) -> json.readValue(rs.getString(1), Product.class), code).stream().findFirst();
    }

    // The starter and CSV publishers share this lock. Call inside the publishing transaction.
    String lockCatalogueImports() {
        return db.queryForObject("SELECT digest FROM catalogue_imports WHERE dataset_id=? FOR UPDATE", String.class, DATASET);
    }

    Optional<ExistingProduct> existingForImport(String barcode) {
        return db.query("SELECT import_source FROM " + VISIBLE + " WHERE barcode=?",
            (rs, row) -> new ExistingProduct(rs.getString(1)), barcode).stream().findFirst();
    }

    void insertImported(Product product, String sourceRecord) {
        db.update("INSERT INTO imported_catalogue_products(barcode,name,has_ingredients,payload,source_record) VALUES (?,?,?,?,?)",
            product.barcode(), product.name(), !product.ingredients().isBlank(), json.writeValueAsString(product), sourceRecord);
    }

    Map<String, Object> summary() {
        var counts = db.queryForMap("SELECT COUNT(*) AS total, COUNT(CASE WHEN has_ingredients THEN 1 END) AS with_ingredients, "
            + "COUNT(CASE WHEN label_reviewed THEN 1 END) AS verified FROM " + VISIBLE);
        int total = ((Number) counts.get("total")).intValue();
        int ingredients = ((Number) counts.get("with_ingredients")).intValue();
        int verified = ((Number) counts.get("verified")).intValue();
        return Map.of("products", total, "withIngredients", ingredients, "missingIngredients", total - ingredients,
            "labelVerified", verified, "reviewStatus", verified == 0 ? "WEB_SOURCED_UNVERIFIED" : "MIXED_EVIDENCE", "dataset", DATASET);
    }

    Map<String, Object> page(int page, int size) {
        if (page < 0 || page > 100000 || size < 1 || size > 100)
            throw new InvalidCataloguePageException();
        List<Product> products = db.query("SELECT payload FROM " + VISIBLE + " ORDER BY name,barcode LIMIT ? OFFSET ?",
            (rs, row) -> json.readValue(rs.getString(1), Product.class), size, (long) page * size);
        return Map.of("items", products, "page", page, "size", size, "total", summary().get("products"));
    }

    record ExistingProduct(String importSource) { }
    private record Row(Product product, String payload, String sourceRecord) { }
}

final class InvalidCataloguePageException extends RuntimeException { }
