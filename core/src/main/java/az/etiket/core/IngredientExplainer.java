package az.etiket.core;

import java.text.Normalizer;
import java.util.*;
import java.util.regex.Pattern;

/** Small exact-alias dictionary shared by the API and offline Android app. No fuzzy matching. */
public final class IngredientExplainer {
    public static final String VERSION = "2026-11-v1";
    private static final Locale AZ = Locale.forLanguageTag("az");
    private static final Pattern PERCENT = Pattern.compile("\\s*(?:\\(\\s*\\d+(?:[.,]\\d+)?\\s*%\\s*\\)|\\d+(?:[.,]\\d+)?\\s*%)$");
    private static final Set<String> GROUPS = Set.of("qatılaşdırıcı", "qatılaşdırıcılar",
        "turşuluq tənzimləyicisi", "turşuluq tənzimləyiciləri", "boyayıcı", "boyayıcılar",
        "rəngləndirici", "rəngləndiricilər", "stabilizator", "stabilizatorlar", "emulqator", "emulqatorlar");
    private static final IngredientAnalysis.Reference FDA = new IngredientAnalysis.Reference("FDA — ingredient functions",
        "https://www.fda.gov/food/food-additives-and-gras-ingredients-information-consumers/types-food-ingredients");
    private static final IngredientAnalysis.Reference FSANZ = new IngredientAnalysis.Reference("FSANZ — food additive functions",
        "https://www.foodstandards.gov.au/consumer/additives/additiveinfo");
    private static final IngredientAnalysis.Reference FSA = new IngredientAnalysis.Reference("FSA — additive names and E numbers",
        "https://www.gov.uk/government/publications/approved-additives-and-e-numbers/approved-additives-and-e-numbers");
    private static final IngredientAnalysis.Reference GELATIN = new IngredientAnalysis.Reference("FAO / JECFA — edible gelatin",
        "https://www.fao.org/fileadmin/user_upload/jecfa_additives/docs/Monograph1/Additive-171.pdf");
    private static final Map<String, Entry> DICTIONARY = dictionary();

    private IngredientExplainer() { }

    public static IngredientAnalysis analyze(String ingredients, String language) {
        if (ingredients == null || ingredients.isBlank()) return result("MISSING", List.of());
        String tag = language == null ? "" : language.trim().toLowerCase(Locale.ROOT).replace('_', '-');
        if (!tag.equals("az") && !tag.startsWith("az-"))
            return result("UNSUPPORTED_LANGUAGE", List.of());
        List<IngredientAnalysis.Item> items = new ArrayList<>();
        for (String component : split(ingredients)) explain(component, "", items, 0);
        long known = items.stream().filter(IngredientAnalysis.Item::recognized).count();
        return result(known == items.size() && known > 0 ? "AVAILABLE" : known > 0 ? "PARTIAL" : "NO_MATCHES", items);
    }

    private static IngredientAnalysis result(String status, List<IngredientAnalysis.Item> items) {
        return new IngredientAnalysis(status, "az", VERSION, "EDITORIAL_DRAFT",
            (int) items.stream().filter(IngredientAnalysis.Item::recognized).count(), items);
    }

    private static void explain(String raw, String group, List<IngredientAnalysis.Item> output, int depth) {
        String reported = raw.trim();
        if (reported.isEmpty()) return;
        String term = normalize(reported);
        int opening = term.indexOf('(');
        if (depth < 4 && opening > 0 && term.endsWith(")") && balanced(term)
                && GROUPS.contains(term.substring(0, opening).trim())) {
            String category = reported.substring(0, reported.indexOf('(')).trim();
            String children = reported.substring(reported.indexOf('(') + 1, reported.lastIndexOf(')'));
            int previousSize = output.size();
            for (String child : split(children)) explain(child, category, output, depth + 1);
            if (output.size() > previousSize) return;
        }
        // Strip only a known functional prefix. Never search for a known word inside an unknown phrase.
        for (String category : GROUPS) {
            if (term.startsWith(category)) {
                String rest = term.substring(category.length()).trim();
                if (rest.startsWith(":") || rest.startsWith("-")) {
                    term = rest.substring(1).trim();
                    if (group.isEmpty()) group = category;
                    break;
                }
            }
        }
        term = PERCENT.matcher(term).replaceFirst("").trim();
        Entry entry = balanced(term) ? DICTIONARY.get(key(term)) : null;
        output.add(entry == null
            ? new IngredientAnalysis.Item(reported, group, false, reported, "", List.of())
            : new IngredientAnalysis.Item(reported, group, true, entry.name(), entry.description(), entry.references()));
    }

