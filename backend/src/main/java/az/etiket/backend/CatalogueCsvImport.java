package az.etiket.backend;

import az.etiket.core.Barcode;
import az.etiket.core.DemoCatalog;
import az.etiket.core.NutritionLabel;
import az.etiket.core.Product;
import az.etiket.core.ProductEvidence;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.apache.commons.csv.DuplicateHeaderMode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

/** Publish reviewer-confirmed package data independently of the starter catalogue. */
@Service
final class CatalogueCsvImport {
    static final int MAX_BYTES = 1024 * 1024;
    private static final int MAX_ROWS = 500;
    private static final Set<String> REQUIRED = Set.of("barcode", "name", "pack_size", "source_url");
    private static final Set<String> OPTIONAL = Set.of("brand", "ingredients", "ingredients_language",
        "declared_allergens", "nutrition_basis");
    private static final Map<String, String> NUTRIENT_COLUMNS = Map.of(
        "energy_kcal", "energyKcal", "fat_g", "fat", "saturated_fat_g", "saturatedFat",
        "carbohydrates_g", "carbohydrates", "sugars_g", "sugars", "fiber_g", "fiber",
        "protein_g", "protein", "salt_g", "salt");
    private static final CSVFormat FORMAT = CSVFormat.RFC4180.builder()
        .setHeader().setSkipHeaderRecord(true).setIgnoreEmptyLines(true)
        .setDuplicateHeaderMode(DuplicateHeaderMode.DISALLOW).get();

    enum Status { READY, UNCHANGED, INVALID, CONFLICT }
    record Entry(String barcode, String name, String brand, String packSize, String sourceUrl,
        String ingredients, String ingredientsLanguage, String declaredAllergens, NutritionLabel nutrition) { }
    record Row(int row, String barcode, Entry entry, Status status, String reason) {
        Row withStatus(Status next, String reason) { return new Row(row, barcode, entry, next, reason); }
    }
    record Result(String digest, boolean published, int added, int ready, int unchanged, int blocked, List<Row> rows) { }
    private record SourceRecord(Entry entry, String reviewer, String checkedAt) { }

    private final CatalogueStore catalogue;
    private final TransactionTemplate transactions;
    private final JsonMapper json = JsonMapper.builder().build();

    CatalogueCsvImport(CatalogueStore catalogue, TransactionTemplate transactions) {
        this.catalogue = catalogue;
        this.transactions = transactions;
    }

    Result importCsv(byte[] bytes, boolean dryRun, boolean confirmed, String previewDigest, String reviewer) {
        if (bytes.length > MAX_BYTES) throw new SubmissionException(413, "CATALOGUE_CSV_TOO_LARGE");
        if (!dryRun && !confirmed) throw new SubmissionException(400, "CATALOGUE_CONFIRMATION_REQUIRED");
        String digest = LabelPhotos.sha256(bytes);
        if (!dryRun && !digest.equals(previewDigest))
            throw new SubmissionException(409, "CATALOGUE_PREVIEW_CHANGED");
        List<Row> parsed = parse(bytes);
        return transactions.execute(status -> assessAndPublish(parsed, digest, dryRun, reviewer));
    }

    private Result assessAndPublish(List<Row> parsed, String digest, boolean dryRun, String reviewer) {
        if (!dryRun) catalogue.lockCatalogueImports();
        Map<String, Long> counts = parsed.stream().filter(row -> !row.barcode().isEmpty())
            .collect(Collectors.groupingBy(Row::barcode, Collectors.counting()));
        List<Row> checked = parsed.stream().map(row -> assess(row, counts)).toList();
        List<Entry> ready = checked.stream().filter(row -> row.status() == Status.READY).map(Row::entry).toList();
        int unchanged = (int) checked.stream().filter(row -> row.status() == Status.UNCHANGED).count();
        int blocked = checked.size() - ready.size() - unchanged;
        if (dryRun || blocked > 0) return new Result(digest, false, 0, ready.size(), unchanged, blocked, checked);

        String now = Instant.now().toString();
        for (Entry entry : ready) {
            var evidence = new ProductEvidence("LABEL_PHOTO_REVIEWED",
                entry.ingredients().isEmpty() ? "MISSING" : "LABEL_PHOTO_REVIEWED", true,
                entry.ingredients().isEmpty() ? "" : entry.sourceUrl(), entry.ingredients(),
                entry.declaredAllergens(), List.of("Reviewer-confirmed catalogue import"), false);
            var product = new Product(entry.barcode(), entry.name(), entry.brand(), entry.packSize(),
                entry.ingredients(), entry.ingredientsLanguage(), "", Map.of(), List.of(), List.of(),
                "REVIEWED_LABEL", entry.sourceUrl(), now, now, false, evidence, null, entry.nutrition());
            catalogue.insertImported(product, json.writeValueAsString(new SourceRecord(entry, reviewer, now)));
        }
        return new Result(digest, true, ready.size(), ready.size(), unchanged, 0, checked);
    }

