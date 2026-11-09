package az.etiket.backend;

import az.etiket.core.Barcode;
import az.etiket.core.NutritionLabel;
import az.etiket.core.NutritionSource;
import az.etiket.core.Product;
import java.net.URI;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

/** Store the latest report from each feed without changing the package-reviewed product. */
@Service
final class NutritionFeeds {
    record Feed(String sourceId, NutritionSource.Kind kind, String name, String observedAt, List<Input> records) { }
    record Input(String barcode, String packSize, String sourceUrl, NutritionLabel nutrition) { }
    record Conflict(String barcode, String reason) { }
    record Result(boolean applied, int records, List<Conflict> conflicts) { }
    record Report(String sourceId, NutritionLabel nutrition, NutritionSource source) { }

    private final JdbcTemplate db;
    private final TransactionTemplate transactions;
    private final CatalogueStore catalogue;
    private final OpenFoodFactsCatalogue external;
    private final JsonMapper json = JsonMapper.builder().build();

    NutritionFeeds(JdbcTemplate db, TransactionTemplate transactions, CatalogueStore catalogue, OpenFoodFactsCatalogue external) {
        this.db = db;
        this.transactions = transactions;
        this.catalogue = catalogue;
        this.external = external;
    }

    Result importFeed(Feed feed, boolean dryRun) {
        if (feed == null || feed.sourceId() == null || !feed.sourceId().matches("[a-z0-9][a-z0-9-]{0,39}")
                || (feed.kind() != NutritionSource.Kind.MANUFACTURER_REPORTED && feed.kind() != NutritionSource.Kind.RETAILER_REPORTED)
                || feed.records() == null || feed.records().isEmpty() || feed.records().size() > 500) throw invalid();
        String name = required(feed.name(), 80);
        Instant observed;
        try { observed = Instant.parse(required(feed.observedAt(), 40)); }
        catch (DateTimeParseException error) { throw invalid(); }
        if (observed.isBefore(Instant.EPOCH) || observed.isAfter(Instant.now().plusSeconds(300))) throw invalid();
        Map<String, Report> batch = new LinkedHashMap<>();
        for (Input row : feed.records()) {
            if (row == null || row.nutrition() == null || row.nutrition().values().isEmpty()) throw invalid();
            String barcode;
            try { barcode = Barcode.normalize(row.barcode()); }
            catch (IllegalArgumentException error) { throw invalid(); }
            String packSize = required(row.packSize(), 80);
            if (PackageSize.exact(packSize).isEmpty()) throw invalid();
            String url = httpsUrl(row.sourceUrl());
            var report = new Report(feed.sourceId(), row.nutrition(), new NutritionSource(feed.kind(), name, url, observed.toString(), packSize));
            if (batch.putIfAbsent(barcode, report) != null) throw new SubmissionException(400, "NUTRITION_IMPORT_DUPLICATE_BARCODE");
        }
        return transactions.execute(status -> {
            db.queryForObject("SELECT id FROM nutrition_import_guard WHERE id=1 FOR UPDATE", Integer.class);
            List<Conflict> conflicts = new ArrayList<>();
            for (var entry : batch.entrySet()) {
                String barcode = entry.getKey();
                Report incoming = entry.getValue();
                Product product = catalogue.find(barcode)
                    .orElseThrow(() -> new SubmissionException(400, "NUTRITION_IMPORT_UNKNOWN_BARCODE"));
                List<Report> reports = new ArrayList<>(reports(barcode));
                Report previous = reports.stream().filter(report -> report.sourceId().equals(feed.sourceId())).findFirst().orElse(null);
                if (previous != null && !previous.equals(incoming)
                        && !observed.isAfter(Instant.parse(previous.source().observedAt())))
                    throw new SubmissionException(409, "NUTRITION_IMPORT_OLDER_REPORT");
                reports.remove(previous);
                reports.add(incoming);
                conflict(product, reports, external.lastReport(barcode)).ifPresent(reason -> conflicts.add(new Conflict(barcode, reason)));
                if (!dryRun && !incoming.equals(previous)) {
                    String payload = json.writeValueAsString(incoming);
                    if (previous == null) db.update("INSERT INTO nutrition_reports(barcode,source_id,observed_at,payload) VALUES (?,?,?,?)",
                        barcode, feed.sourceId(), observed.toEpochMilli(), payload);
                    else db.update("UPDATE nutrition_reports SET observed_at=?,payload=? WHERE barcode=? AND source_id=?",
                        observed.toEpochMilli(), payload, barcode, feed.sourceId());
                }
            }
            return new Result(!dryRun, batch.size(), List.copyOf(conflicts));
        });
    }

    Product addSavedNutrition(Product product) {
        List<Report> reports = reports(product.barcode());
        if (reports.isEmpty()) return product;
        if (conflict(product, reports, external.lastReport(product.barcode())).isPresent())
            return product.withNutrition(NutritionLabel.empty(), NutritionSource.conflict());
        // Within a source kind, the query puts the latest report first. Display one panel as reported.
        Report preferred = reports.stream().filter(report -> report.source().kind() == NutritionSource.Kind.MANUFACTURER_REPORTED)
            .findFirst().orElse(reports.getFirst());
        return product.withNutrition(preferred.nutrition(), preferred.source());
    }

    private List<Report> reports(String barcode) {
        return db.query("SELECT payload FROM nutrition_reports WHERE barcode=? ORDER BY observed_at DESC,source_id",
            (rs, row) -> json.readValue(rs.getString(1), Report.class), barcode);
    }

    private static Optional<String> conflict(Product product, List<Report> reports, Optional<OpenFoodFactsClient.Lookup> cached) {
        var pack = PackageSize.product(product);
        if (pack.isEmpty()) return Optional.of("PACK_SIZE_UNKNOWN");
        if (reports.stream().anyMatch(report -> !pack.equals(PackageSize.exact(report.source().packSize()))))
            return Optional.of("PACK_SIZE_MISMATCH");
        List<NutritionLabel> panels = new ArrayList<>(reports.stream().map(Report::nutrition).toList());
        cached.ifPresent(result -> panels.add(result.product().nutrition()));
        if (panels.stream().anyMatch(left -> panels.stream().anyMatch(left::disagreesWith)))
            return Optional.of("NUTRITION_DISAGREEMENT");
        return Optional.empty();
    }

    private static String required(String value, int limit) {
        if (value == null || value.isBlank() || value.length() > limit) throw invalid();
        return value.strip();
    }

    private static String httpsUrl(String value) {
        String url = required(value, 800);
        try {
            URI uri = URI.create(url);
            if ("https".equals(uri.getScheme()) && uri.getHost() != null && uri.getUserInfo() == null) return url;
        } catch (IllegalArgumentException ignored) {
            // Reject malformed URLs before storing links.
        }
        throw invalid();
    }

    private static SubmissionException invalid() { return new SubmissionException(400, "NUTRITION_IMPORT_INVALID"); }
}
