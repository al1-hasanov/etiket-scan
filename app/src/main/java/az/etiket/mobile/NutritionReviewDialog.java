package az.etiket.mobile;

import android.app.Dialog;
import android.content.Context;
import android.graphics.*;
import android.os.Build;
import android.os.Bundle;
import android.text.InputFilter;
import android.text.InputType;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.*;
import android.widget.*;
import az.etiket.core.NutritionLabel;
import java.io.File;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Consumer;

/** A local, editable check against the selected photo. Opening or cancelling never changes the draft. */
final class NutritionReviewDialog extends Dialog {
    private static final String[] AZ = {"Enerji (kcal)", "Yağ (q)", "Doymuş yağ (q)", "Karbohidrat (q)", "Şəkərlər (q)", "Lif (q)", "Zülal (q)", "Duz (q)"};
    private static final String[] EN = {"Energy (kcal)", "Fat (g)", "Saturated fat (g)", "Carbohydrates (g)", "Sugars (g)", "Fibre (g)", "Protein (g)", "Salt (g)"};
    private final boolean az;
    private final Map<String, EditText> fields = new LinkedHashMap<>();
    private final Spinner basis;
    private final TextView feedback;
    private Bitmap bitmap;
    private int previousBasis;

    NutritionReviewDialog(Context context, boolean az, File photo, NutritionLabel suggestion,
            int currentBasis, Map<String, String> current, Bundle saved, Consumer<NutritionLabel> apply) {
        super(context);
        this.az = az;
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        LinearLayout root = column();
        root.setBackgroundColor(0xfff6f8f5);
        root.setPadding(dp(16), dp(12), dp(16), dp(12));
        setContentView(root);
        int photoHeight = context.getResources().getConfiguration().orientation
            == android.content.res.Configuration.ORIENTATION_LANDSCAPE ? 90 : 170;
        if (Build.VERSION.SDK_INT >= 30) {
            getWindow().setDecorFitsSystemWindows(false);
            root.setOnApplyWindowInsetsListener((view, insets) -> {
                Insets bars = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.ime());
                root.setPadding(dp(16) + bars.left, dp(12) + bars.top, dp(16) + bars.right, dp(12) + bars.bottom);
                View image = root.findViewById(R.id.nutrition_review_photo);
                if (image != null) {
                    image.getLayoutParams().height = dp(insets.isVisible(WindowInsets.Type.ime()) ? 80 : photoHeight);
                    image.requestLayout();
                }
                return insets;
            });
            root.requestApplyInsets();
        }
        root.addView(text(t("Qida dəyərlərini yoxlayın", "Check nutrition values"), 22));
        if (photo != null && photo.isFile()) bitmap = BitmapFactory.decodeFile(photo.getPath());
        if (bitmap != null) {
            PhotoView image = new PhotoView(context, bitmap);
            image.setId(R.id.nutrition_review_photo);
            image.setContentDescription(t("Seçilmiş qida cədvəli fotosu. Böyütmək üçün iki dəfə toxunun.",
                "Selected nutrition photo. Double tap or use the zoom buttons to enlarge."));
            root.addView(image, new LinearLayout.LayoutParams(-1, dp(photoHeight)));
            LinearLayout zoom = new LinearLayout(context);
            zoom.addView(button(t("Kiçilt", "Zoom out"), () -> image.zoom(1 / 1.5f)), weighted());
            zoom.addView(button(t("Böyüt", "Zoom in"), () -> image.zoom(1.5f)), weighted());
            root.addView(zoom);
            setOnDismissListener(dialog -> { image.setImageDrawable(null); bitmap.recycle(); bitmap = null; });
        } else root.addView(text(t("Fotoya baxmaq üçün qida cədvəli fotosunu əlavə edin.", "Add the nutrition photo to compare these entries with the label."), 14));

