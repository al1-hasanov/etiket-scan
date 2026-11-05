package az.etiket.core;

/** GTINs are strings: leading zeroes matter. Supports EAN-8, UPC-A, EAN-13 and GTIN-14. */
public final class Barcode {
    private Barcode() { }

    public static String normalize(String input) {
        if (input == null) throw new IllegalArgumentException("BARCODE_INVALID");
        String value = input.strip().replace(" ", "").replace("-", "");
        if (!value.matches("(?:[0-9]{8}|[0-9]{12,14})"))
            throw new IllegalArgumentException("BARCODE_INVALID");
        int sum = 0;
        for (int i = value.length() - 2, weight = 3; i >= 0; i--, weight = 4 - weight)
            sum += (value.charAt(i) - '0') * weight;
        if ((10 - sum % 10) % 10 != value.charAt(value.length() - 1) - '0')
            throw new IllegalArgumentException("BARCODE_CHECK_DIGIT");
        if (value.length() == 12) value = "0" + value;
        if (value.length() == 14 && value.startsWith("0")) value = value.substring(1);
        return value;
    }
}
