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
    public record AnalysisEvent(String role, String status, String error) { }
    final MutableLiveData<Event> event = new MutableLiveData<>(new Event(false, "", null));
    final Map<String, File> photos = new ConcurrentHashMap<>();
    final Map<String, JSONObject> analysisLinks = new ConcurrentHashMap<>();
    final Map<String, JSONObject> analysisResults = new ConcurrentHashMap<>();
    final MutableLiveData<AnalysisEvent> analysisEvent=new MutableLiveData<>();
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
        for (String role : SubmissionClient.PHOTO_ROLES) {
            File file = new File(directory(), role + ".jpg"); if (file.isFile()) photos.put(role, file);
        }
        try {
            JSONObject saved=new JSONObject(new String(java.nio.file.Files.readAllBytes(new File(directory(),"analysis.json").toPath()),java.nio.charset.StandardCharsets.UTF_8));
            for(String role:List.of("ingredients","nutrition")){
                JSONObject link=saved.optJSONObject(role);
                if(link!=null){analysisLinks.put(role,link);JSONObject result=link.optJSONObject("result");if(result!=null)analysisResults.put(role,result);}
            }
        }catch(Exception ignored){ }
    }
    void photo(Uri uri, String role) {
        busy = true; event.setValue(new Event(true, "", null));
        worker.execute(() -> {
            String error = "";
            try { File file = new File(directory(), role + ".jpg"); LabelPhotoProcessor.prepare(getApplication(), uri, file); photos.put(role, file);
                analysisLinks.remove(role);analysisResults.remove(role);persistAnalysis(); }
            catch (Exception e) { error = "PHOTO_INVALID"; }
            finally { new File(directory(), "camera-original.jpg").delete(); }
            busy = false; event.postValue(new Event(false, error, null));
        });
    }
    void analyze(String base,String role,String language){
        if(busy)return;
        busy=true;event.setValue(new Event(true,"",null));analysisEvent.setValue(new AnalysisEvent(role,"QUEUED",""));
        worker.execute(()->{
            try{
                JSONObject link=analysisLinks.get(role);
                if(link==null||!base.equals(link.optString("base"))||!language.equals(link.optString("language"))){
                    link=new JSONObject().put("id",UUID.randomUUID().toString()).put("token",UUID.randomUUID().toString().replace("-","")+UUID.randomUUID().toString().replace("-",""))
                        .put("base",base).put("language",language);analysisLinks.put(role,link);analysisResults.remove(role);persistAnalysis();
                }
                JSONObject request=new JSONObject().put("clientRequestId",link.getString("id")).put("token",link.getString("token")).put("role",role).put("language",language);
                JSONObject response=AnalysisClient.start(base,request,photos.get(role));
                long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(95);
                while(Set.of("QUEUED","PROCESSING").contains(response.optString("status"))){
                    analysisEvent.postValue(new AnalysisEvent(role,response.optString("status"),""));
                    if(System.nanoTime()>deadline)throw new ApiClient.Failure("OCR_PENDING");
                    Thread.sleep(1500);response=AnalysisClient.status(base,link);
                }
                JSONObject result=response.optJSONObject("result");
                if(result!=null){analysisResults.put(role,result);link.put("result",result);persistAnalysis();}
                String status=response.optString("status");busy=false;event.postValue(new Event(false,"",null));
                if(status.equals("FAILED")){analysisLinks.remove(role);analysisResults.remove(role);persistAnalysis();}
                analysisEvent.postValue(new AnalysisEvent(role,status,status.equals("FAILED")?response.optString("errorCode","OCR_UNAVAILABLE"):""));
            }catch(Exception e){busy=false;event.postValue(new Event(false,"",null));analysisEvent.postValue(new AnalysisEvent(role,"",e instanceof ApiClient.Failure failure?failure.code:"CONNECTION_FAILED"));}
        });
    }
    JSONObject references(String base)throws JSONException{
        JSONObject result=new JSONObject();
        for(var entry:analysisLinks.entrySet())if(analysisResults.containsKey(entry.getKey())&&base.equals(entry.getValue().optString("base")))
            result.put(entry.getKey(),new JSONObject().put("id",entry.getValue().getString("id")).put("token",entry.getValue().getString("token")));
        return result;
    }
    private void persistAnalysis()throws IOException,JSONException{
        JSONObject value=new JSONObject();for(var entry:analysisLinks.entrySet())value.put(entry.getKey(),entry.getValue());
        File temp=new File(directory(),"analysis.tmp");java.nio.file.Files.write(temp.toPath(),value.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
        java.nio.file.Files.move(temp.toPath(),new File(directory(),"analysis.json").toPath(),java.nio.file.StandardCopyOption.REPLACE_EXISTING);
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
            } catch (ApiClient.Failure e) {
                if(Set.of("ANALYSIS_NOT_FOUND","ANALYSIS_PHOTO_CHANGED","ANALYSIS_NOT_READY").contains(e.code)){
                    analysisLinks.clear();analysisResults.clear();try{persistAnalysis();}catch(Exception ignored){}
                }
                busy = false; event.postValue(new Event(false, e.code, null)); }
              catch (JSONException e) { busy = false; event.postValue(new Event(false,"SUBMISSION_UNAVAILABLE",null)); }
        });
    }
    void cleanFiles() {
        File[] files = directory().listFiles(); if (files != null) for (File file : files) file.delete(); directory().delete();
    }
    @Override protected void onCleared() { worker.shutdownNow(); }
}