        ScrollView scroll = new ScrollView(context);
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        LinearLayout form = column();
        scroll.addView(form);
        TextView explanation = text(t("Fotodakı 100 q və ya 100 ml sütununu seçin. Əsas miqdarı dəyişəndə rəqəmlər silinir. Boş sahələr məlum deyil deməkdir.",
            "Use the photo's 100 g or 100 ml column. Changing the reference clears the numbers. Blank fields mean unknown."), 14);
        form.addView(explanation);
        basis = new Spinner(context);
        basis.setId(R.id.nutrition_review_basis);
        basis.setContentDescription(t("Əsas miqdar", "Reference amount"));
        basis.setAdapter(new ArrayAdapter<>(context, android.R.layout.simple_spinner_dropdown_item,
            new String[]{t("Əsas miqdarı seçin", "Choose reference amount"), "100 g", "100 ml"}));
        form.addView(basis);
        int suggestedBasis = suggestion.basis().equals("PER_100_G") ? 1 : suggestion.basis().equals("PER_100_ML") ? 2 : 0;
        int initialBasis = saved != null ? saved.getInt("basis") : suggestedBasis == 0 ? currentBasis : suggestedBasis;
        basis.setSelection(initialBasis);
        previousBasis = initialBasis;
        boolean sameBasis = initialBasis == currentBasis;
        if (!sameBasis && current.values().stream().anyMatch(value -> !value.isBlank()))
            form.addView(text(t("Əsas miqdar fərqlidir. Təsdiqdən sonra əvvəlki qida dəyərləri əvəz olunacaq.",
                "The reference amount differs. Applying this review replaces the existing nutrition entries."), 14));
        int[] ids = {R.id.nutrition_review_energy, R.id.nutrition_review_fat, R.id.nutrition_review_saturated,
            R.id.nutrition_review_carbs, R.id.nutrition_review_sugars, R.id.nutrition_review_fibre,
            R.id.nutrition_review_protein, R.id.nutrition_review_salt};
        for (int i = 0; i < NutritionLabel.KEYS.size(); i++) {
            String key = NutritionLabel.KEYS.get(i);
            Double proposed = suggestion.values().get(key);
            TextView label = text(az ? AZ[i] : EN[i], 15);
            label.setTypeface(null, Typeface.BOLD);
            label.setLabelFor(ids[i]);
            form.addView(label);
            if (proposed != null) form.addView(text(t("Təklif: ", "Suggested: ") + number(proposed)
                + (suggestedBasis == 1 ? t(" / 100 q", " / 100 g") : " / 100 ml"), 13));
            EditText input = new EditText(context);
            input.setId(ids[i]);
            input.setSingleLine(true);
            input.setTextSize(18);
            input.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
            input.setFilters(new InputFilter[]{new InputFilter.LengthFilter(10)});
            String existing = sameBasis ? current.getOrDefault(key, "") : "";
            input.setText(saved != null ? saved.getString(key, "") : !existing.isBlank() ? existing
                : proposed != null && initialBasis == suggestedBasis ? number(proposed) : "");
            input.setHint(t("Məlum deyil", "Unknown"));
            form.addView(input, new LinearLayout.LayoutParams(-1, -2));
            fields.put(key, input);
        }
        basis.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                if (position != previousBasis) fields.values().forEach(input -> input.setText(""));
                previousBasis = position;
            }
            @Override public void onNothingSelected(AdapterView<?> parent) { }
        });
        feedback = text("", 14);
        feedback.setTextColor(0xff9e3232);
        feedback.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        root.addView(feedback);
        fields.values().forEach(input -> input.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence value, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence value, int start, int before, int count) {
                feedback.setText("");
                input.setError(null);
            }
            @Override public void afterTextChanged(Editable value) { }
        }));
        LinearLayout actions = new LinearLayout(context);
        Button cancel = button(t("Ləğv et", "Cancel"), this::dismiss);
        cancel.setId(R.id.nutrition_review_cancel);
        actions.addView(cancel, weighted());
        Button use = button(t("Yoxlanmış rəqəmləri köçür", "Use reviewed values"), () -> {
            NutritionLabel checked = checkedValues();
            if (checked != null) { apply.accept(checked); dismiss(); }
        });
        use.setId(R.id.nutrition_review_apply);
        actions.addView(use, weighted());
        root.addView(actions);
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
    }

    @Override public void show() {
        super.show();
        getWindow().setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT);
    }

    Bundle draft() {
        Bundle saved = new Bundle();
        saved.putInt("basis", basis.getSelectedItemPosition());
        fields.forEach((key, input) -> saved.putString(key, input.getText().toString()));
        return saved;
    }

    private NutritionLabel checkedValues() {
        if (basis.getSelectedItemPosition() == 0) {
            feedback.setText(t("Əvvəl 100 q və ya 100 ml seçin.", "Choose 100 g or 100 ml first."));
            return null;
        }
        Map<String, Double> values = new LinkedHashMap<>();
        for (var entry : fields.entrySet()) {
            String text = entry.getValue().getText().toString().strip();
            if (text.isEmpty()) continue;
            try {
                if (!text.matches("[0-9]+(?:[.,][0-9]+)?")) throw new NumberFormatException();
                values.put(entry.getKey(), Double.parseDouble(text.replace(',', '.')));
            } catch (NumberFormatException e) {
                entry.getValue().setError(t("Rəqəmi yoxlayın.", "Check this number."));
                entry.getValue().requestFocus();
                return null;
            }
        }
        try {
            return new NutritionLabel(basis.getSelectedItemPosition() == 1 ? "PER_100_G" : "PER_100_ML", values);
        } catch (IllegalArgumentException e) {
            feedback.setText(t("Rəqəmləri yoxlayın. Doymuş yağ ümumi yağdan, şəkər isə karbohidratdan çox ola bilməz.",
                "Check the numbers and their limits. Saturated fat cannot exceed fat, and sugars cannot exceed carbohydrates."));
            return null;
        }
    }

    private LinearLayout column() { LinearLayout view = new LinearLayout(getContext()); view.setOrientation(LinearLayout.VERTICAL); return view; }
    private TextView text(String value, int size) {
        TextView view = new TextView(getContext()); view.setText(value); view.setTextColor(0xff19372a);
        view.setTextSize(size); view.setPadding(0, dp(6), 0, dp(6)); return view;
    }
    private Button button(String title, Runnable action) {
        Button view = new Button(getContext()); view.setAllCaps(false); view.setText(title); view.setOnClickListener(v -> action.run()); return view;
    }
    private LinearLayout.LayoutParams weighted() { return new LinearLayout.LayoutParams(0, -2, 1); }
    private int dp(int value) { return Math.round(value * getContext().getResources().getDisplayMetrics().density); }
    private String t(String a, String e) { return az ? a : e; }
    private static String number(double value) { return BigDecimal.valueOf(value).stripTrailingZeros().toPlainString(); }

    /** The photo stays above the editable rows. Gestures affect only its display, never the evidence. */
    private static final class PhotoView extends ImageView {
        private final Matrix transform = new Matrix();
        private final int imageWidth, imageHeight;
        private final ScaleGestureDetector pinch;
        private final GestureDetector taps;
        private float fit, scale, lastX, lastY;
        private float downX, downY;
        private boolean tap;

        PhotoView(Context context, Bitmap bitmap) {
            super(context);
            imageWidth = bitmap.getWidth(); imageHeight = bitmap.getHeight();
            setImageBitmap(bitmap); setScaleType(ScaleType.MATRIX); setBackgroundColor(0xff15271e);
            pinch = new ScaleGestureDetector(context, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
                @Override public boolean onScale(ScaleGestureDetector detector) {
                    zoom(detector.getScaleFactor(), detector.getFocusX(), detector.getFocusY()); return true;
                }
            });
            taps = new GestureDetector(context, new GestureDetector.SimpleOnGestureListener() {
                @Override public boolean onDown(MotionEvent event) { return true; }
                @Override public boolean onDoubleTap(MotionEvent event) {
                    zoom(scale > fit * 1.1f ? fit / scale : 2.5f, event.getX(), event.getY()); return true;
                }
            });
        }
        @Override protected void onSizeChanged(int w, int h, int oldw, int oldh) {
            super.onSizeChanged(w, h, oldw, oldh);
            fit = Math.min(w / (float) imageWidth, h / (float) imageHeight); scale = fit;
            transform.setScale(fit, fit); constrain();
        }
        void zoom(float factor) { zoom(factor, getWidth() / 2f, getHeight() / 2f); }
        private void zoom(float factor, float x, float y) {
            if (fit <= 0) return;
            float next = Math.max(fit, Math.min(fit * 6, scale * factor));
            transform.postScale(next / scale, next / scale, x, y); scale = next; constrain();
        }
        private void constrain() {
            RectF bounds = new RectF(0, 0, imageWidth, imageHeight); transform.mapRect(bounds);
            float dx = bounds.width() <= getWidth() ? (getWidth() - bounds.width()) / 2 - bounds.left
                : bounds.left > 0 ? -bounds.left : bounds.right < getWidth() ? getWidth() - bounds.right : 0;
            float dy = bounds.height() <= getHeight() ? (getHeight() - bounds.height()) / 2 - bounds.top
                : bounds.top > 0 ? -bounds.top : bounds.bottom < getHeight() ? getHeight() - bounds.bottom : 0;
            transform.postTranslate(dx, dy); setImageMatrix(transform);
        }
        @Override public boolean onTouchEvent(MotionEvent event) {
            pinch.onTouchEvent(event); taps.onTouchEvent(event);
            if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
                downX = event.getX(); downY = event.getY(); tap = true;
            } else if (event.getActionMasked() == MotionEvent.ACTION_POINTER_DOWN
                    || Math.hypot(event.getX() - downX, event.getY() - downY) > ViewConfiguration.get(getContext()).getScaledTouchSlop()) tap = false;
            if (event.getActionMasked() == MotionEvent.ACTION_MOVE && event.getPointerCount() == 1 && !pinch.isInProgress()) {
                transform.postTranslate(event.getX() - lastX, event.getY() - lastY); constrain();
            }
            if (event.getActionMasked() == MotionEvent.ACTION_UP && tap) performClick();
            lastX = event.getX(); lastY = event.getY(); return true;
        }
        @Override public boolean performClick() { super.performClick(); return true; }
    }
}
