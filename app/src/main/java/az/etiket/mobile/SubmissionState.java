package az.etiket.mobile;

import android.app.Application;
import android.content.Context;
import android.net.Uri;
import androidx.lifecycle.*;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;
import org.json.*;

/** Retains in-flight photo preparation/upload over screen rotation. No reviewer credentials. */
public final class SubmissionState extends AndroidViewModel {
    public record Event(boolean busy, String error, JSONObject receipt) { }
    final MutableLiveData<Event> event = new MutableLiveData<>(new Event(false, "", null));
    final Map<String, File> photos = new ConcurrentHashMap<>();
    final ExecutorService worker = Executors.newSingleThreadExecutor();
    String id = UUID.randomUUID().toString();
    String pendingRole = "front";
    volatile boolean busy;
    public SubmissionState(Application application) { super(application); }
    File directory() {
        File directory = new File(getApplication().getCacheDir(), "label-drafts/" + id);
        if (!directory.isDirectory() && !directory.mkdirs()) throw new IllegalStateException("Cannot prepare photo directory");
        return directory;
    }
    void restore(String candidate) {
        if (candidate != null && UUID.fromString(candidate).toString().equals(candidate)) id = candidate;
        for (String role : SubmissionClient.ROLES) {
            File file = new File(directory(), role + ".jpg"); if (file.isFile()) photos.put(role, file);
        }
    }
    void photo(Uri uri, String role) {
        busy = true; event.setValue(new Event(true, "", null));
        worker.execute(() -> {
            String error = "";
            try { File file = new File(directory(), role + ".jpg"); LabelPhotoProcessor.prepare(getApplication(), uri, file); photos.put(role, file); }
            catch (Exception e) { error = "PHOTO_INVALID"; }
            finally { new File(directory(), "camera-original.jpg").delete(); }
            busy = false; event.postValue(new Event(false, error, null));
        });
    }
    void upload(String base, JSONObject metadata) {
        busy = true; event.setValue(new Event(true, "", null));
        worker.execute(() -> {
            try {
                JSONObject pending = new JSONObject().put("id",id).put("barcode",metadata.optString("barcode")).put("status","UNCONFIRMED")
                    .put("submittedAt",java.time.Instant.now().toString()).put("reviewedAt","").put("note","");
                SubmissionClient.remember(getApplication().getSharedPreferences("etiket", Context.MODE_PRIVATE),base,pending,metadata.optString("name"));
                JSONObject receipt = SubmissionClient.submit(base, metadata, new HashMap<>(photos));
                SubmissionClient.remember(getApplication().getSharedPreferences("etiket", Context.MODE_PRIVATE), base, receipt, metadata.optString("name"));
                busy = false; event.postValue(new Event(false, "", receipt));
            } catch (ApiClient.Failure e) { busy = false; event.postValue(new Event(false, e.code, null)); }
              catch (JSONException e) { busy = false; event.postValue(new Event(false,"SUBMISSION_UNAVAILABLE",null)); }
        });
    }
    void cleanFiles() {
        File[] files = directory().listFiles(); if (files != null) for (File file : files) file.delete(); directory().delete();
    }
    @Override protected void onCleared() { worker.shutdownNow(); }
}
