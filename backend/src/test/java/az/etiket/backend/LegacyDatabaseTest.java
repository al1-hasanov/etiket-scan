package az.etiket.backend;

import java.sql.DriverManager;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/** Exercise migration from a populated 0.1 cache, not just a fresh database. */
@SpringBootTest(properties = {"spring.datasource.url=jdbc:h2:mem:legacy-upgrade;DB_CLOSE_DELAY=-1",
    "off.enabled=true", "off.base-url=http://127.0.0.1:1"})
class LegacyDatabaseTest {
    static {
        try (var c = DriverManager.getConnection("jdbc:h2:mem:legacy-upgrade;DB_CLOSE_DELAY=-1", "sa", "")) {
            c.createStatement().execute("CREATE TABLE product_cache (barcode VARCHAR(14) PRIMARY KEY, payload TEXT NOT NULL, stored_at BIGINT NOT NULL)");
            String oldPayload = """
                {"barcode":"3017620422003","name":"Legacy cache fixture","brand":"Fixture","quantity":"100 g",
                "ingredients":"Oats","ingredientsLanguage":"en","nutriScore":"","nutrients":{},"allergens":[],"traces":[],
                "source":"OPEN_FOOD_FACTS","sourceUrl":"https://world.openfoodfacts.org/product/3017620422003",
                "retrievedAt":"2026-11-04T00:00:00Z","sourceUpdatedAt":"","demo":false}
                """;
            try (var insert = c.prepareStatement("INSERT INTO product_cache VALUES (?,?,?)")) {
                insert.setString(1, "3017620422003"); insert.setString(2, oldPayload);
                insert.setLong(3, System.currentTimeMillis()); insert.executeUpdate();
            }
        } catch (Exception e) { throw new ExceptionInInitializerError(e); }
    }
    @Autowired ProductService service;
    @Autowired CatalogueStore catalogue;
    @Autowired JdbcTemplate db;
    @Autowired org.flywaydb.core.Flyway flyway;

    @Test void populatedLegacyDatabaseGetsCatalogueWithoutLosingCache() {
        assertEquals(77, catalogue.summary().get("products"));
        assertEquals(1, db.queryForObject("SELECT COUNT(*) FROM product_cache", Integer.class));
        assertEquals("1", flyway.info().current().getVersion().toString());
    }
    @Test void oldProductJsonWithoutEvidenceRemainsReadable() {
        var p = service.lookup("3017620422003");
        assertEquals("Legacy cache fixture", p.name());
        assertEquals("COMMUNITY_UNVERIFIED", p.evidence().reviewStatus());
        assertFalse(p.evidence().labelPhotoChecked());
    }
}
