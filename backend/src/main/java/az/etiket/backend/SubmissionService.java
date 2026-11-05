package az.etiket.backend;

import az.etiket.core.*;
import static az.etiket.backend.SubmissionModels.*;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.Semaphore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;
import tools.jackson.databind.json.JsonMapper;

@Service
final class SubmissionService {
    static final List<String> ROLES = List.of("front", "barcode", "ingredients");
    private final JdbcTemplate db;
    private final TransactionTemplate tx;
    private final LabelPhotos images;
    private final int maximum, pendingMaximum;
    private final boolean configured;
    private final Semaphore processing = new Semaphore(2);
    private final JsonMapper json = JsonMapper.builder().build();

    SubmissionService(JdbcTemplate db, TransactionTemplate tx, LabelPhotos images,
            @Value("${review.password:}") String password, @Value("${submissions.max-total:100}") int maximum,
            @Value("${submissions.max-pending:25}") int pendingMaximum) {
        this.db = db; this.tx = tx; this.images = images; this.maximum = maximum;
        this.pendingMaximum = pendingMaximum; this.configured = !password.isBlank();
    }

    Receipt create(Draft input, Map<String, MultipartFile> files) {
        if (!configured) throw new SubmissionException(503, "REVIEW_NOT_CONFIGURED");
        Draft draft = validate(input);
        if (!processing.tryAcquire()) throw new SubmissionException(429, "SUBMISSION_BUSY");
        try {
            Map<String, LabelPhotos.Stored> photos = new LinkedHashMap<>();
            for (String role : ROLES) photos.put(role, images.sanitize(files.get(role)));
            String submitted = json.writeValueAsString(draft);
            String digest = LabelPhotos.sha256((submitted + photos.values().stream().map(LabelPhotos.Stored::sha256)
                .reduce("", String::concat)).getBytes(StandardCharsets.UTF_8));
            return tx.execute(status -> {
                db.queryForObject("SELECT id FROM submission_guard WHERE id=1 FOR UPDATE", Integer.class);
                var previous = rows(draft.clientRequestId());
                if (!previous.isEmpty()) {
                    Row existing = previous.getFirst();
                    if (!digest.equals(existing.fingerprint())) throw new SubmissionException(409, "SUBMISSION_ID_CONFLICT");
                    return receipt(existing);
                }
                int total = db.queryForObject("SELECT COUNT(*) FROM label_submissions", Integer.class);
                int pending = db.queryForObject("SELECT COUNT(*) FROM label_submissions WHERE status='PENDING'", Integer.class);
                if (total >= maximum || pending >= pendingMaximum) throw new SubmissionException(429, "SUBMISSION_QUEUE_FULL");
                long now = System.currentTimeMillis();
                db.update("INSERT INTO label_submissions(id,barcode,submitted_payload,fingerprint,status,submitted_at) VALUES (?,?,?,?,'PENDING',?)",
                    draft.clientRequestId(), draft.barcode(), submitted, digest, now);
                for (var photo : photos.entrySet()) {
                    var p = photo.getValue();
                    db.update("INSERT INTO submission_photos(submission_id,role,photo_data,sha256,width,height) VALUES (?,?,?,?,?,?)",
                        draft.clientRequestId(), photo.getKey(), p.bytes(), p.sha256(), p.width(), p.height());
                }
                return new Receipt(draft.clientRequestId(), draft.barcode(), "PENDING", iso(now), "", "");
            });
        } finally { processing.release(); }
    }

    Receipt status(String id) { return receipt(row(id)); }

    Map<String, Object> queue(String status, int page) {
        if (!Set.of("PENDING", "APPROVED", "REJECTED").contains(status) || page < 0 || page > 10000)
            throw new SubmissionException(400, "REVIEW_REQUEST_INVALID");
        var items = db.query("SELECT * FROM label_submissions WHERE status=? ORDER BY submitted_at,id LIMIT 25 OFFSET ?",
            (rs, i) -> receipt(map(rs)), status, page * 25);
        return Map.of("items", items, "page", page, "total",
            db.queryForObject("SELECT COUNT(*) FROM label_submissions WHERE status=?", Integer.class, status));
    }

    Detail detail(String id) {
        Row row = row(id);
        return new Detail(receipt(row), row.version(), json.readValue(row.submitted(), Draft.class),
            photoMetadata(id, true), publishedId(row.barcode()), row.reviewed() == null ? null : json.readValue(row.reviewed(), Product.class));
    }

