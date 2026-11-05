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
@SpringBootTest(properties={"review.password=only-for-local-tests"})
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
    @Autowired SubmissionService submissions;
    @AfterAll static void stop() throws Exception { postgres.close(); }

    @Test void migrationsAndIdempotentImportWorkOnPostgres() throws Exception {
        assertTrue(db.queryForObject("SELECT version()", String.class).startsWith("PostgreSQL"));
        assertEquals(77, catalogue.summary().get("products"));
        assertEquals(10, catalogue.summary().get("withIngredients"));
        catalogue.importBundledCatalogue();
        assertEquals(77, db.queryForObject("SELECT COUNT(*) FROM catalogue_products", Integer.class));
        assertEquals(2, db.queryForObject("SELECT COUNT(*) FROM flyway_schema_history WHERE success=TRUE", Integer.class));
    }
    @Test void unicodeAndPartialProductSurviveDatabaseRoundTrip() {
        assertTrue(products.lookup("4760177560085").ingredients().contains("inək südü"));
        var missing = products.lookup("5201360553457");
        assertEquals("MISSING", missing.evidence().ingredientsStatus());
        assertEquals("", missing.ingredients());
        assertTrue(missing.evidence().externalCandidateAvailable());
    }
    @Test void reviewedProductsAndPhotosSurviveSeedReimportOnPostgres() throws Exception {
        String id=null;
        try {
            var receipt=submissions.create(SubmissionFixtures.draft("3017620422003"),SubmissionFixtures.photos());id=receipt.id();
            byte[] before=submissions.photo(id,"ingredients",true);
            submissions.approve(id,SubmissionFixtures.approval(0,""),"test-reviewer");
            catalogue.importBundledCatalogue();
            assertArrayEquals(before,submissions.photo(id,"ingredients",false));
            assertEquals("REVIEWED_LABEL",products.lookup("3017620422003").source());
            assertEquals(78,catalogue.summary().get("products"));assertEquals(1,catalogue.summary().get("labelVerified"));
        } finally {
            if(id!=null){db.update("DELETE FROM reviewed_products WHERE submission_id=?",id);db.update("DELETE FROM submission_photos WHERE submission_id=?",id);db.update("DELETE FROM label_submissions WHERE id=?",id);}
        }
    }
}
