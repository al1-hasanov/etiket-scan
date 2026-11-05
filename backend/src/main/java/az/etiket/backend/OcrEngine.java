package az.etiket.backend;

import java.util.List;

interface OcrEngine {
    record Text(String text, double confidence, List<String> uncertainWords, int rotation, List<String> warnings) {
        Text(String text, double confidence, List<String> uncertainWords, int rotation) {
            this(text, confidence, uncertainWords, rotation, List.of());
        }
    }
    Text recognize(byte[] image, String language) throws Exception;
}
