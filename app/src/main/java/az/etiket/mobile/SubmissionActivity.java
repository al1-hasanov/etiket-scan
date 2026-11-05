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
import java.io.File;
import java.util.*;
import org.json.*;

public class SubmissionActivity extends ComponentActivity {
    private SubmissionState state;
    private boolean az;
    private LinearLayout form;
    private LinearLayout optionalText;
    private EditText barcode, name, brand, quantity, ingredients, allergens;
    private Spinner language;
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

    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        az = getSharedPreferences("etiket", MODE_PRIVATE).getBoolean("az", true);
        state = new ViewModelProvider(this, new ViewModelProvider.AndroidViewModelFactory(getApplication())).get(SubmissionState.class);
        if (saved != null && !state.busy) state.restore(saved.getString("draftId"));
        if (saved != null) state.pendingRole = saved.getString("role", "front");
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
            if (event.receipt() != null && !receiptShown) {
                receiptShown = true;
                boolean pending = event.receipt().optString("status").equals("PENDING");
                new AlertDialog.Builder(this).setTitle(pending ? t("Yoxlamaya göndərildi", "Submitted for review") : t("Göndərmənin vəziyyəti yeniləndi", "Submission status updated"))
                    .setMessage(pending ? t("Məlumat yoxlanmadan kataloqa əlavə olunmayacaq. Vəziyyəti Göndərdiklərim bölməsində izləyə bilərsiniz.",
                        "It will appear in the catalogue only after review. Track it in My submissions.") : t("Bu göndərmə artıq yoxlanıb. Nəticəyə Göndərdiklərim bölməsində baxın.","This submission has already been reviewed. See its result in My submissions."))
                    .setCancelable(false).setPositiveButton("OK", (dialog, which) -> { state.cleanFiles(); setResult(RESULT_OK); finish(); }).show();
            }
        });
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
        for (String role : SubmissionClient.ROLES) {
            addText(switch(role){case "front"->t("1. Ön tərəf", "1. Front of pack");case "barcode"->t("2. Barkod fotosu", "2. Barcode photo");default->t("3. Tərkib etiketi", "3. Ingredient label");},17,true);
            ImageView image = new ImageView(this); image.setAdjustViewBounds(true); image.setScaleType(ImageView.ScaleType.FIT_CENTER);
            image.setContentDescription(role); form.addView(image,new LinearLayout.LayoutParams(-1,dp(150)));previews.put(role,image);
            Button button = new Button(this); button.setAllCaps(false);button.setText(t("Foto seç və ya çək", "Choose or take photo"));
            button.setOnClickListener(view->choose(role));form.addView(button);photoButtons.add(button);
        }
        Button optionalButton = new Button(this); optionalButton.setAllCaps(false); optionalButton.setText(t("Etiket mətnini də yaz (istəyə görə)","Add label text (optional)"));
        optionalButton.setOnClickListener(view -> optionalText.setVisibility(optionalText.getVisibility()==View.VISIBLE?View.GONE:View.VISIBLE));form.addView(optionalButton);
        optionalText.setVisibility(saved != null && saved.getBoolean("optionalExpanded") ? View.VISIBLE : View.GONE); form.addView(optionalText);
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
    private void updatePhotos(){for(String role:SubmissionClient.ROLES){File file=state.photos.get(role);if(file!=null)previews.get(role).setImageBitmap(LabelPhotoProcessor.thumbnail(file));}}
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
            state.upload(base,metadata);
        }catch(IllegalArgumentException e){barcode.setError(t("Barkodu və Parametrlərdəki server ünvanını yoxlayın.","Check the barcode and server address in Settings."));}
        catch(JSONException e){feedback.setText(t("Məlumatı yoxlayıb yenidən sınayın.","Check your details and try again."));}
    }
    private String error(String code){return switch(code){
        case ""->"";
        case "REVIEW_NOT_CONFIGURED"->t("Server hələ foto qəbuluna hazır deyil.","The server is not ready to receive label photos yet.");
        case "SUBMISSION_QUEUE_FULL","SUBMISSION_BUSY"->t("Yoxlama növbəsi hazırda doludur. Daha sonra sınayın.","The review queue is busy. Please try again later.");
        case "PHOTO_TOO_LARGE","PHOTO_DIMENSIONS_TOO_LARGE","PHOTO_INVALID"->t("Foto hazırlanmadı. Daha aydın, yaxın çəkilmiş foto seçin.","Could not prepare the photo. Choose a clear close-up of the label.");
        case "CONNECTION_FAILED"->t("Bağlantı alınmadı. İnterneti və Parametrlərdəki ünvanı yoxlayıb yenidən sınayın.","Connection failed. Check the internet and server address in Settings, then retry.");
        default->t("Göndərmə alınmadı. Məlumatları və fotoları yoxlayın.","Submission failed. Check the details and photos.");};}
    @Override public void onSaveInstanceState(Bundle out){super.onSaveInstanceState(out);out.putString("draftId",state.id);out.putString("role",state.pendingRole);
        out.putString("barcode",barcode.getText().toString());out.putString("name",name.getText().toString());out.putString("brand",brand.getText().toString());out.putString("quantity",quantity.getText().toString());
        out.putString("ingredients",ingredients.getText().toString());out.putString("allergens",allergens.getText().toString());out.putInt("language",language.getSelectedItemPosition());out.putBoolean("consent",consent.isChecked());out.putBoolean("optionalExpanded",optionalText.getVisibility()==View.VISIBLE);}
}
