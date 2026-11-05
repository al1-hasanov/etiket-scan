package az.etiket.core;

/** A minimum readability check for OCR drafts in any of the supported scripts. */
public final class OcrQuality {
    public record Assessment(boolean readable, int words) { }

    private OcrQuality() { }

    public static Assessment assess(String text, double confidence) {
        int letters = 0;
        int visible = 0;
        int wordLetters = 0;
        int words = 0;
        for (String token : text.strip().split("\\s+")) {
            int count = 0;
            int firstLetter = -1;
            boolean varied = false;
            for (int i = 0; i < token.length();) {
                int codePoint = token.codePointAt(i);
                i += Character.charCount(codePoint);
                visible++;
                if (!Character.isLetter(codePoint)) continue;
                count++;
                int lower = Character.toLowerCase(codePoint);
                if (firstLetter == -1) firstLetter = lower;
                else if (firstLetter != lower) varied = true;
            }
            letters += count;
            if (count >= 3 && varied) {
                words++;
                wordLetters += count;
            }
        }
        boolean readable = Double.isFinite(confidence) && confidence >= 45
            && words >= 2 && letters >= 8 && letters >= visible * .35
            && wordLetters >= letters * .55;
        return new Assessment(readable, words);
    }
}
