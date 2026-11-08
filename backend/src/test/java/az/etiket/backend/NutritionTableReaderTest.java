package az.etiket.backend;

import az.etiket.backend.NutritionTableReader.Word;
import az.etiket.core.NutritionLabel;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class NutritionTableReaderTest {
    @Test void keepsOnlyPer100GramCellsWithWrappedLabelsAndEnergy() {
        var words = germanTable();
        var result = NutritionTableReader.agree(NutritionTableReader.read(words), NutritionTableReader.read(words));
        assertEquals(new NutritionLabel("PER_100_G", Map.of(
            "energyKcal", 378d, "fat", 9.8d, "saturatedFat", 3.3d, "carbohydrates", 66d,
            "sugars", 27d, "fiber", 2.7d, "protein", 5.2d, "salt", .29d)), result.nutrition());
    }

    @Test void findsTheReferenceOnTheRightAndDoesNotBorrowAMissingServingCell() {
        List<Word> words = new ArrayList<>();
        row(words, 30, "Nutrition", "Per serving (22 ml)", "100 ml");
        row(words, 54, "Energy", "85 kcal", "378 kcal");
        row(words, 78, "Fat", "2,1 g", "");
        row(words, 102, "Salt", "0,06 g", "0 g");
        words.add(new Word("102", "3%", 725, 102, 748, 122, 94));
        var table = NutritionTableReader.read(words);
        assertEquals(new NutritionLabel("PER_100_ML", Map.of("energyKcal", 378d, "salt", 0d)),
            NutritionTableReader.agree(table, table).nutrition());
    }

    @Test void requiresAgreementAndKeepsUnclearCellsUnknown() {
        List<Word> first = germanTable();
        List<Word> second = first.stream().map(word -> switch (word.text()) {
            case "66 g" -> replace(word, "669", 92);
            case "9,8 g" -> replace(word, "9,9 g", 94);
            case "27 g" -> replace(word, "27 g", 30);
            case "0,29 g" -> replace(word, "0,29 mg", 92);
            case "5,2 g" -> replace(word, "<5,2 g", 92);
            default -> word;
        }).toList();
        assertEquals(Map.of("energyKcal", 378d, "saturatedFat", 3.3d, "fiber", 2.7d),
            NutritionTableReader.agree(NutritionTableReader.read(first), NutritionTableReader.read(second)).nutrition().values());
    }

    @Test void fallsBackForCompetingReferenceHeadingsOrUnconfirmedLayout() {
        List<Word> words = germanTable();
        var first = NutritionTableReader.read(words);
        var ambiguous = NutritionTableReader.read(words.stream()
            .map(word -> word.text().equals("in 22 g") ? replace(word, "100 ml", 97) : word).toList());
        assertTrue(NutritionTableReader.agree(first, ambiguous).nutrition().values().isEmpty());
        var noServing = NutritionTableReader.read(words.stream().filter(word -> !word.text().equals("in 22 g")).toList());
        assertTrue(NutritionTableReader.agree(first, noServing).nutrition().values().isEmpty());
    }

    @Test void omitsACellSpanningTheColumnBoundary() {
        List<Word> words = germanTable();
        words.replaceAll(word -> word.text().equals("66 g")
            ? new Word(word.line(), word.text(), 400, word.top(), 510, word.bottom(), 97) : word);
        var table = NutritionTableReader.read(words);
        var values = NutritionTableReader.agree(table, table).nutrition().values();
        assertFalse(values.containsKey("carbohydrates"));
        assertEquals(.29d, values.get("salt"));
    }

    @Test void doesNotTreatTwoNumbersInAMisreadNutrientRowAsHeadings() {
        List<Word> words = new ArrayList<>();
        row(words, 30, "Kohlenhydrale", "100 g", "22 g");
        row(words, 54, "Fett", "9,8 g", "2,1 g");
        assertNull(NutritionTableReader.read(words));
    }

    @Test void ignoresIsolatedBordersButDoesNotGuessDamagedUnitsOrDigits() {
        List<Word> words = germanTable();
        words.replaceAll(word -> word.text().equals("Brennwert") ? replace(word, "| Brennwert", 94)
            : word.text().equals("(378 kcal)") ? replace(word, "| (378 kcal)", 94)
            : word.text().equals("66 g") ? replace(word, "669", 94) : word);
        var table = NutritionTableReader.read(words);
        var values = NutritionTableReader.agree(table, table).nutrition().values();
        assertEquals(378d, values.get("energyKcal"));
        assertFalse(values.containsKey("carbohydrates"));
    }

    @Test void aFallbackCanConfirmACellButCannotOutvoteAConflictingNumber() {
        var first = NutritionTableReader.read(germanTable());
        var changed = NutritionTableReader.read(germanTable().stream()
            .map(word -> word.text().equals("9,8 g") ? replace(word, "9,9 g", 94) : word).toList());
        var values = NutritionTableReader.agree(List.of(first, first, changed)).nutrition().values();
        assertFalse(values.containsKey("fat"));
        assertEquals(.29d, values.get("salt"));
    }

    @Test void lowConfidenceServingHeadingsAndChangedServingAmountsCannotAnchorValues() {
        var first = NutritionTableReader.read(germanTable());
        var weak = NutritionTableReader.read(germanTable().stream()
            .map(word -> word.text().equals("in 22 g") ? replace(word, "in 22 g", 8) : word).toList());
        assertTrue(NutritionTableReader.agree(first, weak).nutrition().values().isEmpty());
        var changed = NutritionTableReader.read(germanTable().stream()
            .map(word -> word.text().equals("in 22 g") ? replace(word, "in 222 g", 94) : word).toList());
        assertTrue(NutritionTableReader.agree(List.of(first, first, changed)).nutrition().values().isEmpty());
    }

    private static List<Word> germanTable() {
        List<Word> words = new ArrayList<>();
        row(words, 30, "Nährwerte", "in 100 g", "in 22 g");
        row(words, 54, "Brennwert", "1595 kJ", "356 kJ");
        row(words, 78, "", "(378 kcal)", "(85 kcal)");
        row(words, 102, "Fett", "9,8 g", "2,1 g");
        row(words, 126, "davon gesättigte", "", "");
        row(words, 150, "Fettsäuren", "3,3 g", "0,7 g");
        row(words, 174, "Kohlenhydrate", "66 g", "15 g");
        row(words, 198, "davon Zucker", "27 g", "5,9 g");
        row(words, 222, "Ballaststoffe", "2,7 g", "0,6 g");
        row(words, 246, "Eiweiß", "5,2 g", "1,1 g");
        row(words, 270, "Salz", "0,29 g", "0,06 g");
        return words;
    }

    private static void row(List<Word> words, int top, String name, String left, String right) {
        if (!name.isEmpty()) add(words, top, name, 20 + name.length() * 8);
        if (!left.isEmpty()) add(words, top, left, 420);
        if (!right.isEmpty()) add(words, top, right, 610);
    }

    private static void add(List<Word> words, int top, String text, int right) {
        words.add(new Word(Integer.toString(top), text, right - text.length() * 8, top, right, top + 20, 94));
    }

    private static Word replace(Word word, String text, double confidence) {
        return new Word(word.line(), text, word.left(), word.top(), word.right(), word.bottom(), confidence);
    }
}