    private Row assess(Row row, Map<String, Long> counts) {
        if (row.status() == Status.INVALID) return row;
        if (counts.get(row.barcode()) > 1) return row.withStatus(Status.CONFLICT, "DUPLICATE_BARCODE");
        var existing = catalogue.existingForImport(row.barcode());
        if (existing.isEmpty()) return row;
        String source = existing.get().importSource();
        if (source != null && json.readValue(source, SourceRecord.class).entry().equals(row.entry()))
            return row.withStatus(Status.UNCHANGED, "");
        return row.withStatus(Status.CONFLICT, "BARCODE_ALREADY_EXISTS");
    }

    private static List<Row> parse(byte[] bytes) {
        String text;
        try { text = StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString(); }
        catch (CharacterCodingException e) { throw new SubmissionException(400, "CATALOGUE_CSV_UTF8_REQUIRED"); }
        if (text.startsWith("\uFEFF")) text = text.substring(1);
        try (CSVParser parser = CSVParser.parse(text, FORMAT)) {
            var headers = parser.getHeaderNames();
            if (!headers.containsAll(REQUIRED)
                    || headers.stream().anyMatch(name -> !REQUIRED.contains(name) && !OPTIONAL.contains(name)
                        && !NUTRIENT_COLUMNS.containsKey(name)))
                throw new SubmissionException(400, "CATALOGUE_CSV_HEADER_INVALID");
            List<Row> rows = new ArrayList<>();
            for (CSVRecord record : parser) {
                if (rows.size() == MAX_ROWS) throw new SubmissionException(400, "CATALOGUE_CSV_TOO_MANY_ROWS");
                rows.add(readRow(rows.size() + 2, record));
            }
            if (rows.isEmpty()) throw new SubmissionException(400, "CATALOGUE_CSV_EMPTY");
            return rows;
        } catch (IOException | UncheckedIOException | IllegalArgumentException e) {
            throw new SubmissionException(400, "CATALOGUE_CSV_INVALID");
        }
    }

    private static Row readRow(int number, CSVRecord record) {
        String barcode = "";
        try {
            barcode = Barcode.normalize(field(record, "barcode", 32, true));
            if (!record.isConsistent()) throw new IllegalArgumentException("COLUMN_COUNT_MISMATCH");
            String code = barcode;
            if (DemoCatalog.products().stream().anyMatch(product -> product.barcode().equals(code)))
                throw new IllegalArgumentException("DEMO_BARCODE_RESERVED");
            String name = field(record, "name", 200, true);
            String brand = field(record, "brand", 200, false);
            String size = field(record, "pack_size", 80, true);
            if (PackageSize.exact(size).isEmpty()) throw new IllegalArgumentException("PACK_SIZE_INVALID");
            String source = field(record, "source_url", 800, true);
            checkSource(source);
            String ingredients = field(record, "ingredients", 10_000, false);
            String allergens = field(record, "declared_allergens", 2_000, false);
            String language = field(record, "ingredients_language", 12, !ingredients.isEmpty() || !allergens.isEmpty());
            if (!language.isEmpty() && !language.matches("[a-z]{2,3}(-[A-Z]{2})?"))
                throw new IllegalArgumentException("INGREDIENTS_LANGUAGE_INVALID");
            var entry = new Entry(barcode, name, brand, size, source, ingredients, language, allergens, nutrition(record));
            return new Row(number, barcode, entry, Status.READY, "");
        } catch (IllegalArgumentException e) {
            return new Row(number, barcode, null, Status.INVALID, e.getMessage());
        }
    }

    private static String field(CSVRecord record, String name, int maximum, boolean required) {
        String value = record.isSet(name) ? record.get(name).strip() : "";
        if (value.length() > maximum || required && value.isEmpty()
                || value.chars().anyMatch(c -> Character.isISOControl(c) && c != '\r' && c != '\n' && c != '\t'))
            throw new IllegalArgumentException(name.toUpperCase(Locale.ROOT) + "_INVALID");
        return value;
    }

    private static void checkSource(String value) {
        try {
            URI url = URI.create(value);
            if ("https".equals(url.getScheme()) && url.getHost() != null && url.getRawUserInfo() == null
                    && url.getPort() <= 65_535) return;
        } catch (IllegalArgumentException ignored) {
            // Report the field without echoing malformed URL text.
        }
        throw new IllegalArgumentException("SOURCE_URL_INVALID");
    }

    private static NutritionLabel nutrition(CSVRecord record) {
        String basis = field(record, "nutrition_basis", 12, false);
        Map<String, Double> values = new HashMap<>();
        for (var column : NUTRIENT_COLUMNS.entrySet()) {
            String value = field(record, column.getKey(), 20, false);
            if (value.isEmpty()) continue;
            if (!value.matches("[0-9]{1,4}(?:[.,][0-9]{1,6})?"))
                throw new IllegalArgumentException(column.getKey().toUpperCase(Locale.ROOT) + "_INVALID");
            values.put(column.getValue(), Double.parseDouble(value.replace(',', '.')));
        }
        var label = new NutritionLabel(basis, values);
        return values.isEmpty() ? NutritionLabel.empty() : label;
    }
}