    Product approve(String id, Approval input, String reviewer) {
        id = id(id);
        if (input == null || !input.photosChecked()) throw new SubmissionException(400, "PHOTO_REVIEW_REQUIRED");
        String name = text(input.name(), 200, true), brand = text(input.brand(), 200, false);
        String quantity = text(input.quantity(), 80, true), ingredients = text(input.ingredients(), 10000, true);
        String language = language(input.ingredientsLanguage());
        String allergen = text(input.declaredAllergenStatement(), 2000, false);
        String expected = input.expectedPublishedSubmissionId() == null ? "" : input.expectedPublishedSubmissionId();
        final String submissionId = id;
        try {
            return tx.execute(status -> {
                Row row = locked(submissionId);
                pending(row, input.version());
                var existing = db.query("SELECT submission_id FROM reviewed_products WHERE barcode=? FOR UPDATE",
                    (rs, i) -> rs.getString(1), row.barcode());
                String actual = existing.isEmpty() ? "" : existing.getFirst();
                if (!actual.equals(expected)) throw new SubmissionException(409, "PUBLISHED_PRODUCT_CHANGED");
                long now = System.currentTimeMillis();
                String source = "/sources/labels/" + submissionId;
                var evidence = new ProductEvidence("LABEL_PHOTO_REVIEWED", "LABEL_PHOTO_REVIEWED", true,
                    "/api/v1/labels/" + submissionId + "/photos/ingredients", ingredients, allergen,
                    List.of("Compared by a reviewer with submitted package photos; check the current package for changes."), false);
                Product product = new Product(row.barcode(), name, brand, quantity, ingredients, language, "",
                    Map.of(), List.of(), List.of(), "REVIEWED_LABEL", source, iso(row.submittedAt()), iso(now), false, evidence);
                String payload = json.writeValueAsString(product);
                if (actual.isEmpty()) db.update("INSERT INTO reviewed_products(barcode,name,has_ingredients,payload,submission_id) VALUES (?,?,TRUE,?,?)",
                    row.barcode(), name, payload, submissionId);
                else db.update("UPDATE reviewed_products SET name=?,has_ingredients=TRUE,payload=?,submission_id=? WHERE barcode=?",
                    name, payload, submissionId, row.barcode());
                db.update("UPDATE label_submissions SET status='APPROVED',version=version+1,reviewed_at=?,reviewer=?,reviewed_payload=? WHERE id=?",
                    now, reviewer, payload, submissionId);
                return product;
            });
        } catch (DataIntegrityViolationException e) { throw new SubmissionException(409, "PUBLISHED_PRODUCT_CHANGED"); }
    }

    Receipt reject(String id, Rejection input, String reviewer) {
        String submissionId = id(id);
        if (input == null) throw new SubmissionException(400, "REVIEW_REQUEST_INVALID");
        String note = text(input.note(), 1000, true);
        return tx.execute(status -> {
            Row row = locked(submissionId); pending(row, input.version());
            long now = System.currentTimeMillis();
            db.update("UPDATE label_submissions SET status='REJECTED',version=version+1,reviewed_at=?,reviewer=?,decision_note=? WHERE id=?",
                now, reviewer, note, submissionId);
            return new Receipt(submissionId, row.barcode(), "REJECTED", iso(row.submittedAt()), iso(now), note);
        });
    }

    Optional<Product> findReviewed(String barcode) {
        return db.query("SELECT payload FROM reviewed_products WHERE barcode=?",
            (rs, i) -> json.readValue(rs.getString(1), Product.class), barcode).stream().findFirst();
    }

    PublicLabel publicLabel(String id) {
        Row row = row(id);
        if (!row.status().equals("APPROVED")) throw new SubmissionException(404, "LABEL_NOT_FOUND");
        return new PublicLabel(json.readValue(row.reviewed(), Product.class), iso(row.reviewedAt()), photoMetadata(id, false));
    }

    byte[] photo(String id, String role, boolean reviewer) {
        id = id(id);
        if (!ROLES.contains(role)) throw new SubmissionException(404, "PHOTO_NOT_FOUND");
        if (!reviewer && !row(id).status().equals("APPROVED")) throw new SubmissionException(404, "PHOTO_NOT_FOUND");
        return db.query("SELECT photo_data FROM submission_photos WHERE submission_id=? AND role=?",
            (rs, i) -> rs.getBytes(1), id, role).stream().findFirst().orElseThrow(() -> new SubmissionException(404, "PHOTO_NOT_FOUND"));
    }