    private static List<String> split(String text) {
        // A malformed bracket group is retained whole, rather than guessed apart.
        if (!balanced(text)) return List.of(text);
        List<String> result = new ArrayList<>();
        int depth = 0, start = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '(') depth++;
            if (c == ')') depth--;
            boolean decimalComma = c == ',' && i > 0 && i + 1 < text.length()
                && Character.isDigit(text.charAt(i - 1)) && Character.isDigit(text.charAt(i + 1));
            if (depth == 0 && (c == ';' || c == ',' && !decimalComma)) {
                result.add(text.substring(start, i)); start = i + 1;
            }
        }
        result.add(text.substring(start));
        return result;
    }

    private static boolean balanced(String value) {
        int depth = 0;
        for (int i = 0; i < value.length(); i++) {
            if (value.charAt(i) == '(') depth++;
            if (value.charAt(i) == ')' && --depth < 0) return false;
        }
        return depth == 0;
    }

    private static String normalize(String value) {
        return Normalizer.normalize(value, Normalizer.Form.NFKC).replace('\u00a0', ' ')
            .replace('\u2013', '-').replace('\u2014', '-').replace('\u2011', '-')
            .toLowerCase(AZ).trim().replaceAll("\\s+", " ").replaceFirst("[.]+$", "").trim();
    }

    private static String key(String value) {
        String normalized = normalize(value);
        return normalized.matches("e\\s*-?\\s*\\d{3,4}[a-z]?")
            ? normalized.replaceAll("[\\s-]", "") : normalized;
    }

    private record Entry(String name, String description, List<IngredientAnalysis.Reference> references) { }

    private static void add(Map<String, Entry> entries, String name, String description,
            List<IngredientAnalysis.Reference> references, String... aliases) {
        Entry entry = new Entry(name, description, List.copyOf(references));
        for (String alias : aliases) {
            if (entries.put(key(alias), entry) != null) throw new IllegalStateException("Duplicate ingredient alias: " + alias);
        }
    }

    private static Map<String, Entry> dictionary() {
        Map<String, Entry> entries = new LinkedHashMap<>();
        add(entries, "İnək südü", "İnəkdən alınan süd məhsuludur.", List.of(), "inək südü", "süd");
        add(entries, "Pasterizə inək südü", "İstiliklə emal edilmiş inək südüdür; pasterizasiya sterilizasiya ilə eyni deyil.", List.of(), "pasterizə inək südü");
        add(entries, "Yağsızlaşdırılmış süd", "Yağının böyük hissəsi ayrılmış süddür.", List.of(), "yağsızlaşdırılmış süd");
        add(entries, "Qaymaq", "Südün yağla zəngin hissəsidir.", List.of(), "qaymaq");
        add(entries, "Süd tozu", "Suyunun böyük hissəsi çıxarılaraq qurudulmuş süddür.", List.of(), "süd tozu", "quru süd");
        add(entries, "Quru süd zərdabı", "Südün laxtalanmasından sonra ayrılan maye hissədən hazırlanmış tozdur.", List.of(), "quru süd zərdabı");
        add(entries, "Bitki yağı", "Bitki mənşəli yağdır. Mənbə mətni konkret bitkini göstərmir.", List.of(), "bitki yağı");
        add(entries, "Maya", "Mayalanma və ya laxtalanma üçün işlədilən ümumi addır. Burada dəqiq növü göstərilmir.", List.of(), "maya", "mayadan istifadə etməklə");
        add(entries, "Duz", "Məhsula duzlu dad verir. Bu siyahı onun miqdarını göstərmir.", List.of(FDA), "duz");
        add(entries, "Qara çörək otu", "Dad və ətir vermək üçün işlədilən toxumdur.", List.of(), "qara çörək otu");
        add(entries, "Su", "Məhsulun maye hissəsini təşkil edən və ya hazırlamada istifadə olunan sudur.", List.of(), "su", "pasterizə edilmiş su", "pasterilizə edilmiş su");
        add(entries, "Şəkər", "Şirin dad vermək üçün istifadə olunur. Miqdarı bu siyahıdan müəyyən edilmir.", List.of(FDA), "şəkər", "şəkər tozu");
        add(entries, "Qlükoza-fruktoza siropu", "Qlükoza və fruktoza şəkərləri olan şirinləşdirici siropdur.", List.of(FDA), "qlükoza-fruktoza siropu");
        add(entries, "Çiyələk püresi", "Əzilib püre halına salınmış çiyələkdir.", List.of(), "çiyələk püresi");
        add(entries, "Konsentrasiyalı çiyələk püresi", "Suyunun bir hissəsi çıxarılaraq qatılaşdırılmış çiyələk püresidir.", List.of(), "konsentrasiyalı çiyələk püresi");
        add(entries, "Çiyələk şirəsi", "Çiyələkdən alınmış şirədir.", List.of(), "çiyələk şirəsi");
        add(entries, "Banan püresi", "Əzilib püre halına salınmış banandır.", List.of(), "banan püresi");
        add(entries, "Konsentrasiyalı moruq püresi", "Suyunun bir hissəsi çıxarılaraq qatılaşdırılmış moruq püresidir.", List.of(), "konsentrasiyalı moruq püresi");
        add(entries, "Qaragilə", "Meyvə tərkib hissəsidir; dad və rəng verir.", List.of(), "qaragilə");
        add(entries, "Mərsin", "Mənbədə meyvə tərkib hissəsi kimi göstərilib.", List.of(), "mərsin");
        add(entries, "Zoğal", "Meyvə tərkib hissəsidir; dad və rəng verir.", List.of(), "zoğal");
        add(entries, "Yaşıl noxud", "Paxlalılar qrupuna aid bitki tərkib hissəsidir.", List.of(), "yaşıl noxud");
        add(entries, "Üzüm yarpağı", "Üzüm bitkisinin yemək hazırlamada istifadə olunan yarpağıdır.", List.of(), "üzüm yarpağı");
        add(entries, "Pomidor", "Məhsula pomidor dadı və rəngi verən tərkib hissəsidir.", List.of(), "pomidor");
        add(entries, "Bərk buğda unu", "Bərk buğdadan hazırlanan undur; makaron istehsalında istifadə olunur.", List.of(), "əla növ bərk buğda unu", "bərk buğda unu");
        add(entries, "Buğda unu", "Buğda dənlərinin üyüdülməsi ilə alınan undur.", List.of(), "buğda unu");
        add(entries, "Tam dənli yulaf", "Dənin əsas hissələri saxlanılmaqla hazırlanmış yulafdır.", List.of(), "tam dənli yulaf");
        add(entries, "Kakao", "Kakao dənələrindən alınan tərkib hissəsidir; dad və rəng verir.", List.of(), "kakao");
        add(entries, "E1422 — modifikasiya olunmuş nişasta", "Qatılığı və teksturanı tənzimləmək üçün işlədilən nişasta törəməsidir.", List.of(FSA, FSANZ), "E1422");
        add(entries, "E1442 — modifikasiya olunmuş nişasta", "Qatılığı və teksturanı tənzimləmək üçün işlədilən nişasta törəməsidir.", List.of(FSA, FSANZ), "E1442");
        add(entries, "Ksantan kitrəsi (E415)", "Qarışığı qatılaşdırmağa kömək edən maddədir.", List.of(FSA, FSANZ), "ksantan kitrəsi", "E415");
        add(entries, "Pektinlər (E440)", "Qatılaşdırma və gel əmələ gətirmə üçün işlədilən maddələrdir.", List.of(FSA, FDA), "pektinlər", "pektin", "E440");
        add(entries, "Jelatin", "Heyvan kollagenindən alınan, gel əmələ gətirməyə kömək edən zülaldır. Bu mətndə heyvan növü göstərilmir.", List.of(GELATIN, FDA), "jelatin");
        add(entries, "Aromatizatorlar", "Məhsulun dadını və ətrini formalaşdırmaq üçün əlavə olunur. Konkret maddələr göstərilmir.", List.of(FDA), "aromatizatorlar", "aromatizator");
        add(entries, "Limon turşusu (E330)", "Turş dad vermək və turşuluğu tənzimləmək üçün istifadə olunan maddədir.", List.of(FSA, FSANZ), "limon turşusu", "E330");
        add(entries, "Natrium sitratlar (E331)", "Limon turşusunun natrium duzlarıdır; turşuluğu tənzimləməyə kömək edir.", List.of(FSA, FSANZ), "natrium sitratlar", "E331");
        add(entries, "Kalsium sitratlar (E333)", "Limon turşusunun kalsium duzlarıdır; turşuluğu tənzimləmək üçün işlədilə bilər.", List.of(FSA, FSANZ), "kalsium sitratlar", "E333");
        add(entries, "Natrium fosfatlar (E339)", "Turşuluğu və teksturanı tənzimləmək üçün istifadə oluna bilən fosfat duzlarıdır.", List.of(FSA, FSANZ), "natrium fosfatlar", "E339");
        add(entries, "Karminlər (E120)", "Qırmızı rəng vermək üçün istifadə olunan rəngləndiricidir.", List.of(FSA), "karminlər", "karmin", "E120");
        add(entries, "Emulqator", "Yağ və suyun qarışıq vəziyyətdə qalmasına kömək edən maddədir. Dəqiq növ göstərilmir.", List.of(FDA, FSANZ), "emulqator", "emulqatorlar");
        return Collections.unmodifiableMap(entries);
    }
}
