package az.etiket.mobile;

import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Insets;
import android.graphics.Matrix;
import android.graphics.Rect;
import android.graphics.RectF;
import android.os.Build;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.WindowInsets;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import androidx.activity.ComponentActivity;
import androidx.activity.OnBackPressedCallback;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.ViewModel;
import androidx.lifecycle.ViewModelProvider;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Edits a private copy. The submission only replaces its photo after RESULT_OK. */
public final class LabelCropActivity extends ComponentActivity {
    private boolean az;
    private String role;
    private CropState state;
    private LabelCropView crop;
    private Button zoom;
    private TextView feedback;
    private final List<Button> actions = new ArrayList<>();

    @Override protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        az = getSharedPreferences("etiket", MODE_PRIVATE).getBoolean("az", true);
        role = getIntent().getStringExtra("role");
        String draft = getIntent().getStringExtra("draftId");
        state = new ViewModelProvider(this).get(CropState.class);
        try {
            if (!SubmissionState.canCrop(role) || draft == null || !UUID.fromString(draft).toString().equals(draft))
                throw new IllegalArgumentException("Invalid draft");
            File directory = new File(getCacheDir(), "label-drafts/" + draft);
            state.open(directory, role, saved == null ? 0 : saved.getInt("turns"));
        } catch (IllegalArgumentException | IOException e) {
            Toast.makeText(this, t("Foto açıla bilmədi.", "Could not open the photo."), Toast.LENGTH_SHORT).show();
            finish();
            return;
        }
        build(saved);
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override public void handleOnBackPressed() {
                if (state.save.getValue() != Save.SAVING) finish();
            }
        });
        state.save.observe(this, status -> {
            boolean saving = status == Save.SAVING;
            for (Button action : actions) action.setEnabled(!saving);
            crop.setEnabled(!saving);
            feedback.setVisibility(status == Save.IDLE ? View.GONE : View.VISIBLE);
            if (status == Save.DONE) {
                setResult(RESULT_OK, new Intent().putExtra("role", role));
                finish();
            } else if (status == Save.ERROR) {
                feedback.setText(t("Daha böyük sahə seçin və yenidən sınayın.", "Select a larger area and try again."));
            } else if (saving) {
                feedback.setText(t("Foto hazırlanır…", "Preparing the crop…"));
            }
        });
    }

    private void build(Bundle saved) {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0xfff6f8f5);
        root.setPadding(dp(12), dp(8), dp(12), dp(8));
        setContentView(root);
        if (Build.VERSION.SDK_INT >= 30) {
            getWindow().setDecorFitsSystemWindows(false);
            root.setOnApplyWindowInsetsListener((view, insets) -> {
                Insets bars = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
                view.setPadding(dp(12) + bars.left, dp(8) + bars.top, dp(12) + bars.right, dp(8) + bars.bottom);
                return insets;
            });
            root.requestApplyInsets();
        }

        LinearLayout header = row(root);
        header.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = new TextView(this);
        title.setText(t("Etiketi kəs", "Crop label"));
        title.setTextColor(0xff19372a);
        title.setTextSize(22);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        header.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
        CheckBox square = new CheckBox(this);
        square.setId(R.id.label_crop_square);
        square.setText(t("Kvadrat", "Square"));
        square.setMinHeight(dp(48));
        square.setChecked(saved != null && saved.getBoolean("square"));
        header.addView(square);
        actions.add(square);
        TextView guide = new TextView(this);
        guide.setTextColor(0xff19372a);
        guide.setTextSize(14);
        guide.setPadding(0, dp(4), 0, dp(6));
        String labelGuide = role.equals("nutrition")
            ? t("100 q və ya 100 ml başlığını və sətir adlarını saxlayın.",
                "Keep the 100 g or 100 ml heading and the row names.")
            : t("Tərkib siyahısını bütöv saxlayın.", "Keep the full ingredients list inside.");
        guide.setText(String.format(Locale.ROOT,
            t("Çərçivəni içəridən sürüşdürün. Ölçünü kənar və ya küncdən dəyişin.\n%s",
                "Drag inside to move the box. Resize from any edge or corner.\n%s"), labelGuide));
        root.addView(guide);

        crop = new LabelCropView(this);
        crop.setId(R.id.label_crop_canvas);
        crop.setContentDescription(t("Kəsim çərçivəsi", "Crop box"));
        crop.reset(state.displayed);
        RectF window = saved == null ? null : saved.getParcelable("window");
        if (saved != null) crop.restore(saved.getFloatArray("corners"), window);
        crop.setSquare(square.isChecked());
        root.addView(crop, new LinearLayout.LayoutParams(-1, 0, 1));

        LinearLayout rotation = row(root);
        action(rotation, t("Sola döndər", "Rotate left"), () -> rotate(-1));
        action(rotation, t("Sağa döndər", "Rotate right"), () -> rotate(1));

        LinearLayout controls = row(root);
        zoom = action(controls, "", () -> zoomText(crop.toggleZoom()));
        zoomText(crop.isZoomed());
        square.setOnCheckedChangeListener((button, checked) -> {
            crop.setSquare(checked);
            zoomText(crop.isZoomed());
        });
        action(controls, t("Sıfırla", "Reset"), () -> {
            state.turnTo(0);
            crop.reset(state.displayed);
            zoomText(false);
        });

        feedback = new TextView(this);
        feedback.setTextColor(0xff9e3232);
        feedback.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        root.addView(feedback);
        LinearLayout footer = row(root);
        action(footer, t("Ləğv et", "Cancel"), this::finish);
        action(footer, t("Sahəni istifadə et", "Use selected area"), () -> state.save(crop.cropBounds()))
            .setId(R.id.label_crop_use);
    }

    private LinearLayout row(LinearLayout root) {
        LinearLayout row = new LinearLayout(this);
        root.addView(row, new LinearLayout.LayoutParams(-1, -2));
        return row;
    }

    private Button action(LinearLayout row, String text, Runnable onClick) {
        Button button = new Button(this);
        button.setAllCaps(false);
        button.setText(text);
        button.setOnClickListener(view -> onClick.run());
        row.addView(button, new LinearLayout.LayoutParams(0, -2, 1));
        actions.add(button);
        return button;
    }

    private void zoomText(boolean zoomed) {
        zoom.setText(zoomed ? t("Bütün foto", "Full photo") : t("Sahəni böyüt", "Zoom selection"));
    }

    private void rotate(int direction) {
        state.turnTo(state.turns + direction);
        crop.reset(state.displayed);
        zoomText(false);
    }

    @Override protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        if (crop != null) {
            out.putFloatArray("corners", crop.corners());
            out.putParcelable("window", crop.window());
            out.putInt("turns", state.turns);
            out.putBoolean("square", crop.isSquare());
        }
    }

    private int dp(int size) { return Math.round(size * getResources().getDisplayMetrics().density); }
    private String t(String a, String e) { return az ? a : e; }

    enum Save { IDLE, SAVING, DONE, ERROR }

    public static final class CropState extends ViewModel {
        final MutableLiveData<Save> save = new MutableLiveData<>(Save.IDLE);
        private final ExecutorService worker = Executors.newSingleThreadExecutor();
        private Bitmap original;
        Bitmap displayed;
        int turns;
        private File destination;

        void open(File directory, String role, int turns) throws IOException {
            if (original != null) return;
            File source = new File(directory, role + "-original.jpg");
            if (!source.isFile()) source = new File(directory, role + ".jpg");
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(source.getPath(), options);
            if (source.length() > LabelPhotoProcessor.MAX_BYTES || options.outWidth < 1 || options.outHeight < 1
                || options.outWidth > 2048 || options.outHeight > 2048) throw new IOException("Invalid source");
            original = BitmapFactory.decodeFile(source.getPath());
            if (original == null) throw new IOException("Invalid source");
            destination = new File(directory, role + "-crop.jpg");
            turnTo(turns);
        }

        void turnTo(int turns) {
            this.turns = Math.floorMod(turns, 4);
            Matrix rotation = new Matrix();
            rotation.setRotate(this.turns * 90f);
            displayed = Bitmap.createBitmap(original, 0, 0, original.getWidth(), original.getHeight(), rotation, true);
        }

        void save(Rect area) {
            if (save.getValue() == Save.SAVING) return;
            Bitmap source = displayed;
            save.setValue(Save.SAVING);
            worker.execute(() -> {
                try {
                    if (area.width() < LabelCropView.MIN_WIDTH || area.height() < LabelCropView.MIN_HEIGHT)
                        throw new IllegalArgumentException("Crop is too small");
                    Bitmap result = Bitmap.createBitmap(source, area.left, area.top, area.width(), area.height());
                    try {
                        LabelPhotoProcessor.writeJpeg(result, destination, 95, 85, 70);
                    } finally {
                        if (result != source) result.recycle();
                    }
                    save.postValue(Save.DONE);
                } catch (IOException | IllegalArgumentException e) {
                    save.postValue(Save.ERROR);
                }
            });
        }

        @Override protected void onCleared() { worker.shutdown(); }
    }
}
