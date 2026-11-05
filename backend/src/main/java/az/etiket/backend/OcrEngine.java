package az.etiket.backend;

import java.util.List;

interface OcrEngine {
    record Text(String text, double confidence, List<String> uncertainWords, int rotation) { }
    Text recognize(byte[] image, String language) throws Exception;
}
