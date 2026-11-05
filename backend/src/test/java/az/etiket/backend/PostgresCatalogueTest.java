package az.etiket.backend;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** Real temporary PostgreSQL process; no Docker and no production services. */
@SpringBootTest
class PostgresCatalogueTest {
    static final EmbeddedPostgres postgres;
    static {
        try {
            postgres = EmbeddedPostgres.builder().setPort(0)
                .setServerConfig("listen_addresses", "127.0.0.1")
                .setServerConfig("unix_socket_directories", "").start();
        }
        catch (Exception e) { throw new ExceptionInInitializerError(e); }
    }
    @DynamicPropertySource static void database(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", () -> postgres.getJdbcUrl("postgres", "postgres"));
        r.add("spring.datasource.username", () -> "postgres");
        r.add("spring.datasource.password", () -> "");
    }
    @Autowired CatalogueStore catalogue;
    @Autowired ProductService products;
    @Autowired JdbcTemplate db;
    @AfterAll static void stop() throws Exception { postgres.close(); }

    @Test void migrationsAndIdempotentImportWorkOnPostgres() throws Exception {
        assertTrue(db.queryForObject("SELECT version()", String.class).startsWith("PostgreSQL"));
        assertEquals(77, catalogue.summary().get("products"));
        assertEquals(10, catalogue.summary().get("withIngredients"));
        catalogue.importBundledCatalogue();
        assertEquals(77, db.queryForObject("SELECT COUNT(*) FROM catalogue_products", Integer.class));
        assertEquals(1, db.queryForObject("SELECT COUNT(*) FROM flyway_schema_history WHERE success=TRUE", Integer.class));
    }
    @Test void unicodeAndPartialProductSurviveDatabaseRoundTrip() {
        assertTrue(products.lookup("4760177560085").ingredients().contains("inək südü"));
        var missing = products.lookup("5201360553457");
        assertEquals("MISSING", missing.evidence().ingredientsStatus());
        assertEquals("", missing.ingredients());
        assertTrue(missing.evidence().externalCandidateAvailable());
    }
}
