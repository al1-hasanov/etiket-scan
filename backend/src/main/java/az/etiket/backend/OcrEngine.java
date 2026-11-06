package az.etiket.backend;

import az.etiket.core.NutritionTextParser;
import java.util.List;

interface OcrEngine {
    record Text(String text, double confidence, List<String> uncertainWords, int rotation, List<String> warnings,
                NutritionTextParser.Result tableNutrition) {
        Text(String text, double confidence, List<String> uncertainWords, int rotation, List<String> warnings) {
            this(text, confidence, uncertainWords, rotation, warnings, null);
        }
        Text(String text, double confidence, List<String> uncertainWords, int rotation) {
            this(text, confidence, uncertainWords, rotation, List.of());
        }
    }
    Text recognize(byte[] image, String language) throws Exception;

    default Text recognizeNutrition(byte[] image, String language) throws Exception {
        return recognize(image, language);
    }
}