    private List<Photo> photoMetadata(String id, boolean reviewer) {
        String prefix = reviewer ? "/api/v1/review/submissions/" : "/api/v1/labels/";
        return db.query("SELECT role,width,height,sha256 FROM submission_photos WHERE submission_id=? ORDER BY role",
            (rs, i) -> new Photo(rs.getString(1), prefix + id + "/photos/" + rs.getString(1), rs.getInt(2), rs.getInt(3), rs.getString(4)), id);
    }

    private String publishedId(String barcode) {
        return db.query("SELECT submission_id FROM reviewed_products WHERE barcode=?", (rs, i) -> rs.getString(1), barcode)
            .stream().findFirst().orElse("");
    }
    private Row row(String id) { return rows(id(id)).stream().findFirst().orElseThrow(() -> new SubmissionException(404, "SUBMISSION_NOT_FOUND")); }
    private List<Row> rows(String id) { return db.query("SELECT * FROM label_submissions WHERE id=?", (rs, i) -> map(rs), id); }
    private Row locked(String id) {
        return db.query("SELECT * FROM label_submissions WHERE id=? FOR UPDATE", (rs, i) -> map(rs), id)
            .stream().findFirst().orElseThrow(() -> new SubmissionException(404, "SUBMISSION_NOT_FOUND"));
    }
    private Row map(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new Row(rs.getString("id"), rs.getString("barcode"), rs.getString("submitted_payload"), rs.getString("fingerprint"),
            rs.getString("status"), rs.getInt("version"), rs.getLong("submitted_at"), rs.getLong("reviewed_at"),
            rs.getString("decision_note"), rs.getString("reviewed_payload"));
    }
    private record Row(String id, String barcode, String submitted, String fingerprint, String status, int version,
        long submittedAt, long reviewedAt, String note, String reviewed) { }
    private Receipt receipt(Row row) {
        return new Receipt(row.id(), row.barcode(), row.status(), iso(row.submittedAt()),
            row.reviewedAt() == 0 ? "" : iso(row.reviewedAt()), row.note());
    }
    private void pending(Row row, int version) {
        if (!row.status().equals("PENDING") || row.version() != version) throw new SubmissionException(409, "SUBMISSION_ALREADY_REVIEWED");
    }
    private static String iso(long value) { return Instant.ofEpochMilli(value).toString(); }
    private static String id(String value) {
        try {
            String canonical = UUID.fromString(value).toString();
            if (!canonical.equals(value)) throw new IllegalArgumentException();
            return canonical;
        } catch (RuntimeException e) { throw new SubmissionException(400, "SUBMISSION_ID_INVALID"); }
    }
    private static String text(String value, int maximum, boolean required) {
        String clean = value == null ? "" : value.strip();
        if (clean.length() > maximum || required && clean.isEmpty() || clean.chars().anyMatch(c -> c < 32 && c != '\n' && c != '\r' && c != '\t'))
            throw new SubmissionException(400, "SUBMISSION_FIELDS_INVALID");
        return clean;
    }
    private static String language(String value) {
        String clean = text(value, 12, true);
        if (!clean.matches("[a-z]{2,3}(-[A-Z]{2})?")) throw new SubmissionException(400, "INGREDIENT_LANGUAGE_INVALID");
        return clean;
    }
    private Draft validate(Draft draft) {
        if (draft == null || !draft.consentToPublish()) throw new SubmissionException(400, "PUBLICATION_CONSENT_REQUIRED");
        String barcode;
        try { barcode = Barcode.normalize(draft.barcode()); }
        catch (RuntimeException e) { throw new SubmissionException(400, "BARCODE_INVALID"); }
        if (DemoCatalog.products().stream().anyMatch(p -> p.barcode().equals(barcode))) throw new SubmissionException(400, "DEMO_SUBMISSION_NOT_ALLOWED");
        return new Draft(id(draft.clientRequestId()), barcode, text(draft.name(), 200, true), text(draft.brand(), 200, false),
            text(draft.quantity(), 80, false), text(draft.ingredients(), 10000, false), language(draft.ingredientsLanguage()),
            text(draft.declaredAllergenStatement(), 2000, false), true);
    }
}
