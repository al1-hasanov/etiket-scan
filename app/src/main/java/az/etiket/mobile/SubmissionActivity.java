package az.etiket.mobile;

import android.app.*;
import android.content.*;
import android.graphics.Color;
import android.graphics.Insets;
import android.net.Uri;
import android.os.*;
import android.view.*;
import android.widget.*;
import androidx.activity.*;
import androidx.activity.result.*;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.core.content.FileProvider;
import androidx.lifecycle.ViewModelProvider;
import az.etiket.core.Barcode;
import az.etiket.core.NutritionLabel;
import java.io.File;
import java.math.BigDecimal;
import java.util.*;
import org.json.*;

public class SubmissionActivity extends ComponentActivity {
    private static final String[] NUTRITION_AZ = {"Enerji (kcal)", "Yağ (q)", "Doymuş yağ (q)", "Karbohidrat (q)", "Şəkərlər (q)", "Lif (q)", "Zülal (q)", "Duz (q)"};
    private static final String[] NUTRITION_EN = {"Energy (kcal)", "Fat (g)", "Saturated fat (g)", "Carbohydrates (g)", "Sugars (g)", "Fibre (g)", "Protein (g)", "Salt (g)"};
    private SubmissionState state;
    private boolean az;
    private LinearLayout form;
    private LinearLayout optionalText;
    private EditText barcode, name, brand, quantity, ingredients, allergens;
    private Spinner language;
    private Spinner nutritionBasis;
    private final Map<String,EditText> nutritionFields=new LinkedHashMap<>();
    private final Map<String,TextView> analysisPreviews=new HashMap<>();
    private final Map<String,Button> applyButtons=new HashMap<>();
    private TextView nutritionSummary;
    private Button nutritionRawToggle;
    private boolean showNutritionRaw;
    private NutritionReviewDialog nutritionReview;
    private boolean readAfterCrop;
    private CheckBox consent;
    private Button submit;
    private TextView feedback;
    private final Map<String, ImageView> previews = new HashMap<>();
    private final List<Button> photoButtons = new ArrayList<>();
    private final String[] languages = {"az", "ru", "en", "tr", "de", "und"};
    private boolean receiptShown;
    private final ActivityResultLauncher<String[]> gallery = registerForActivityResult(new ActivityResultContracts.OpenDocument(), uri -> {
        if (uri != null && state != null) state.photo(uri, state.pendingRole);
    });
    private final ActivityResultLauncher<Uri> camera = registerForActivityResult(new ActivityResultContracts.TakePicture() {
        @Override public Intent createIntent(Context context, Uri uri) {
            Intent intent = super.createIntent(context, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
            intent.setClipData(ClipData.newRawUri("Package photo", uri)); return intent;
        }
    }, saved -> {
        if (saved && state != null) {
            File file = new File(state.directory(), "camera-original.jpg");
            state.photo(FileProvider.getUriForFile(this, getPackageName() + ".labelphotos", file), state.pendingRole);
        }
    });

    private final ActivityResultLauncher<Intent> cropEditor = registerForActivityResult(
        new ActivityResultContracts.StartActivityForResult(), result -> {
            Intent data = result.getData();
            if (result.getResultCode() == RESULT_OK && data != null && state != null) {
                String role = data.getStringExtra("role");
                if (state.pendingRole.equals(role)) state.commitCrop(role, readAfterCrop);
            }
            readAfterCrop = false;
        });

    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        az = getSharedPreferences("etiket", MODE_PRIVATE).getBoolean("az", true);
        state = new ViewModelProvider(this, new ViewModelProvider.AndroidViewModelFactory(getApplication())).get(SubmissionState.class);
        if (saved != null && !state.busy) state.restore(saved.getString("draftId"));
        if (saved != null) {
            state.pendingRole = saved.getString("role", "front");
            readAfterCrop = saved.getBoolean("readAfterCrop");
            showNutritionRaw = saved.getBoolean("showNutritionRaw");
        }
        build(saved);
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override public void handleOnBackPressed() {
                if (state.busy) Toast.makeText(SubmissionActivity.this, t("Göndərmənin bitməsini gözləyin.", "Please wait for the current operation."), Toast.LENGTH_SHORT).show();
                else { state.cleanFiles(); finish(); }
            }
        });
        state.event.observe(this, event -> {
            submit.setEnabled(!event.busy()); for (Button button : photoButtons) button.setEnabled(!event.busy());
            feedback.setText(event.busy() ? t("Foto hazırlanır və ya göndərilir…", "Preparing or uploading photos…") : error(event.error()));
            updatePhotos();
            updateAnalysis();
            if (!event.busy() && !state.readAfterCommit.isEmpty()) {
                String role = state.readAfterCommit;
                state.readAfterCommit = "";
                analyze(role);
            }
            if (event.receipt() != null && !receiptShown) {
                receiptShown = true;
                boolean pending = event.receipt().optString("status").equals("PENDING");
                new AlertDialog.Builder(this).setTitle(pending ? t("Yoxlamaya göndərildi", "Submitted for review") : t("Göndərmənin vəziyyəti yeniləndi", "Submission status updated"))
                    .setMessage(pending ? t("Məlumat yoxlanmadan kataloqa əlavə olunmayacaq. Vəziyyəti Göndərdiklərim bölməsində izləyə bilərsiniz.",
                        "It will appear in the catalogue only after review. Track it in My submissions.") : t("Bu göndərmə artıq yoxlanıb. Nəticəyə Göndərdiklərim bölməsində baxın.","This submission has already been reviewed. See its result in My submissions."))
                    .setCancelable(false).setPositiveButton("OK", (dialog, which) -> { state.cleanFiles(); setResult(RESULT_OK); finish(); }).show();
            }
        });
        state.analysisEvent.observe(this, event -> updateAnalysis());
        if (saved != null && saved.containsKey("nutritionReview")) reviewNutrition(saved.getBundle("nutritionReview"));
    }
    private String t(String a, String e) { return az ? a : e; }
    private int dp(int size) { return Math.round(size * getResources().getDisplayMetrics().density); }
    private void addText(String value, int size, boolean bold) {
        TextView view = new TextView(this); view.setText(value); view.setTextSize(size); view.setTextColor(0xff19372a);
        view.setPadding(0, dp(8), 0, dp(8)); if (bold) view.setTypeface(null, android.graphics.Typeface.BOLD); form.addView(view);
    }
    private EditText field(String title, String value, @androidx.annotation.IdRes int id, boolean multiline) {
        addText(title, 14, true); EditText input = new EditText(this); input.setId(id); input.setText(value);
        input.setTextSize(16); input.setSingleLine(!multiline); if (multiline) { input.setMinLines(3); input.setGravity(Gravity.TOP); }
        form.addView(input, new LinearLayout.LayoutParams(-1, -2)); return input;
    }
    private void build(Bundle saved) {
        LinearLayout outer = new LinearLayout(this); outer.setOrientation(LinearLayout.VERTICAL); outer.setBackgroundColor(0xfff6f8f5);
        outer.setPadding(dp(20), dp(16), dp(20), dp(16)); setContentView(outer);
        if (Build.VERSION.SDK_INT >= 30) {
            getWindow().setDecorFitsSystemWindows(false);
            outer.setOnApplyWindowInsetsListener((view, insets) -> {
                Insets bars = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.ime());
                outer.setPadding(dp(20)+bars.left, dp(16)+bars.top, dp(20)+bars.right, dp(16)+bars.bottom); return insets;
            });outer.requestApplyInsets();
        }
        ScrollView scroll = new ScrollView(this); outer.addView(scroll, new LinearLayout.LayoutParams(-1, -1));
        form = new LinearLayout(this); form.setOrientation(LinearLayout.VERTICAL); scroll.addView(form);
        addText(t("Etiket fotosu göndər", "Submit package photos"), 25, true);
        addText(t("Ön tərəf, barkod və tərkib aydın görünsün. Şəxsi məlumat, üz və qəbz göndərməyin. Bir arxa tərəf fotosu həm barkod, həm də tərkib üçün seçilə bilər.",
            "Show the front, barcode and ingredients clearly. Photograph only packaging, without faces, receipts or personal details. One clear back photo may be used for both barcode and ingredients."), 14, false);
        barcode = field(t("Barkod", "Barcode"), value(saved,"barcode"), R.id.submission_barcode, false); barcode.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        name = field(t("Məhsulun adı", "Product name"), value(saved,"name"), R.id.submission_name, false);
        brand = field(t("Brend", "Brand"), value(saved,"brand"), R.id.submission_brand, false);
        quantity = field(t("Qablaşdırma ölçüsü (məsələn, 65 q)", "Pack size (for example, 65 g)"), value(saved,"quantity"), R.id.submission_quantity, false);
        addText(t("Tərkib etiketinin dili", "Ingredient label language"), 14, true);
        language = new Spinner(this); language.setId(R.id.submission_language); language.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item,
            new String[]{"Azərbaycan dili", "Русский", "English", "Türkçe", "Deutsch", t("Digər", "Other")}));form.addView(language);
        if (saved != null) language.setSelection(saved.getInt("language",0));
        LinearLayout mainForm = form; optionalText = new LinearLayout(this); optionalText.setOrientation(LinearLayout.VERTICAL); form = optionalText;
        ingredients = field(t("Etiketdəki tərkib mətni (istəyə görə)", "Ingredient text from the label (optional)"), value(saved,"ingredients"), R.id.submission_ingredients, true);
        allergens = field(t("Etiketdəki allergen mətni (istəyə görə)", "Allergen statement from the label (optional)"), value(saved,"allergens"), R.id.submission_allergens, true);
        form = mainForm;
        EditText[] limited={barcode,name,brand,quantity,ingredients,allergens};int[] limits={24,200,200,80,10000,2000};
        for(int i=0;i<limited.length;i++)limited[i].setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(limits[i])});
        for (String role : SubmissionClient.PHOTO_ROLES) {
            addText(switch(role){case "front"->t("1. Ön tərəf", "1. Front of pack");case "barcode"->t("2. Barkod fotosu", "2. Barcode photo");case "nutrition"->t("4. Qida dəyərləri fotosu (istəyə görə)","4. Nutrition table photo (optional)");default->t("3. Tərkib etiketi", "3. Ingredient label");},17,true);
            ImageView image = new ImageView(this); image.setAdjustViewBounds(true); image.setScaleType(ImageView.ScaleType.FIT_CENTER);
            image.setContentDescription(role); form.addView(image,new LinearLayout.LayoutParams(-1,dp(150)));previews.put(role,image);
            Button button = new Button(this); button.setAllCaps(false);button.setText(t("Foto seç və ya çək", "Choose or take photo"));
            button.setOnClickListener(view->choose(role));form.addView(button);photoButtons.add(button);
            if(SubmissionState.canCrop(role)){
                Button crop = new Button(this);
                crop.setAllCaps(false);
                crop.setText(t("Etiketi kəs", "Crop label"));
                crop.setOnClickListener(view -> editPhoto(role, false));
                form.addView(crop);
                photoButtons.add(crop);
                addText(t("Oxuma üçün internet lazımdır. Məlumat yalnız yoxlamadan sonra dərc olunur.","Reading needs internet. Information is published only after review."),13,false);
                Button read=new Button(this);read.setAllCaps(false);read.setText(t("Fotodan mətni oxu", "Read text from photo"));
                if(role.equals("ingredients"))read.setId(R.id.read_ingredients);
                read.setOnClickListener(view -> {
                    if (state.hasCrop(role)) analyze(role);
                    else editPhoto(role, true);
                });
                form.addView(read);photoButtons.add(read);
                TextView extracted=new TextView(this);extracted.setTextSize(14);extracted.setTextIsSelectable(true);extracted.setPadding(dp(10),dp(10),dp(10),dp(10));
                extracted.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
                boolean nutrition = role.equals("nutrition");
                analysisPreviews.put(role, extracted);
                Button use = new Button(this);
                use.setAllCaps(false);
                use.setText(nutrition ? t("Qida dəyərləri təkliflərinə bax", "Review suggested nutrition values")
                    : t("Mətni tərkib sahəsinə köçür", "Use extracted ingredient text"));
                use.setId(nutrition ? R.id.use_ocr_nutrition : R.id.use_ocr_ingredients);
                use.setOnClickListener(view -> applyAnalysis(role));
                if (!nutrition) form.addView(extracted);
                if (nutrition) {
                    nutritionSummary = new TextView(this);
                    nutritionSummary.setId(R.id.nutrition_analysis_summary);
                    nutritionSummary.setTextSize(14);
                    nutritionSummary.setPadding(dp(10), dp(10), dp(10), dp(10));
                    nutritionSummary.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
                    form.addView(nutritionSummary);
                }
                form.addView(use);
                if (nutrition) {
                    nutritionRawToggle = new Button(this);
                    nutritionRawToggle.setId(R.id.nutrition_raw_toggle);
                    nutritionRawToggle.setAllCaps(false);
                    nutritionRawToggle.setOnClickListener(view -> { showNutritionRaw = !showNutritionRaw; updateAnalysis(); });
                    form.addView(nutritionRawToggle);
                    form.addView(extracted);
                }
                applyButtons.put(role, use);
            }
        }
        Button optionalButton = new Button(this); optionalButton.setAllCaps(false); optionalButton.setText(t("Etiket mətnini də yaz (istəyə görə)","Add label text (optional)"));
        optionalButton.setOnClickListener(view -> optionalText.setVisibility(optionalText.getVisibility()==View.VISIBLE?View.GONE:View.VISIBLE));form.addView(optionalButton);
        optionalText.setVisibility(saved != null && saved.getBoolean("optionalExpanded") ? View.VISIBLE : View.GONE); form.addView(optionalText);
        addText(t("Qida dəyərləri — etiketdəki rəqəmlər", "Nutrition — values printed on the label"),19,true);
        addText(t("Yalnız etiketdə göstərilənləri yazın. Boş sahə məlum deyil deməkdir. Porsiya üçün verilən rəqəmləri bura köçürməyin.","Enter only values shown on the label. Leave unknown fields blank. These fields accept per-100-g or per-100-ml values, not per-serving amounts."),14,false);
        nutritionBasis=new Spinner(this);nutritionBasis.setId(R.id.nutrition_basis);nutritionBasis.setAdapter(new ArrayAdapter<>(this,android.R.layout.simple_spinner_dropdown_item,new String[]{t("Əsas miqdar seçilməyib","Reference amount unknown"),"100 g","100 ml"}));form.addView(nutritionBasis);
        if(saved!=null)nutritionBasis.setSelection(saved.getInt("nutritionBasis",0));
        int[] ids={R.id.nutrition_energy,R.id.nutrition_fat,R.id.nutrition_saturated,R.id.nutrition_carbs,R.id.nutrition_sugars,R.id.nutrition_fibre,R.id.nutrition_protein,R.id.nutrition_salt};
        for(int i=0;i<NutritionLabel.KEYS.size();i++){
            String key=NutritionLabel.KEYS.get(i);EditText input=field(t(NUTRITION_AZ[i],NUTRITION_EN[i]),saved==null?"":saved.getString("nutrition-"+key,""),ids[i],false);
            input.setInputType(android.text.InputType.TYPE_CLASS_NUMBER|android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL);input.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(10)});nutritionFields.put(key,input);
        }
        consent = new CheckBox(this);consent.setId(R.id.submission_consent);consent.setText(t("Yoxlamadan sonra bu məhsul məlumatlarının və qablaşdırma fotolarının mənbə kimi açıq göstərilməsinə razıyam.",
            "I agree that, after review, these product details and package photos may be published as the product's source."));
        if(saved!=null)consent.setChecked(saved.getBoolean("consent"));form.addView(consent);
        feedback = new TextView(this);feedback.setTextColor(0xff9e3232);feedback.setPadding(0,dp(12),0,dp(12));feedback.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);form.addView(feedback);
        submit = new Button(this);submit.setAllCaps(false);submit.setText(t("Yoxlamaya göndər", "Submit for review"));submit.setOnClickListener(view->submit());form.addView(submit);
    }
    private String value(Bundle saved,String key){return saved!=null?saved.getString(key,""):getIntent().getStringExtra(key)==null?"":getIntent().getStringExtra(key);}
    private void choose(String role) {
        state.pendingRole=role;
        new AlertDialog.Builder(this).setItems(new String[]{t("Kamera", "Camera"),t("Qalereya", "Gallery")},(dialog,which)->{
            try {
                if(which==1)gallery.launch(new String[]{"image/*"});
                else {File file=new File(state.directory(),"camera-original.jpg");camera.launch(FileProvider.getUriForFile(this,getPackageName()+".labelphotos",file));}
            }catch(ActivityNotFoundException e){feedback.setText(t("Kamera yoxdur; qalereyadan foto seçin.","Camera unavailable; choose a photo from the gallery."));}
        }).show();
    }
    private void updatePhotos(){for(String role:SubmissionClient.PHOTO_ROLES){File file=state.photos.get(role);if(file!=null)previews.get(role).setImageBitmap(LabelPhotoProcessor.thumbnail(file));}}
    private void editPhoto(String role, boolean read) {
        if (state.busy) return;
        if (!state.photos.containsKey(role)) {
            analysisPreviews.get(role).setText(t("Əvvəl bu bölməyə foto əlavə edin.", "Add a photo to this section first."));
            return;
        }
        state.pendingRole = role;
        readAfterCrop = read;
        cropEditor.launch(new Intent(this, LabelCropActivity.class).putExtra("draftId", state.id).putExtra("role", role));
    }

    private void analyze(String role){
        if(state.busy)return;
        if(!state.photos.containsKey(role)){feedback.setText(t("Əvvəl bu bölməyə foto əlavə edin.","Add a photo to this section first."));return;}
        try{String base=ApiClient.validateBase(getSharedPreferences("etiket",MODE_PRIVATE).getString("base","http://10.0.2.2:8080"));state.analyze(base,role,languages[language.getSelectedItemPosition()]);}
        catch(IllegalArgumentException e){feedback.setText(t("Parametrlərdə server ünvanını yoxlayın.","Check the server address in Settings."));}
    }
    private void updateAnalysis() {
        SubmissionState.AnalysisEvent job = state.analysisEvent.getValue();
        for (var entry : analysisPreviews.entrySet()) {
            String role = entry.getKey();
            TextView preview = entry.getValue();
            JSONObject result = state.analysisResults.get(role);
            boolean readable = SubmissionState.readable(result);
            boolean nutritionRole = "nutrition".equals(role);
            NutritionLabel nutrition = readable && nutritionRole ? ProductJson.nutrition(result.optJSONObject("nutrition")) : null;
            boolean canCopy = readable && (!nutritionRole || nutrition != null && !nutrition.values().isEmpty());
            boolean thisRole = job != null && role.equals(job.role());
            Button use = applyButtons.get(role);
            boolean canReviewPhoto = nutritionRole && state.photos.containsKey("nutrition");
            use.setEnabled((canCopy || canReviewPhoto) && !state.busy);
            use.setVisibility(canCopy || canReviewPhoto ? View.VISIBLE : View.GONE);
            if (nutritionRole) use.setText(canCopy ? t("Qida dəyərləri təkliflərinə bax", "Review suggested nutrition values")
                : t("Fotodan qida dəyərlərini daxil et", "Enter nutrition values from photo"));
            preview.setTextColor(0xff19372a);
            if (thisRole && state.busy && Set.of("QUEUED", "PROCESSING").contains(job.status())) {
                preview.setText(t("Etiket mətni oxunur…", "Reading the label…"));
            } else if (thisRole && !job.error().isEmpty()) {
                preview.setText(error(job.error()));
                preview.setTextColor(0xff9e3232);
            } else if (result == null && !(thisRole && "RETAKE".equals(job.status()))) {
                preview.setText("");
            } else if (!readable) {
                preview.setText(t("Mətn aydın oxunmadı. Kəsimi düzəldin, daha yaxın foto çəkin və ya mətni əl ilə yazın.",
                    "The label could not be read clearly. Adjust the crop, retake a close-up or enter the text manually."));
                preview.setTextColor(0xff9e3232);
            } else {
                String text = t("Avtomatik oxunmuş qaralama — fotoya baxıb yoxlayın.\n",
                    "Automatically read draft — check against the photo.\n") + result.optString("rawText");
                JSONArray uncertain = result.optJSONArray("uncertainWords");
                if (uncertain != null && uncertain.length() > 0) {
                    List<String> words = new ArrayList<>();
                    for (int i = 0; i < Math.min(uncertain.length(), 15); i++) words.add(uncertain.optString(i));
                    text += t("\nYoxlanmalı sözlər: ", "\nWords to check: ") + String.join(", ", words);
                }
                if (nutritionRole) {
                    String hint = manualNutritionHint();
                    if (canCopy) {
                        String basis = nutritionReference(nutrition);
                        int count = nutrition.values().size();
                        hint = t(basis + " üçün " + count + " təklif", count + " suggested values per " + basis);
                        if (count < NutritionLabel.KEYS.size())
                            hint += "\n" + t("Qalan dəyərləri həmin sütundan əl ilə daxil edin.",
                                "Enter the remaining values from that column by hand.");
                    }
                    text = hint + "\n\n" + text;
                }
                preview.setText(text);
            }
            if (nutritionRole) {
                String raw = result == null ? "" : result.optString("rawText");
                boolean hasRaw = !raw.isBlank() && !state.busy;
                String summary = preview.getText().toString();
                int draftStart = summary.indexOf(t("Avtomatik oxunmuş qaralama", "Automatically read draft"));
                if (draftStart >= 0) summary = summary.substring(0, draftStart).strip();
                nutritionSummary.setText(summary);
                nutritionSummary.setTextColor(preview.getCurrentTextColor());
                nutritionRawToggle.setText(showNutritionRaw ? t("Oxunmuş mətni gizlət", "Hide recognised text")
                    : t("Oxunmuş mətni göstər", "Show recognised text"));
                nutritionRawToggle.setVisibility(hasRaw ? View.VISIBLE : View.GONE);
                preview.setVisibility(hasRaw && showNutritionRaw ? View.VISIBLE : View.GONE);
                if (hasRaw) preview.setText(String.format(t("Avtomatik oxunmuş qaralama — fotoya baxıb yoxlayın.\n%s",
                    "Automatically read draft — check against the photo.\n%s"), raw));
            }
        }
    }

    private String manualNutritionHint() {
        return t("Qida dəyərləri dəqiq oxunmadı. Etiketdəki 100 q və ya 100 ml sütunundan rəqəmləri əl ilə daxil edin.",
            "Could not read reliable nutrition values. Enter the numbers from the 100 g or 100 ml column by hand.");
    }

    private void applyAnalysis(String role){
        JSONObject result=state.analysisResults.get(role);
        if(state.busy)return;
        if (role.equals("nutrition")) {
            reviewNutrition(null);
            return;
        }
        if(!SubmissionState.readable(result))return;
        new AlertDialog.Builder(this).setTitle(t("Qaralamanı köçür?","Use this draft?"))
            .setMessage(t("Bu, uyğun mətn sahələrində yazdıqlarınızı əvəz edəcək. Köçürdükdən sonra sözləri, rəqəmləri və əsas miqdarı foto ilə yoxlayın.","This replaces the corresponding fields, including any edits. After applying, check the words, numbers and reference amount against the photo."))
            .setNegativeButton(t("Ləğv et","Cancel"),null).setPositiveButton(t("Köçür","Use draft"),(dialog,which)->{
                ingredients.setText(result.optString("rawText"));
                optionalText.setVisibility(View.VISIBLE);
            }).show();
    }

    private void reviewNutrition(Bundle saved) {
        JSONObject result = state.analysisResults.get("nutrition");
        NutritionLabel suggestion = SubmissionState.readable(result)
            ? ProductJson.nutrition(result.optJSONObject("nutrition")) : NutritionLabel.empty();
        if (suggestion == null) suggestion = NutritionLabel.empty();
        Map<String, String> current = new LinkedHashMap<>();
        nutritionFields.forEach((key, input) -> current.put(key, input.getText().toString()));
        nutritionReview = new NutritionReviewDialog(this, az, state.photos.get("nutrition"), suggestion,
            nutritionBasis.getSelectedItemPosition(), current, saved, checked -> {
                nutritionBasis.setSelection(checked.basis().equals("PER_100_G") ? 1 : 2);
                nutritionFields.forEach((key, input) -> input.setText(checked.values().containsKey(key)
                    ? nutritionNumber(checked.values().get(key)) : ""));
            });
        nutritionReview.show();
    }

    private String nutritionReference(NutritionLabel nutrition) {
        return nutrition.basis().equals("PER_100_G") ? t("100 q", "100 g") : "100 ml";
    }

    private static String nutritionNumber(double value) {
        return BigDecimal.valueOf(value).stripTrailingZeros().toPlainString();
    }

    private void submit(){
        if(state.busy)return;
        try{
            String code=Barcode.normalize(barcode.getText().toString());
            if(name.getText().toString().isBlank()){name.setError(t("Adı yazın.","Enter a product name."));return;}
            if(!consent.isChecked()){feedback.setText(t("Dərc razılığını seçin.","Please select the publication consent."));return;}
            for(String role:SubmissionClient.ROLES)if(!state.photos.containsKey(role)){feedback.setText(t("Hər üç foto yerini doldurun.","Add all three package photos."));return;}
            String base=ApiClient.validateBase(getSharedPreferences("etiket",MODE_PRIVATE).getString("base","http://10.0.2.2:8080"));
            JSONObject metadata=new JSONObject().put("clientRequestId",state.id).put("barcode",code).put("name",name.getText().toString().trim())
                .put("brand",brand.getText().toString().trim()).put("quantity",quantity.getText().toString().trim()).put("ingredients",ingredients.getText().toString().trim())
                .put("ingredientsLanguage",languages[language.getSelectedItemPosition()]).put("declaredAllergenStatement",allergens.getText().toString().trim()).put("consentToPublish",true);
            Map<String,Double> values=new LinkedHashMap<>();
            for(var entry:nutritionFields.entrySet())if(!entry.getValue().getText().toString().isBlank())values.put(entry.getKey(),Double.parseDouble(entry.getValue().getText().toString().replace(',','.')));
            String basis=new String[]{"","PER_100_G","PER_100_ML"}[nutritionBasis.getSelectedItemPosition()];
            NutritionLabel nutrition=new NutritionLabel(basis,values);
            if(!values.isEmpty()&&!state.photos.containsKey("nutrition")){feedback.setText(t("Qida dəyərləri üçün cədvəlin fotosunu da əlavə edin.","Add the nutrition-table photo to support these values."));return;}
            metadata.put("nutrition",new JSONObject().put("basis",nutrition.basis()).put("values",new JSONObject(values))).put("analysis",state.references(base));
            state.upload(base,metadata);
        }catch(NumberFormatException e){feedback.setText(t("Qida dəyərlərində rəqəmləri yoxlayın.","Check the nutrition numbers."));}
        catch(IllegalArgumentException e){if(e.getMessage()!=null&&e.getMessage().startsWith("NUTRITION_")){feedback.setText(t("Qida dəyərlərində əsas miqdarı və rəqəmləri yoxlayın. Məlum olmayan sahələri boş saxlayın.","Check the nutrition reference amount and numbers. Leave unknown fields blank."));return;}barcode.setError(t("Barkodu və Parametrlərdəki server ünvanını yoxlayın.","Check the barcode and server address in Settings."));}
        catch(JSONException e){feedback.setText(t("Məlumatı yoxlayıb yenidən sınayın.","Check your details and try again."));}
    }
    private String error(String code){return switch(code){
        case ""->"";
        case "REVIEW_NOT_CONFIGURED"->t("Server hələ foto qəbuluna hazır deyil.","The server is not ready to receive label photos yet.");
        case "SUBMISSION_QUEUE_FULL","SUBMISSION_BUSY"->t("Yoxlama növbəsi hazırda doludur. Daha sonra sınayın.","The review queue is busy. Please try again later.");
        case "PHOTO_TOO_LARGE","PHOTO_DIMENSIONS_TOO_LARGE","PHOTO_INVALID"->t("Foto hazırlanmadı. Daha aydın, yaxın çəkilmiş foto seçin.","Could not prepare the photo. Choose a clear close-up of the label.");
        case "CONNECTION_FAILED"->t("Bağlantı alınmadı. İnterneti və Parametrlərdəki ünvanı yoxlayıb yenidən sınayın.","Connection failed. Check the internet and server address in Settings, then retry.");
        case "OCR_UNAVAILABLE","OCR_TIMEOUT"->t("Avtomatik oxuma hazırda alınmır. Yenidən sınayın və ya mətni əl ilə yazın.","Automatic reading is unavailable. Retry or enter the text manually.");
        case "OCR_PENDING"->t("Oxuma davam edir. Nəticəni yoxlamaq üçün Fotodan mətni oxu düyməsini yenidən basın.","Reading is still in progress. Tap Read text from photo again to resume checking.");
        case "OCR_QUEUE_FULL","OCR_BUSY"->t("Mətn oxuma növbəsi doludur. Bir az sonra yenidən sınayın.","The reading queue is busy. Please try again shortly.");
        case "ANALYSIS_PHOTO_CHANGED","ANALYSIS_NOT_FOUND","ANALYSIS_NOT_READY"->t("Foto üçün mətni yenidən oxudun və ya əl ilə göndərin.","Read the photo again before submitting, or enter it manually.");
        default->t("Göndərmə alınmadı. Məlumatları və fotoları yoxlayın.","Submission failed. Check the details and photos.");};}
    @Override public void onSaveInstanceState(Bundle out){super.onSaveInstanceState(out);out.putString("draftId",state.id);out.putString("role",state.pendingRole);out.putBoolean("readAfterCrop",readAfterCrop);
        out.putBoolean("showNutritionRaw",showNutritionRaw);
        if(nutritionReview!=null&&nutritionReview.isShowing())out.putBundle("nutritionReview",nutritionReview.draft());
        out.putString("barcode",barcode.getText().toString());out.putString("name",name.getText().toString());out.putString("brand",brand.getText().toString());out.putString("quantity",quantity.getText().toString());
        out.putInt("nutritionBasis",nutritionBasis.getSelectedItemPosition());for(var entry:nutritionFields.entrySet())out.putString("nutrition-"+entry.getKey(),entry.getValue().getText().toString());
        out.putString("ingredients",ingredients.getText().toString());out.putString("allergens",allergens.getText().toString());out.putInt("language",language.getSelectedItemPosition());out.putBoolean("consent",consent.isChecked());out.putBoolean("optionalExpanded",optionalText.getVisibility()==View.VISIBLE);}
    @Override public void onDestroy(){if(nutritionReview!=null)nutritionReview.dismiss();super.onDestroy();}
}
