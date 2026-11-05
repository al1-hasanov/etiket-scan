package az.etiket.mobile;

import android.app.*;
import android.os.Bundle;
import android.os.Build;
import android.content.*;
import android.graphics.*;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.view.*;
import android.view.inputmethod.InputMethodManager;
import android.widget.*;
import android.text.Editable;
import android.text.TextWatcher;
import androidx.activity.ComponentActivity;
import androidx.activity.OnBackPressedCallback;
import az.etiket.core.*;
import com.google.android.gms.common.moduleinstall.*;
import com.google.mlkit.vision.codescanner.*;
import java.util.*;
import java.util.concurrent.*;
import org.json.JSONObject;

public class MainActivity extends ComponentActivity {
    private static final int GREEN = 0xFF216D50, DARK = 0xFF19372A, BG = 0xFFF6F8F5,
        MUTED = 0xFF627269, PALE = 0xFFE8F0DE, LINE = 0xFFDFE7DD, WHITE = 0xFFFFFFFF;
    private SharedPreferences prefs;
    private ProductStore store;
    private List<Product> catalogue;
    private boolean az;
    private String tab = "scan";
    private Product current;
    private LinearLayout root, body;
    private final ExecutorService executor = Executors.newFixedThreadPool(2);
    private boolean refreshingCatalogue;
    private long requestId;
    private boolean loading;
    private boolean scannerBusy, cameraOpen;
    private ModuleInstallClient moduleClient;
    private InstallStatusListener moduleListener;
    private AlertDialog installDialog;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        prefs = getSharedPreferences("etiket", MODE_PRIVATE); store = new ProductStore(prefs);
        catalogue = StarterCatalogue.load(this);
        List<Product> cached = store.cachedCatalogue(prefs.getString("base", "http://10.0.2.2:8080"));
        if (!cached.isEmpty()) catalogue = cached;
        az = prefs.getBoolean("az", true);
        if (state != null) {
            tab = state.getString("tab", "scan");
            try { if (state.containsKey("product")) current = ProductJson.read(new JSONObject(state.getString("product"))); }
            catch (Exception ignored) { }
        }
        if (Build.VERSION.SDK_INT >= 30) {
            getWindow().setDecorFitsSystemWindows(false);
            getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
        }
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override public void handleOnBackPressed() { handleBack(); }
        });
        render();
    }
    @Override public void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out); out.putString("tab", tab);
        try { if (current != null) out.putString("product", ProductJson.write(current).toString()); } catch (Exception ignored) { }
    }
    @Override protected void onDestroy() {
        requestId++; scannerBusy = false; cleanupScannerInstall(); executor.shutdownNow(); super.onDestroy();
    }
    @Override protected void onResume() {
        super.onResume();
        if (prefs != null && prefs.getBoolean("live", false)) refreshCatalogue(false);
    }
    private void refreshCatalogue(boolean showError) {
        if (refreshingCatalogue) return;
        refreshingCatalogue = true;
        String base = prefs.getString("base", "http://10.0.2.2:8080");
        executor.execute(() -> {
            try {
                List<Product> products = ApiClient.catalogue(base);
                runOnUiThread(() -> {
                    refreshingCatalogue = false;
                    if (isDestroyed() || !base.equals(prefs.getString("base", ""))) return;
                    catalogue = products; store.cacheCatalogue(base, products);
                    if (current == null && !loading) render();
                });
            } catch (ApiClient.Failure e) {
                runOnUiThread(() -> {
                    refreshingCatalogue = false;
                    if (!isDestroyed() && showError) message(t("Kataloq yenilənmədi. Son saxlanmış məhsullar göstərilir.", "Could not refresh the catalogue. Showing the last saved products."));
                });
            }
        });
    }
    private String catalogueSummary() {
        long with = catalogue.stream().filter(p -> !p.ingredients().isBlank()).count();
        long verified = catalogue.stream().filter(p -> p.evidence().labelPhotoChecked()).count();
        return catalogue.size() + t(" məhsul • ", " products • ") + with + t(" tərkib mətni • ", " ingredient lists • ")
            + verified + t(" etiket yoxlaması", " label checks");
    }
    private void submitLabel(String barcode, Product product) {
        Intent intent = new Intent(this, SubmissionActivity.class); intent.putExtra("barcode", barcode);
        if (product != null) intent.putExtra("name", product.name()).putExtra("brand", product.brand()).putExtra("quantity", product.quantity());
        startActivity(intent);
    }
    private void handleBack() {
        if (current != null || !tab.equals("scan") || loading) { requestId++; loading = false; current = null; tab = "scan"; render(); }
        else finish();
    }
    private String t(String a, String e) { return az ? a : e; }
    private String name(Product p) { return !az && p.demo() ? DemoCatalog.englishName(p.barcode()) : p.name(); }
    private int dp(float value) { return Math.round(value * getResources().getDisplayMetrics().density); }
    private LinearLayout column() { LinearLayout l = new LinearLayout(this); l.setOrientation(LinearLayout.VERTICAL); return l; }
    private LinearLayout row() { LinearLayout l = new LinearLayout(this); l.setGravity(Gravity.CENTER_VERTICAL); return l; }
    private void gap(LinearLayout parent, int height) { View space = new View(this); parent.addView(space, new LinearLayout.LayoutParams(1, dp(height))); }
    private GradientDrawable shape(int color, int radius) {
        GradientDrawable d = new GradientDrawable(); d.setColor(color); d.setCornerRadius(dp(radius)); return d;
    }
    private TextView text(String value, int size, int color, boolean bold) {
        TextView v = new TextView(this); v.setText(value); v.setTextSize(size); v.setTextColor(color);
        if (bold) v.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        v.setLineSpacing(dp(3), 1f); return v;
    }
    private TextView button(String label, boolean primary, Runnable action) {
        TextView b = new TextView(this); b.setText(label); b.setAllCaps(false); b.setTextSize(15); b.setGravity(Gravity.CENTER);
        b.setTextColor(primary ? WHITE : GREEN); b.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        b.setBackground(shape(primary ? GREEN : PALE, 15)); b.setMinHeight(dp(48)); b.setMinimumHeight(dp(48));
        b.setPadding(dp(14), dp(10), dp(14), dp(10)); b.setOnClickListener(v -> action.run()); b.setFocusable(true);
        b.setForeground(new android.graphics.drawable.RippleDrawable(android.content.res.ColorStateList.valueOf(0x20216D50), null, null));
        b.setAccessibilityDelegate(new View.AccessibilityDelegate() {
            @Override public void onInitializeAccessibilityNodeInfo(View view, android.view.accessibility.AccessibilityNodeInfo info) {
                super.onInitializeAccessibilityNodeInfo(view, info); info.setClassName(Button.class.getName());
            }
        });
        return b;
    }
    private void full(LinearLayout parent, View view) { parent.addView(view, new LinearLayout.LayoutParams(-1, -2)); }
    private LinearLayout card(int color) {
        LinearLayout l = column(); l.setPadding(dp(18), dp(18), dp(18), dp(18)); l.setBackground(shape(color, 22)); return l;
    }
    private void pill(LinearLayout parent, String label, int bg, int color) {
        TextView v = text(label, 11, color, true); v.setPadding(dp(10), dp(5), dp(10), dp(5)); v.setBackground(shape(bg, 20));
        parent.addView(v, new LinearLayout.LayoutParams(-2, -2));
    }
    private void render() {
        root = column(); root.setBackgroundColor(BG);
        root.setPadding(dp(20), dp(12), dp(20), dp(8)); setContentView(root);
        root.setOnApplyWindowInsetsListener((v, insets) -> {
            if (Build.VERSION.SDK_INT >= 30) {
                Insets bars = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.ime());
                root.setPadding(dp(20) + bars.left, dp(12) + bars.top, dp(20) + bars.right, dp(8) + bars.bottom);
            }
            return insets;
        });
        LinearLayout header = row();
        TextView logo = text("e", 22, WHITE, true); logo.setGravity(Gravity.CENTER); logo.setBackground(shape(GREEN, 12));
        header.addView(logo, new LinearLayout.LayoutParams(dp(38), dp(38)));
        TextView title = text("  Etiket", 23, DARK, true); header.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
        TextView language = button(az ? "EN" : "AZ", false, () -> { az = !az; prefs.edit().putBoolean("az", az).apply(); render(); });
        header.addView(language, new LinearLayout.LayoutParams(dp(60), dp(46))); full(root, header); gap(root, 10);
        LinearLayout status = row();
        boolean live = prefs.getBoolean("live", false);
        TextView mode = text(live ? t("● Canlı API", "● Live API") : t("● Offline kataloq", "● Offline catalogue"), 12, MUTED, false);
        status.addView(mode, new LinearLayout.LayoutParams(0, -2, 1));
        TextView settings = text(t("Parametrlər", "Settings"), 13, GREEN, true); settings.setPadding(dp(12), dp(10), 0, dp(10));
        settings.setOnClickListener(v -> settings()); status.addView(settings); full(root, status);
        ScrollView scroll = new ScrollView(this); scroll.setFillViewport(true); scroll.setClipToPadding(false); scroll.setVerticalScrollBarEnabled(false);
        body = column(); body.setPadding(0, dp(8), 0, dp(22)); scroll.addView(body);
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        if (loading) loadingPage();
        else if (current != null) productPage(current);
        else if (tab.equals("scan")) home();
        else if (tab.equals("catalogue")) cataloguePage();
        else if (tab.equals("fixtures")) fixturesPage();
        else listPage();
        LinearLayout nav = row(); nav.setPadding(0, dp(6), 0, 0);
        String[] tabs = {"scan", "catalogue", "history", "saved"};
        String[] labels = {t("Skan", "Scan"), t("Kataloq", "Catalogue"), t("Tarixçə", "History"), t("Seçilmişlər", "Saved")};
        for (int i = 0; i < tabs.length; i++) {
            String key = tabs[i];
            TextView b = button(labels[i], tab.equals(key), () -> { requestId++; loading = false; current = null; tab = key; render(); });
            b.setTextSize(12); b.setSingleLine(true); b.setEllipsize(android.text.TextUtils.TruncateAt.END);
            b.setPadding(dp(6), dp(10), dp(6), dp(10));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(50), 1); lp.setMargins(dp(3), 0, dp(3), 0);
            nav.addView(b, lp);
        }
        full(root, nav); root.requestApplyInsets();
    }
    private void home() {
        LinearLayout hero = card(PALE);
        TextView title = text(t("Etiketdə\nnə var?", "What’s in\nyour food?"), 34, DARK, true); full(hero, title); gap(hero, 8);
        full(hero, text(t("Barkodu skan edin. Tərkibi və qida dəyərlərini bir yerdə görün.",
            "Scan a barcode. Find ingredients and nutrition in one place."), 15, MUTED, false));
        View artwork = new BarcodeArt(this); hero.addView(artwork, new LinearLayout.LayoutParams(-1, dp(100))); full(body, hero); gap(body, 16);
        full(body, button(t("Barkodu kamerayla skan et", "Scan a barcode"), true, this::scan)); gap(body, 10);
        full(body, button(t("Barkodu əl ilə yaz", "Enter barcode manually"), false, this::manual)); gap(body, 24);
        full(body, button(t("Məhsul kataloqunu aç", "Browse products"), false,
            () -> { tab = "catalogue"; current = null; render(); if (prefs.getBoolean("live", false)) refreshCatalogue(true); })); gap(body, 10);
        full(body, button(t("Məhsul məlumatı əlavə et", "Add product information"), false, () -> submitLabel("", null))); gap(body, 10);
        full(body, button(t("Göndərdiklərim", "My submissions"), false, () -> startActivity(new Intent(this, MySubmissionsActivity.class)))); gap(body, 18);
        full(body, text(t("İlk məhsulları yoxlayın", "Try the first products"), 20, DARK, true)); gap(body, 5);
        full(body, text(catalogueSummary(), 12, MUTED, false)); gap(body, 10);
        for (String code : new String[]{"4760177560085", "8076809576130", "5201360553457"})
            for (Product p : catalogue) if (p.barcode().equals(code)) productCard(p);
        gap(body, 6);
        full(body, text(t("Saxlanmış kataloq offline işləyir. Yeni yoxlanmış etiketlər üçün canlı axtarışı qoşub kataloqu yeniləyin.",
            "The saved catalogue works offline. Enable live lookup and refresh it for newly reviewed labels."), 12, MUTED, false)); gap(body, 12);
        full(body, button(t("Uydurma demo nümunələri", "Fictional demo samples"), false,
            () -> { tab = "fixtures"; current = null; render(); }));
    }
    private void fixturesPage() {
        full(body, text(t("Uydurma nümunələr", "Fictional samples"), 25, DARK, true)); gap(body, 10);
        for (Product p : DemoCatalog.products()) productCard(p);
    }
    private void cataloguePage() {
        full(body, text(t("Məhsul kataloqu", "Product catalogue"), 27, DARK, true)); gap(body, 6);
        full(body, text(catalogueSummary(), 12, MUTED, false)); gap(body, 12);
        if (prefs.getBoolean("live", false)) {
            full(body, button(t("Kataloqu yenilə", "Refresh catalogue"), false, () -> refreshCatalogue(true))); gap(body, 10);
        }
        EditText search = new EditText(this); search.setSingleLine(); search.setTextSize(15);
        search.setHint(t("Ad, brend və ya barkod", "Name, brand or barcode")); full(body, search); gap(body, 12);
        LinearLayout results = column(); full(body, results);
        Runnable update = () -> {
            results.removeAllViews();
            String query = search.getText().toString().strip().toLowerCase(Locale.forLanguageTag("az"));
            LinearLayout previous = body; body = results;
            int count = 0;
            for (Product p : catalogue) {
                String haystack = (p.name() + " " + p.brand() + " " + p.barcode()).toLowerCase(Locale.forLanguageTag("az"));
                if (haystack.contains(query)) { productCard(p); count++; }
            }
            if (count == 0) full(results, text(t("Bu test kataloqunda nəticə yoxdur.", "No match in this test catalogue."), 15, MUTED, false));
            body = previous;
        };
        search.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            public void onTextChanged(CharSequence s, int start, int before, int count) { update.run(); }
            public void afterTextChanged(Editable e) { }
        });
        update.run();
    }
    private void productCard(Product p) {
        productCard(p, true);
    }
    private void productCard(Product p, boolean refreshFromApi) {
        LinearLayout c = card(WHITE); LinearLayout top = row();
        TextView icon = text(p.nutriScore().isEmpty() ? "?" : p.nutriScore().toUpperCase(Locale.ROOT), 21, GREEN, true);
        icon.setGravity(Gravity.CENTER); icon.setBackground(shape(PALE, 13)); top.addView(icon, new LinearLayout.LayoutParams(dp(48), dp(48)));
        LinearLayout copy = column(); copy.setPadding(dp(13), 0, 0, 0);
        full(copy, text(name(p), 16, DARK, true));
        String subtitle = p.demo() ? t("NÜMUNƏ • ", "SAMPLE • ") + p.quantity() : p.brand();
        if (!p.demo() && "BAZARSTORE".equals(p.source())) subtitle += p.ingredients().isEmpty()
            ? t(" • Tərkib yoxdur", " • Ingredients missing") : t(" • Sayt tərkibi", " • Web ingredients");
        full(copy, text(subtitle, 12, MUTED, false));
        top.addView(copy, new LinearLayout.LayoutParams(0, -2, 1)); c.addView(top);
        c.setContentDescription(name(p)); c.setOnClickListener(v -> {
            if (refreshFromApi && prefs.getBoolean("live", false) && !p.demo()) lookup(p.barcode()); else open(p);
        }); full(body, c); gap(body, 10);
    }
    private void open(Product p) { current = p; loading = false; store.remember(p); render(); }
    private void listPage() {
        boolean saved = tab.equals("saved");
        full(body, text(saved ? t("Seçilmişlər", "Saved products") : t("Son baxılanlar", "Recently viewed"), 28, DARK, true)); gap(body, 7);
        full(body, text(t("Bu cihazda saxlanılır. Offline da baxa bilərsiniz.", "Stored on this device, available offline."), 13, MUTED, false)); gap(body, 18);
        List<Product> rows = store.list(saved ? "saved" : "history");
        if (rows.isEmpty()) {
            LinearLayout empty = card(PALE); full(empty, text(t("Hələ məhsul yoxdur", "Nothing here yet"), 20, DARK, true)); gap(empty, 8);
            full(empty, text(t("Bir məhsula baxın və sonra onu yadda saxlayın.", "Open a product and save it to find it here."), 15, MUTED, false)); full(body, empty);
        } else for (Product p : rows) productCard(p, false);
    }
    private void loadingPage() {
        gap(body, 60); ProgressBar bar = new ProgressBar(this); body.addView(bar); gap(body, 20);
        full(body, text(t("Məhsul axtarılır…", "Looking up your product…"), 23, DARK, true)); gap(body, 8);
        full(body, text(t("Etiket bazasında barkodla axtarılır.", "Looking up the barcode in the Etiket database."), 14, MUTED, false));
    }
    private void productPage(Product p) {
        TextView back = text(t("‹ Geri", "‹ Back"), 15, GREEN, true); back.setPadding(0, dp(8), 0, dp(14));
        back.setOnClickListener(v -> { current = null; render(); }); full(body, back);
        boolean retailer = "BAZARSTORE".equals(p.source());
        boolean reviewed = "REVIEWED_LABEL".equals(p.source()) && p.evidence().labelPhotoChecked();
        pill(body, p.demo() ? t("UYDURMA NÜMUNƏ", "FICTIONAL SAMPLE") : reviewed ? t("YOXLANMIŞ ETİKET FOTOSU", "REVIEWED LABEL PHOTO") : retailer ? "BAZARSTORE · TEST" : "OPEN FOOD FACTS", PALE, GREEN); gap(body, 12);
        full(body, text(name(p), 29, DARK, true)); gap(body, 4);
        full(body, text(p.brand() + (p.quantity().isEmpty() ? "" : "  •  " + p.quantity()), 14, MUTED, false)); gap(body, 4);
        full(body, text(p.barcode(), 12, MUTED, false)); gap(body, 18);
        if (reviewed) {
            LinearLayout status = card(PALE); full(status, text(t("Etiket fotosu ilə yoxlanıb", "Checked against label photos"), 17, DARK, true)); gap(status, 5);
            full(status, text(t("Tərkib göndərilmiş qablaşdırma fotoları ilə müqayisə edilib. Aldığınız məhsulun cari etiketini də yoxlayın.",
                "Ingredients were compared with submitted package photos. Also check the current label on the product you buy."), 13, MUTED, false)); full(body, status); gap(body, 14);
        } else if (retailer) {
            LinearLayout status = card(0xFFFFF3DA);
            full(status, text(t("Qablaşdırma ilə yoxlanmayıb", "Not checked against package labels"), 17, DARK, true)); gap(status, 5);
            full(status, text(t("Mənbə mağazanın saytıdır. Tərkibi cari qablaşdırma ilə müqayisə etmək lazımdır.",
                "Source: the retailer website. Compare ingredients with the current package label."), 13, MUTED, false));
            full(body, status); gap(body, 14);
        }
        if (!retailer) {
        LinearLayout score = card(PALE);
        full(score, text(p.nutriScore().isEmpty() ? t("Nutri-Score məlum deyil", "Nutri-Score unavailable") : "Nutri-Score  " + p.nutriScore().toUpperCase(Locale.ROOT), 23, GREEN, true));
        gap(score, 5);
        full(score, text(p.demo() ? t("Bu dərəcə yalnız nümunə üçündür.", "This grade is for demonstration only.") :
            t("Mənbənin verdiyi qidalanma dərəcəsidir; təhlükəsizlik sertifikatı deyil.", "A nutrition grade reported by the source, not a safety certification."), 12, MUTED, false));
        full(body, score); gap(body, 14);
        }
        LinearLayout nutrition = card(WHITE); full(nutrition, text(t("Qida dəyərləri", "Nutrition"), 20, DARK, true));
        full(nutrition, text(p.nutrition().basis().equals("PER_100_ML") ? t("Mənbədə 100 ml üçün göstərilən miqdar", "Amounts reported per 100 ml by the source") : t("Mənbədə 100 q üçün göstərilən miqdar", "Amounts reported per 100 g by the source"), 12, MUTED, false)); gap(nutrition, 10);
        String[][] values = {{"energyKcal", "Enerji", "Energy", "kcal"}, {"fat", "Yağ", "Fat", "g"},
            {"saturatedFat", "Doymuş yağ", "Saturated fat", "g"}, {"carbohydrates", "Karbohidrat", "Carbohydrates", "g"},
            {"sugars", "Şəkərlər", "Sugars", "g"}, {"fiber", "Lif", "Fibre", "g"}, {"protein", "Zülal", "Protein", "g"}, {"salt", "Duz", "Salt", "g"}};
        if (p.nutrition().values().isEmpty()) full(nutrition, text(t("Bu mənbədə qida dəyərləri verilməyib.", "This source does not provide nutrition values."), 14, MUTED, false));
        else for (String[] nutrient : values) {
            LinearLayout line = row(); line.setPadding(0, dp(8), 0, dp(8));
            line.addView(text(t(nutrient[1], nutrient[2]), 14, MUTED, false), new LinearLayout.LayoutParams(0, -2, 1));
            Double number = p.nutrition().values().get(nutrient[0]);
            String amount = number == null ? t("Məlum deyil", "Unknown") : String.format(Locale.ROOT, "%.2f", number).replaceAll("0+$", "").replaceAll("\\.$", "") + " " + nutrient[3];
            line.addView(text(amount, 14, DARK, true)); full(nutrition, line);
        }
        LinearLayout ingredients = card(WHITE); full(ingredients, text(t("Tərkib", "Ingredients"), 20, DARK, true)); gap(ingredients, 8);
        String value = p.ingredients();
        if (!az && p.demo()) value = switch (p.barcode()) {
            case "2000000000015" -> "Wholegrain oats (100%).";
            case "2000000000022" -> "Wheat flour, sugar, vegetable oil, cocoa, milk powder, salt.";
            default -> "";
        };
        full(ingredients, text(value.isEmpty() ? t("Tərkib məlumatı yoxdur.", "No ingredients provided.") : value, 15, DARK, false)); gap(ingredients, 7);
        if (retailer) full(ingredients, text(value.isEmpty()
            ? t("Növbəti addım: qablaşdırmanın tərkib hissəsinin aydın fotosunu yoxlamaq.", "Next step: check a clear photo of the package ingredient label.")
            : t("Saytdan götürülmüş mətn • insan yoxlaması lazımdır", "Web-sourced text • needs human review"), 12, MUTED, false));
        if (p.evidence().externalCandidateAvailable()) {
            gap(ingredients, 7);
            full(ingredients, text(t("Xarici mənbədə yoxlanmamış tərkib variantı var. Burada təsdiqlənmiş tərkib kimi göstərilmir.",
                "An unreviewed foreign-source candidate exists. Package confirmation is still needed."), 12, MUTED, false));
        }
        if (!p.demo() && !value.isEmpty()) full(ingredients, text(t("Mənbənin orijinal mətni • dil: ", "Original source text • language: ")
            + (p.ingredientsLanguage().isEmpty() ? "?" : p.ingredientsLanguage()), 11, MUTED, false));
        gap(ingredients, 10); full(ingredients, text(t("Göstərilən allergenlər", "Listed allergens"), 14, DARK, true)); gap(ingredients, 4);
        full(ingredients, text(p.allergens().isEmpty() ? t("Məlum deyil; allergenlərin olmaması təsdiqlənməyib.", "Unknown; absence of allergens is not confirmed.") : tags(p.allergens()), 13, MUTED, false));
        if (!p.evidence().declaredAllergenStatement().isEmpty()) {
            gap(ingredients, 6); full(ingredients, text(p.evidence().declaredAllergenStatement(), 13, MUTED, false));
        }
        if (!p.traces().isEmpty()) { gap(ingredients, 5); full(ingredients, text(t("İz miqdarları: ", "Possible traces: ") + tags(p.traces()), 13, MUTED, false)); }
        full(body, ingredients); gap(body, 14);
        ingredientExplanations(p);
        full(body, nutrition); gap(body, 14);
        if (!p.demo()) { full(body, button(t("Etiket fotosu göndər", "Submit label photos"), false, () -> submitLabel(p.barcode(), p))); gap(body, 14); }
        full(body, button(store.contains("saved", p) ? t("Seçilmişlərdən çıxar", "Remove from saved") : t("Məhsulu yadda saxla", "Save product"), true,
            () -> { store.toggleSaved(p); render(); })); gap(body, 14);
        if (p.demo()) full(body, text(t("Nümunə məhsul və rəqəmlər uydurmadır. Real məhsul haqqında nəticə deyil.",
            "This product and its numbers are fictional fixtures, not information about a real product."), 12, MUTED, false));
        else {
            full(body, text(t("Qablaşdırmadakı etiketi yoxlayın. İctimai bazada məlumat natamam və ya köhnə ola bilər.",
                "Check the package label. Online data may be incomplete or out of date."), 12, MUTED, false)); gap(body, 7);
            if (!p.retrievedAt().isEmpty()) full(body, text(t("Alınma tarixi: ", "Retrieved: ") + p.retrievedAt().substring(0, Math.min(10, p.retrievedAt().length())), 11, MUTED, false));
            gap(body, 7);
            full(body, button(reviewed ? t("Yoxlanmış etiket fotolarını aç", "Open reviewed label photos") : retailer ? t("Bazarstore mənbəsini aç", "Open Bazarstore source") : t("Open Food Facts mənbəsini aç", "Open source on Open Food Facts"), false,
                () -> startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(ApiClient.resolveSource(prefs.getString("base", "http://10.0.2.2:8080"), p.sourceUrl()))))));
            gap(body, 7); full(body, text(reviewed ? t("Mənbə: yoxlanmış qablaşdırma fotoları", "Source: reviewed package photos") : retailer ? t("Mənbə: bazarstore.az • test üçün sayt məlumatları", "Source: bazarstore.az • web data for prototype testing")
                : "Data © Open Food Facts contributors • ODbL 1.0", 11, MUTED, false));
        }
    }
    private void ingredientExplanations(Product p) {
        IngredientAnalysis analysis = p.ingredientAnalysis();
        LinearLayout explanations = card(PALE);
        full(explanations, text(t("Tərkibin sadə izahı", "Ingredient explanations (Azerbaijani)"), 20, DARK, true));
        gap(explanations, 7);
        if (analysis.status().equals("MISSING")) {
            full(explanations, text(t("Tərkib siyahısı yoxdur; izah hazırlamaq mümkün deyil.",
                "There is no ingredient list to explain."), 14, MUTED, false));
        } else if (analysis.status().equals("UNSUPPORTED_LANGUAGE")) {
            full(explanations, text(t("Hazırda yalnız Azərbaycan dilindəki tərkib siyahıları izah olunur.",
                "This version explains ingredient lists written in Azerbaijani."), 14, MUTED, false));
        } else {
            full(explanations, text(t("Ümumi lüğət izahlarıdır. Məhsulun tərkibini təsdiqləmir. Mətnlər redaktə mərhələsindədir.",
                "General dictionary descriptions. They do not verify the product ingredients. Wording is an editorial draft."), 12, MUTED, false));
            gap(explanations, 7);
            full(explanations, text(t("İzahı olan hissələr: ", "Entries with explanations: ")
                + analysis.recognizedCount() + " / " + analysis.items().size(), 12, GREEN, true));
            Set<IngredientAnalysis.Reference> references = new LinkedHashSet<>();
            for (IngredientAnalysis.Item item : analysis.items()) {
                gap(explanations, 13);
                full(explanations, text(item.recognized() ? item.nameAz() : item.reportedText(), 15, DARK, true));
                if (!item.group().isEmpty()) full(explanations, text(item.group(), 11, MUTED, false));
                full(explanations, text(item.recognized() ? item.explanationAz()
                    : t("Bu hissə üçün lüğətdə izah yoxdur.", "This entry has no dictionary explanation yet."), 14, MUTED, false));
                references.addAll(item.references());
            }
            if (!references.isEmpty()) {
                gap(explanations, 16);
                full(explanations, text(t("İzahlar üçün istinadlar", "Explanation references"), 13, DARK, true));
                for (IngredientAnalysis.Reference reference : references) {
                    gap(explanations, 6);
                    full(explanations, button(reference.title(), false,
                        () -> startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(reference.url())))));
                }
            }
        }
        full(body, explanations); gap(body, 14);
    }
    private String tags(List<String> values) {
        Map<String, String> labels = new HashMap<>();
        labels.put("en:milk", t("süd", "milk")); labels.put("en:gluten", t("qlüten", "gluten"));
        labels.put("en:nuts", t("çərəzlər", "nuts")); labels.put("en:soybeans", t("soya", "soybeans"));
        labels.put("en:eggs", t("yumurta", "eggs")); labels.put("en:peanuts", t("yerfındığı", "peanuts"));
        labels.put("en:fish", t("balıq", "fish")); labels.put("en:sesame-seeds", t("küncüt", "sesame seeds"));
        List<String> result = new ArrayList<>();
        for (String key : values) result.add(labels.getOrDefault(key, key.replaceFirst("^[a-z]{2}:", "").replace('-', ' ')));
        return String.join(", ", result);
    }
    private void manual() {
        EditText input = new EditText(this); input.setSingleLine(); input.setHint("4760177560085");
        input.setInputType(android.text.InputType.TYPE_CLASS_NUMBER); input.setPadding(dp(22), dp(16), dp(22), dp(16));
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle(t("Barkodu yazın", "Enter a barcode")).setView(input)
            .setPositiveButton(t("Axtar", "Look up"), null).setNegativeButton(t("Ləğv et", "Cancel"), null).create();
        dialog.setOnShowListener(v -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view -> {
            try {
                String code = Barcode.normalize(input.getText().toString());
                dialog.dismiss(); ((InputMethodManager) getSystemService(INPUT_METHOD_SERVICE)).hideSoftInputFromWindow(input.getWindowToken(), 0); lookup(code);
            } catch (IllegalArgumentException e) { input.setError(t("Barkodun rəqəmlərini yoxlayın.", "Check the digits, including the final check digit.")); }
        })); dialog.show();
    }
    private void lookup(String raw) {
        String code;
        try { code = Barcode.normalize(raw); }
        catch (IllegalArgumentException e) { message(t("Barkod etibarlı deyil.", "This is not a valid product barcode.")); return; }
        for (Product p : DemoCatalog.products()) if (p.barcode().equals(code)) { open(p); return; }
        if (!prefs.getBoolean("live", false)) {
            for (Product p : catalogue) if (p.barcode().equals(code)) { open(p); return; }
            new AlertDialog.Builder(this).setTitle(t("Məhsul tapılmadı", "Product not found"))
                .setMessage(t("Bu barkod saxlanmış kataloqda yoxdur. Etiket fotosu göndərə və ya canlı axtarışı qoşa bilərsiniz.",
                    "This barcode is not in the saved catalogue. Submit label photos or enable live lookup."))
                .setPositiveButton(t("Etiket fotosu göndər", "Submit label photos"), (d, w) -> submitLabel(code, null))
                .setNeutralButton(t("Parametrlər", "Settings"), (d, w) -> settings()).setNegativeButton("OK", null).show(); return;
        }
        long token = ++requestId; loading = true; current = null; render();
        String base = prefs.getString("base", "http://10.0.2.2:8080");
        executor.execute(() -> {
            try {
                Product p = ApiClient.lookup(base, code);
                runOnUiThread(() -> { if (token == requestId && !isDestroyed()) open(p); });
            } catch (ApiClient.Failure e) {
                runOnUiThread(() -> {
                    if (token != requestId || isDestroyed()) return;
                    loading = false; render();
                    String error = switch (e.code) {
                        case "PRODUCT_NOT_FOUND" -> t("Məhsul bazada tapılmadı. Məlumat uydurulmur. Qablaşdırmadakı etiketi yoxlayın.", "This product is not in the database. No details have been guessed. Check its package label.");
                        case "LOOKUP_RATE_LIMITED" -> t("Bir neçə saniyə gözləyib yenidən sınayın.", "Wait a few seconds and try again.");
                        case "CONNECTION_FAILED" -> t("API-yə qoşulmaq mümkün olmadı. Backend-i və Parametrlərdəki ünvanı yoxlayın.", "Could not connect to your API. Check that the backend is running and the address in Settings is correct.");
                        default -> t("Mənbə hazırda cavab vermir. Bir az sonra yenidən sınayın.", "The data source is temporarily unavailable. Please try again later.");
                    };
                    if (e.code.equals("PRODUCT_NOT_FOUND")) new AlertDialog.Builder(this).setMessage(error)
                        .setPositiveButton(t("Etiket fotosu göndər", "Submit label photos"), (d, w) -> submitLabel(code, null)).setNegativeButton("OK", null).show();
                    else message(error);
                });
            }
        });
    }
    private void scan() {
        if (scannerBusy) return;
        scannerBusy = true;
        GmsBarcodeScannerOptions options = new GmsBarcodeScannerOptions.Builder()
            .setBarcodeFormats(com.google.mlkit.vision.barcode.common.Barcode.FORMAT_EAN_13,
                com.google.mlkit.vision.barcode.common.Barcode.FORMAT_EAN_8,
                com.google.mlkit.vision.barcode.common.Barcode.FORMAT_UPC_A).enableAutoZoom().build();
        GmsBarcodeScanner scanner = GmsBarcodeScanning.getClient(this, options);
        moduleClient = ModuleInstall.getClient(this);
        installDialog = new AlertDialog.Builder(this).setTitle(t("Skaner hazırlanır", "Preparing scanner"))
            .setMessage(t("İlk istifadədə skaner modulu endirilir. İnternet bağlantısını açıq saxlayın.",
                "The scanner module downloads on first use. Keep your internet connection on."))
            .setNegativeButton(t("Ləğv et", "Cancel"), (d, w) -> { scannerBusy = false; cleanupScannerInstall(); }).create();
        installDialog.setOnCancelListener(d -> { scannerBusy = false; cleanupScannerInstall(); });
        installDialog.show();
        moduleListener = new InstallStatusListener() {
            @Override public void onInstallStatusUpdated(ModuleInstallStatusUpdate update) {
                int state = update.getInstallState();
                if (state == ModuleInstallStatusUpdate.InstallState.STATE_COMPLETED) beginCamera(scanner);
                else if (state == ModuleInstallStatusUpdate.InstallState.STATE_FAILED
                    || state == ModuleInstallStatusUpdate.InstallState.STATE_CANCELED) scannerError();
            }
        };
        moduleClient.installModules(ModuleInstallRequest.newBuilder().addApi(scanner).setListener(moduleListener).build())
            .addOnSuccessListener(response -> {
                if (response.areModulesAlreadyInstalled()) beginCamera(scanner);
            }).addOnFailureListener(e -> scannerError());
    }
    private void beginCamera(GmsBarcodeScanner scanner) {
        if (!scannerBusy || cameraOpen || isDestroyed()) return;
        cameraOpen = true; cleanupScannerInstall();
        scanner.startScan().addOnSuccessListener(barcode -> {
            scannerBusy = false; cameraOpen = false; if (!isDestroyed()) lookup(barcode.getRawValue());
        }).addOnCanceledListener(() -> { scannerBusy = false; cameraOpen = false; })
            .addOnFailureListener(e -> scannerError());
    }
    private void cleanupScannerInstall() {
        if (moduleClient != null && moduleListener != null) { moduleClient.unregisterListener(moduleListener); moduleListener = null; }
        if (installDialog != null) { installDialog.dismiss(); installDialog = null; }
    }
    private void scannerError() {
        scannerBusy = false; cameraOpen = false; cleanupScannerInstall();
        if (isDestroyed()) return;
        new AlertDialog.Builder(this).setTitle(t("Skaneri açmaq mümkün olmadı", "Scanner unavailable"))
            .setMessage(t("Google Play services və ilk istifadə üçün internet lazımdır. Barkodu əl ilə də yaza bilərsiniz.",
                "Google Play services and an internet connection for the first scanner download are needed. You can also enter the barcode manually."))
            .setPositiveButton(t("Əl ilə yaz", "Enter manually"), (d, w) -> manual()).setNegativeButton("OK", null).show();
    }
    private void settings() {
        LinearLayout panel = column(); panel.setPadding(dp(22), dp(12), dp(22), dp(12));
        Switch live = new Switch(this); live.setText(t("Canlı məhsul axtarışı", "Live product lookup")); live.setChecked(prefs.getBoolean("live", false));
        full(panel, live); gap(panel, 14);
        full(panel, text(t("API ünvanı", "API address"), 14, DARK, true));
        EditText base = new EditText(this); base.setSingleLine(); base.setText(prefs.getString("base", "http://10.0.2.2:8080"));
        base.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_VARIATION_URI); full(panel, base); gap(panel, 8);
        full(panel, text(t("Emulator: 10.0.2.2:8080\nUSB + adb reverse: 127.0.0.1:8080\nİctimai server üçün HTTPS istifadə edin.",
            "Emulator: 10.0.2.2:8080\nUSB + adb reverse: 127.0.0.1:8080\nUse HTTPS for a public server."), 12, MUTED, false)); gap(panel, 14);
        full(panel, text(t("Tarixçə və seçilmişlər cihazda qalır. Foto göndərmə yalnız Yoxlamaya göndər düyməsini seçəndə edilir.",
            "History and saved products stay on this device. Photos are uploaded only when you choose Submit for review."), 12, MUTED, false)); gap(panel, 12);
        full(panel, text("Etiket " + BuildConfig.VERSION_NAME + "\nStarter catalogue: bazarstore.az\nOptional provider: Open Food Facts (ODbL 1.0)", 11, MUTED, false));
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle(t("Parametrlər", "Settings")).setView(panel)
            .setPositiveButton(t("Saxla", "Save"), null).setNegativeButton(t("Bağla", "Close"), null)
            .setNeutralButton(t("Yerli məlumatı sil", "Clear local data"), (d, w) -> {
                new AlertDialog.Builder(this).setMessage(t("Bu cihazdakı tarixçə, seçilmişlər, kataloq və göndərmə qəbzləri silinsin? Serverdəki göndərmələr qalır.", "Clear device history, saved products, catalogue and submission receipts? Server submissions remain."))
                    .setPositiveButton(t("Sil", "Clear"), (dd, ww) -> { store.clear(); catalogue = StarterCatalogue.load(this); current = null; render(); })
                    .setNegativeButton(t("Ləğv et", "Cancel"), null).show();
            }).create();
        dialog.setOnShowListener(v -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(b -> {
            try {
                String address = ApiClient.validateBase(base.getText().toString());
                prefs.edit().putString("base", address).putBoolean("live", live.isChecked()).apply();
                requestId++; loading = false; dialog.dismiss(); render(); if (live.isChecked()) refreshCatalogue(true);
            } catch (IllegalArgumentException e) { base.setError(t("Tam ünvan yazın: http://10.0.2.2:8080", "Use a full address: http://10.0.2.2:8080 (HTTP is for local addresses only).")); }
        })); dialog.show();
    }
    private void message(String value) { new AlertDialog.Builder(this).setMessage(value).setPositiveButton("OK", null).show(); }
    private final class BarcodeArt extends View {
        private final Paint paint = new Paint(3);
        private final Path tick = new Path();
        private final int[] widths = {2,1,3,1,2,4,1,2,1,3,2,1,4,1,2,3,1,2,1};
        BarcodeArt(Context c) { super(c); setContentDescription(t("Barkod illüstrasiyası", "Barcode illustration")); }
        @Override protected void onDraw(Canvas canvas) {
            super.onDraw(canvas); float x = getWidth() - dp(160), y = dp(16);
            paint.setColor(WHITE); canvas.drawRoundRect(x, y, x + dp(146), y + dp(76), dp(13), dp(13), paint);
            float left = x + dp(15);
            paint.setColor(DARK);
            for (int width : widths) { canvas.drawRect(left, y + dp(15), left + dp(width), y + dp(60), paint); left += dp(width + 2); }
            paint.setColor(GREEN); canvas.drawRoundRect(x - dp(20), y + dp(29), x + dp(35), y + dp(64), dp(12), dp(12), paint);
            paint.setColor(WHITE); paint.setStrokeWidth(dp(3)); paint.setStyle(Paint.Style.STROKE);
            tick.reset(); tick.moveTo(x - dp(6), y + dp(46)); tick.lineTo(x + dp(3), y + dp(53)); tick.lineTo(x + dp(18), y + dp(38));
            canvas.drawPath(tick, paint); paint.setStyle(Paint.Style.FILL);
        }
    }
}
