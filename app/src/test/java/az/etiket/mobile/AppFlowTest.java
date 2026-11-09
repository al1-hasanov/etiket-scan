package az.etiket.mobile;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.view.*;
import android.widget.TextView;
import android.widget.EditText;
import android.app.AlertDialog;
import android.content.Context;
import az.etiket.core.*;
import java.io.*;
import org.junit.*;
import static org.junit.Assert.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.shadows.ShadowAlertDialog;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, qualifiers = "w360dp-h800dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class AppFlowTest {
    @Before public void clearData() {
        RuntimeEnvironment.getApplication().getSharedPreferences("etiket", Context.MODE_PRIVATE).edit()
            .clear().putBoolean("live", false).commit();
    }
    @Test public void cleanInstallDefaultsToRailwayAndLiveLookup() {
        var prefs = RuntimeEnvironment.getApplication().getSharedPreferences("etiket", Context.MODE_PRIVATE);
        prefs.edit().clear().commit();
        try (ActivityController<MainActivity> c = Robolectric.buildActivity(MainActivity.class).create().start()) {
            View root = c.get().getWindow().getDecorView();
            assertNotNull(find(root, "● Canlı API"));
            click(root, "Parametrlər");
            AlertDialog dialog = (AlertDialog) ShadowAlertDialog.getLatestDialog();
            assertEquals(ApiClient.DEFAULT_BASE, firstEditText(dialog.getWindow().getDecorView()).getText().toString());
        }
    }
    @Test public void conflictIsShownInTheProductPanelWithThePhotoOption() {
        var prefs = RuntimeEnvironment.getApplication().getSharedPreferences("etiket", Context.MODE_PRIVATE);
        Product p = StarterCatalogue.load(RuntimeEnvironment.getApplication()).stream()
            .filter(x -> x.barcode().equals("4760177560085")).findFirst().orElseThrow();
        new ProductStore(prefs).cacheCatalogue(ApiClient.DEFAULT_BASE,
            java.util.List.of(p.withNutrition(NutritionLabel.empty(), NutritionSource.conflict())));
        try (ActivityController<MainActivity> c = Robolectric.buildActivity(MainActivity.class).setup()) {
            click(c.get().getWindow().getDecorView(), "EN");
            click(c.get().getWindow().getDecorView(), p.name());
            View root = c.get().getWindow().getDecorView();
            assertNotNull(find(root, "Nutrition reports disagree"));
            assertNotNull(find(root, "Submit label photos"));
            assertNull(find(root, "Amounts reported per 100 g by the source"));
        }
    }
    @Test public void languageAndSavedProductsSurviveRecreation() throws Exception {
        try (ActivityController<MainActivity> controller = Robolectric.buildActivity(MainActivity.class).setup()) {
            MainActivity a = controller.get(); View root = a.getWindow().getDecorView();
            click(root, "EN"); click(a.getWindow().getDecorView(), "Fictional demo samples"); click(a.getWindow().getDecorView(), "Plain oats");
            click(a.getWindow().getDecorView(), "Save product");
            controller.recreate();
            click(controller.get().getWindow().getDecorView(), "Saved");
            assertNotNull(find(controller.get().getWindow().getDecorView(), "Plain oats"));
            click(controller.get().getWindow().getDecorView(), "Plain oats");
            assertNotNull(find(controller.get().getWindow().getDecorView(), "Remove from saved"));
        }
    }
    @Test public void incompleteSampleShowsUnknownNotZero() {
        try (ActivityController<MainActivity> controller = Robolectric.buildActivity(MainActivity.class).setup()) {
            View root = controller.get().getWindow().getDecorView(); click(root, "EN");
            click(controller.get().getWindow().getDecorView(), "Fictional demo samples");
            click(controller.get().getWindow().getDecorView(), "Drink with incomplete data");
            root = controller.get().getWindow().getDecorView();
            assertNotNull(find(root, "Unknown")); assertNotNull(find(root, "No ingredients provided."));
            assertNotNull(find(root, "Nutri-Score unavailable"));
        }
    }
    @Test public void deviceHistoryDeduplicatesAndPersistsCompleteProduct() {
        ProductStore store = new ProductStore(RuntimeEnvironment.getApplication().getSharedPreferences("etiket", Context.MODE_PRIVATE));
        Product p = DemoCatalog.products().get(0); store.remember(p); store.remember(p); store.toggleSaved(p);
        ProductStore reloaded = new ProductStore(RuntimeEnvironment.getApplication().getSharedPreferences("etiket", Context.MODE_PRIVATE));
        assertEquals(1, reloaded.list("history").size()); assertEquals(p, reloaded.list("saved").get(0));
        reloaded.toggleSaved(p); assertTrue(reloaded.list("saved").isEmpty());
    }
    @Test public void endpointValidationAllowsLocalDevelopmentAndRequiresHttpsElsewhere() {
        assertEquals("http://10.0.2.2:8080", ApiClient.validateBase("http://10.0.2.2:8080/"));
        assertEquals("https://api.example.com", ApiClient.validateBase("https://api.example.com"));
        for (String value : new String[]{"http://api.example.com", "http://192.168.999.1", "https://user:pass@example.com", "file:///tmp", "https://example.com?x=1"}) {
            try { ApiClient.validateBase(value); fail(value); } catch (IllegalArgumentException expected) { }
        }
    }
    @Test public void renderHomeAndProductForInspection() throws Exception {
        try (ActivityController<MainActivity> controller = Robolectric.buildActivity(MainActivity.class).setup()) {
            MainActivity a = controller.get(); capture(a, "home-az.png");
            click(a.getWindow().getDecorView(), "EN"); capture(a, "home-en.png");
            click(a.getWindow().getDecorView(), "N1 ŞOR 400 Q QAYMAQLI"); capture(a, "product-en.png");
            click(a.getWindow().getDecorView(), "‹ Back");
            click(a.getWindow().getDecorView(), "7 DAYS MİDİ KRUASSAN 65 Q BİŞMİŞ SÜDLÜ"); capture(a, "missing-en.png");
        }
    }
    @Test public void bundledCatalogueAndSavedEvidenceMatchBackendSeed() {
        var products = StarterCatalogue.load(RuntimeEnvironment.getApplication());
        assertEquals(77, products.size());
        assertEquals(10, products.stream().filter(p -> !p.ingredients().isEmpty()).count());
        Product p = products.stream().filter(x -> x.barcode().equals("5201360553457")).findFirst().orElseThrow();
        assertTrue(p.ingredients().isEmpty()); assertTrue(p.evidence().externalCandidateAvailable());
        assertFalse(p.evidence().labelPhotoChecked());
        ProductStore store = new ProductStore(RuntimeEnvironment.getApplication().getSharedPreferences("etiket", Context.MODE_PRIVATE));
        store.toggleSaved(p); assertEquals(p, store.list("saved").get(0));
    }
    @Test public void realProductScreensDistinguishWebIngredientsFromMissing() {
        try (ActivityController<MainActivity> c = Robolectric.buildActivity(MainActivity.class).setup()) {
            click(c.get().getWindow().getDecorView(), "EN");
            click(c.get().getWindow().getDecorView(), "N1 ŞOR 400 Q QAYMAQLI");
            View root = c.get().getWindow().getDecorView();
            assertNotNull(find(root, "Not checked against package labels"));
            assertNotNull(find(root, "Web-sourced text • needs human review"));
            assertNotNull(find(root, "Open Bazarstore source"));
            click(root, "‹ Back"); click(c.get().getWindow().getDecorView(), "7 DAYS MİDİ KRUASSAN 65 Q BİŞMİŞ SÜDLÜ");
            root = c.get().getWindow().getDecorView();
            assertNotNull(find(root, "No ingredients provided."));
            assertNotNull(find(root, "An unreviewed foreign-source candidate exists. Package confirmation is still needed."));
        }
    }
    @Test public void catalogueSearchFindsExactBarcodeAndReportsNoMatches() {
        try (ActivityController<MainActivity> c = Robolectric.buildActivity(MainActivity.class).setup()) {
            click(c.get().getWindow().getDecorView(), "EN");
            click(c.get().getWindow().getDecorView(), "Catalogue");
            View root = c.get().getWindow().getDecorView();
            EditText search = firstEditText(root); assertNotNull(search);
            search.setText("5201360553457");
            assertNotNull(find(root, "7 DAYS MİDİ KRUASSAN 65 Q BİŞMİŞ SÜDLÜ"));
            assertNull(find(root, "N1 ŞOR 400 Q QAYMAQLI"));
            search.setText("no-match-987654321");
            assertNotNull(find(root, "No match in this test catalogue."));
        }
    }
    @Test public void manuallyEnteredCatalogueBarcodeWorksWithoutAnApi() {
        try (ActivityController<MainActivity> c = Robolectric.buildActivity(MainActivity.class).setup()) {
            click(c.get().getWindow().getDecorView(), "EN");
            click(c.get().getWindow().getDecorView(), "Enter barcode manually");
            Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
            AlertDialog dialog = (AlertDialog) ShadowAlertDialog.getLatestDialog();
            firstEditText(dialog.getWindow().getDecorView()).setText("4760177560085");
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
            Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
            assertNotNull(find(c.get().getWindow().getDecorView(), "inək südü, bitki yağı, maya, duz, qara çörək otu"));
        }
    }
    @Test public void savedCatalogueProductsOpenOfflineEvenWhenLiveModeIsEnabled() {
        var prefs = RuntimeEnvironment.getApplication().getSharedPreferences("etiket", Context.MODE_PRIVATE);
        Product p = StarterCatalogue.load(RuntimeEnvironment.getApplication()).stream()
            .filter(x -> x.barcode().equals("4760177560085")).findFirst().orElseThrow();
        new ProductStore(prefs).toggleSaved(p);
        prefs.edit().putBoolean("live", true).putString("base", "http://127.0.0.1:1").putBoolean("az", false).commit();
        try (ActivityController<MainActivity> c = Robolectric.buildActivity(MainActivity.class).setup()) {
            click(c.get().getWindow().getDecorView(), "Saved");
            click(c.get().getWindow().getDecorView(), p.name());
            assertNotNull(find(c.get().getWindow().getDecorView(), "Web-sourced text • needs human review"));
        }
    }
    @Test public void ingredientExplanationsAppearOfflineWithoutChangingReviewStatus() throws Exception {
        try (ActivityController<MainActivity> c = Robolectric.buildActivity(MainActivity.class).setup()) {
            click(c.get().getWindow().getDecorView(), "N1 ŞOR 400 Q QAYMAQLI");
            View root = c.get().getWindow().getDecorView();
            assertNotNull(find(root, "Tərkibin sadə izahı"));
            assertNotNull(find(root, "İzahı olan hissələr: 5 / 5"));
            assertNotNull(find(root, "İnək südü"));
            assertNotNull(find(root, "Qablaşdırma ilə yoxlanmayıb"));
            capture(c.get(), "ingredients-az.png");
            click(root, "Məhsulu yadda saxla");
            Product saved = new ProductStore(RuntimeEnvironment.getApplication().getSharedPreferences("etiket", Context.MODE_PRIVATE)).list("saved").get(0);
            assertEquals(5, saved.ingredientAnalysis().recognizedCount());
            assertFalse(saved.evidence().labelPhotoChecked());
        }
    }
    @Test public void missingIngredientsNeverProduceExplanations() {
        try (ActivityController<MainActivity> c = Robolectric.buildActivity(MainActivity.class).setup()) {
            click(c.get().getWindow().getDecorView(), "7 DAYS MİDİ KRUASSAN 65 Q BİŞMİŞ SÜDLÜ");
            View root = c.get().getWindow().getDecorView();
            assertNotNull(find(root, "Tərkib siyahısı yoxdur; izah hazırlamaq mümkün deyil."));
            assertNull(find(root, "İnək südü"));
        }
    }
    @Test public void partialDictionaryLeavesUnknownTextVisibleInEnglishUi() {
        try (ActivityController<MainActivity> c = Robolectric.buildActivity(MainActivity.class).setup()) {
            click(c.get().getWindow().getDecorView(), "EN");
            click(c.get().getWindow().getDecorView(), "Catalogue");
            click(c.get().getWindow().getDecorView(), "ERMI YOQURT İÇMƏLİ 290 Q MEŞƏ GİLƏMEYVƏ");
            View root = c.get().getWindow().getDecorView();
            assertNotNull(find(root, "Ingredient explanations (Azerbaijani)"));
            assertNotNull(find(root, "This entry has no dictionary explanation yet."));
            assertNotNull(find(root, "konsentrasiyalı yerkökü"));
        }
    }
    private static EditText firstEditText(View v) {
        if (v instanceof EditText e) return e;
        if (v instanceof ViewGroup group) for (int i = 0; i < group.getChildCount(); i++) {
            EditText result = firstEditText(group.getChildAt(i)); if (result != null) return result;
        }
        return null;
    }
    private static TextView find(View v, String text) {
        if (v instanceof TextView t && t.getText().toString().equals(text)) return t;
        if (v instanceof ViewGroup group) for (int i = 0; i < group.getChildCount(); i++) {
            TextView result = find(group.getChildAt(i), text); if (result != null) return result;
        }
        return null;
    }
    private static void click(View root, String text) {
        TextView target = find(root, text); assertNotNull("Missing text: " + text, target);
        View clickable = target;
        while (!clickable.isClickable() && clickable.getParent() instanceof View) clickable = (View) clickable.getParent();
        assertTrue("Not clickable: " + text, clickable.performClick());
    }
    private static void capture(MainActivity a, String file) throws Exception {
        View view = a.getWindow().getDecorView();
        view.measure(View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(2400, View.MeasureSpec.EXACTLY));
        view.layout(0, 0, 1080, 2400);
        if (file.startsWith("ingredients-")) view = (View) find(view, "Tərkibin sadə izahı").getParent();
        Bitmap bitmap = Bitmap.createBitmap(view.getWidth(), view.getHeight(), Bitmap.Config.ARGB_8888); view.draw(new Canvas(bitmap));
        File dir = new File("build/qa"); dir.mkdirs();
        try (OutputStream out = new FileOutputStream(new File(dir, file))) { bitmap.compress(Bitmap.CompressFormat.PNG, 100, out); }
    }
}
