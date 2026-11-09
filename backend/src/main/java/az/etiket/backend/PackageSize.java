package az.etiket.backend;

import az.etiket.core.Product;
import java.math.BigDecimal;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Compare simple pack sizes in g or ml, including the Azerbaijani catalogue spelling. */
record PackageSize(BigDecimal amount, boolean volume) {
    private static final String SIZE = "(\\d{1,6}(?:[.,]\\d{1,4})?)\\s*(millilitr|qram|litr|ml|kg|kq|qr|lt|g|q|l)";
    private static final Pattern EXACT = Pattern.compile(SIZE + "\\s*[e℮]?");
    private static final Pattern IN_TITLE = Pattern.compile("(?<![\\p{L}\\p{N}.,])" + SIZE + "(?![\\p{L}\\p{N}])");
    private static final Pattern MULTIPACK = Pattern.compile("\\d\\s*[x×]\\s*\\d");

    static Optional<PackageSize> exact(String text) {
        if (text == null) return Optional.empty();
        Matcher match = EXACT.matcher(text.strip().toLowerCase(Locale.ROOT));
        return match.matches() ? read(match) : Optional.empty();
    }

    static Optional<PackageSize> product(Product product) {
        if (!product.quantity().isBlank()) return exact(product.quantity());
        String title = product.name().toLowerCase(Locale.ROOT);
        if (MULTIPACK.matcher(title).find()) return Optional.empty();
        Matcher match = IN_TITLE.matcher(title);
        if (!match.find()) return Optional.empty();
        var size = read(match);
        return match.find() ? Optional.empty() : size;
    }

    private static Optional<PackageSize> read(Matcher match) {
        String unit = match.group(2);
        BigDecimal amount = new BigDecimal(match.group(1).replace(',', '.'));
        if (amount.signum() <= 0) return Optional.empty();
        if (unit.equals("kg") || unit.equals("kq") || unit.equals("l") || unit.equals("lt") || unit.equals("litr"))
            amount = amount.movePointRight(3);
        return Optional.of(new PackageSize(amount.stripTrailingZeros(),
            unit.equals("ml") || unit.equals("millilitr") || unit.equals("l") || unit.equals("lt") || unit.equals("litr")));
    }
}
