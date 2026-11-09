package az.etiket.backend;

import az.etiket.core.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.Semaphore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;
import tools.jackson.databind.json.JsonMapper;

/** Private, expiring analysis jobs. Publication remains the existing review transaction. */
@Service
@EnableScheduling
final class LabelAnalysis {
    record Request(String clientRequestId, String token, String role, String language) { }
    record Link(String id, String token) { }
    record Snapshot(String engine, String language, String rawText, double confidence,
        List<String> uncertainWords, int rotation, NutritionLabel nutrition, List<String> warnings) { }
    record Result(String id, String status, Snapshot result, String errorCode) { }
    private record Job(String id, String access, String role, String language, String hash,
        String status, String output, String error, byte[] photo, int attempts) { }
    private final JdbcTemplate db;
    private final TransactionTemplate tx;
    private final LabelPhotos images;
    private final OcrEngine engine;
    private final OcrDiagnostics diagnostics;
    private final boolean enabled, workerEnabled;
    private final int maximum, pendingMaximum;
    private final JsonMapper json = JsonMapper.builder().build();
    private final Semaphore processing = new Semaphore(2);

    LabelAnalysis(JdbcTemplate db, TransactionTemplate tx, LabelPhotos images, OcrEngine engine, OcrDiagnostics diagnostics,
            @Value("${review.password:}") String password, @Value("${ocr.enabled:true}") boolean enabled,
            @Value("${ocr.worker-enabled:true}") boolean workerEnabled, @Value("${ocr.max-jobs:200}") int maximum,
            @Value("${ocr.max-pending:8}") int pendingMaximum) {
        this.db=db; this.tx=tx; this.images=images; this.engine=engine; this.enabled=enabled && !password.isBlank();
        this.diagnostics=diagnostics;
        this.workerEnabled=workerEnabled; this.maximum=maximum; this.pendingMaximum=pendingMaximum;
    }
    Result create(Request input, MultipartFile photo) {
        if (!enabled) throw new SubmissionException(503,"OCR_UNAVAILABLE");
        if(input==null)throw new SubmissionException(400,"ANALYSIS_REQUEST_INVALID");
        String id=id(input.clientRequestId()), token=token(input.token());
        if(input.role()==null||!Set.of("ingredients","nutrition").contains(input.role()))throw new SubmissionException(400,"ANALYSIS_ROLE_INVALID");
        if(input.language()==null||!TesseractOcr.LANGUAGES.containsKey(input.language()))throw new SubmissionException(400,"OCR_LANGUAGE_UNSUPPORTED");
        if(!processing.tryAcquire())throw new SubmissionException(429,"OCR_BUSY");
        try (var diagnostic = diagnostics.job(id, input.role(), input.language(), 0)) {
            var image=images.sanitizeForAnalysis(photo, input.role().equals("nutrition"));
            OcrDiagnostics.event("upload_prepared", "uploadBytes", photo.getSize(), "analysisBytes", image.bytes().length);
            return tx.execute(status->{
                guard();
                long now=System.currentTimeMillis();
                db.update("DELETE FROM label_analysis_jobs WHERE expires_at<?",now);
                var existing=jobs(id);
                if(!existing.isEmpty()){
                    Job old=authorized(existing.getFirst(),token);
                    if(!old.hash().equals(image.evidenceHash())||!old.role().equals(input.role())||!old.language().equals(input.language()))
                        throw new SubmissionException(409,"ANALYSIS_ID_CONFLICT");
                    OcrDiagnostics.event("job_reused", "status", old.status(), "previousAttempts", old.attempts());
                    return result(old);
                }
                if(db.queryForObject("SELECT COUNT(*) FROM label_analysis_jobs",Integer.class)>=maximum
                    ||db.queryForObject("SELECT COUNT(*) FROM label_analysis_jobs WHERE status IN ('QUEUED','PROCESSING')",Integer.class)>=pendingMaximum)
                    throw new SubmissionException(429,"OCR_QUEUE_FULL");
                db.update("INSERT INTO label_analysis_jobs(id,access_hash,role,language,photo_data,photo_sha256,status,created_at,expires_at,output) VALUES (?,?,?,?,?,?,'QUEUED',?,?,?)",
                    id,hash(token),input.role(),input.language(),image.bytes(),image.evidenceHash(),now,now+86_400_000,"{}");
                OcrDiagnostics.event("job_queued", "status", "QUEUED");
                return new Result(id,"QUEUED",null,"");
            });
        } finally { processing.release(); }
    }
    Result get(String id,String secret) { return result(authorized(job(id(id)),token(secret))); }
    Map<String,Snapshot> snapshots(Map<String,Link> links,Map<String,LabelPhotos.Stored> photos) {
        if(links==null||links.isEmpty())return Map.of();
        if(links.size()>2)throw new SubmissionException(400,"ANALYSIS_REQUEST_INVALID");
        Map<String,Snapshot> result=new LinkedHashMap<>();
        for(var entry:links.entrySet()){
            Link link=entry.getValue();
            if(link==null)throw new SubmissionException(400,"ANALYSIS_REQUEST_INVALID");
            Job job=authorized(job(id(link.id())),token(link.token()));
            var photo=photos.get(entry.getKey());
            if(!job.role().equals(entry.getKey())||photo==null||!photo.sha256().equals(job.hash()))throw new SubmissionException(409,"ANALYSIS_PHOTO_CHANGED");
            if(!Set.of("COMPLETED","RETAKE").contains(job.status()))throw new SubmissionException(409,"ANALYSIS_NOT_READY");
            result.put(entry.getKey(),json.readValue(job.output(),Snapshot.class));
        }
        return Map.copyOf(result);
    }
    @Scheduled(fixedDelayString="${ocr.poll-millis:2000}") void scheduled() { if(enabled&&workerEnabled)runNext(); }
    void runNext() {
        Job claimed=tx.execute(status->{
            guard();long now=System.currentTimeMillis();
            db.update("DELETE FROM label_analysis_jobs WHERE expires_at<?",now);
            db.update("UPDATE label_analysis_jobs SET status='QUEUED' WHERE status='PROCESSING' AND lease_until<? AND attempts<2",now);
            db.update("UPDATE label_analysis_jobs SET status='FAILED',error_code='OCR_TIMEOUT',photo_data=NULL WHERE status='PROCESSING' AND lease_until<? AND attempts>=2",now);
            var ids=db.query("SELECT id FROM label_analysis_jobs WHERE status='QUEUED' ORDER BY created_at,id LIMIT 1",(rs,i)->rs.getString(1));
            if(ids.isEmpty())return null;
            String id=ids.getFirst();
            db.update("UPDATE label_analysis_jobs SET status='PROCESSING',attempts=attempts+1,lease_until=? WHERE id=?",now+120_000,id);
            return job(id);
        });
        if(claimed==null)return;
        try (var diagnostic = diagnostics.job(claimed.id(), claimed.role(), claimed.language(), claimed.attempts())) {
            OcrDiagnostics.event("job_started", "imageBytes", claimed.photo().length);
            try {
                boolean nutrition = claimed.role().equals("nutrition");
                OcrEngine.Text text = nutrition ? engine.recognizeNutrition(claimed.photo(), claimed.language())
                    : engine.recognize(claimed.photo(), claimed.language());
                var parsed = new NutritionTextParser.Result(NutritionLabel.empty(), List.of());
                if (nutrition) parsed = text.tableNutrition() != null ? text.tableNutrition() : NutritionTextParser.parse(text.text());
                OcrDiagnostics.event("nutrition_parsed", "source", text.tableNutrition() != null ? "TABLE_AGREEMENT" : nutrition ? "FLAT_TEXT" : "NOT_REQUESTED",
                    "basis", parsed.nutrition().basis().isEmpty() ? "UNKNOWN" : parsed.nutrition().basis(), "suggestionCount", parsed.nutrition().values().size());
                for (String warning : parsed.warnings()) OcrDiagnostics.event("parser_warning", "reason", warning);
                List<String> warnings=new ArrayList<>(text.warnings());
                warnings.addAll(parsed.warnings());
                boolean retake=!OcrQuality.assess(text.text(),text.confidence()).readable()
                    ||text.warnings().stream().anyMatch(warning->warning.startsWith("RETAKE_"));
                if(retake)warnings.add("RETAKE_CLEAR_CLOSE_UP");
                else if(!text.uncertainWords().isEmpty())warnings.add("CHECK_UNCERTAIN_WORDS");
                var snapshot=new Snapshot("Tesseract/Etiket-0.6.4",claimed.language(),text.text(),text.confidence(),
                    text.uncertainWords(),text.rotation(),retake?NutritionLabel.empty():parsed.nutrition(),List.copyOf(warnings));
                db.update("UPDATE label_analysis_jobs SET status=?,output=?,error_code='',photo_data=NULL WHERE id=? AND status='PROCESSING' AND attempts=?",
                    retake?"RETAKE":"COMPLETED",json.writeValueAsString(snapshot),claimed.id(),claimed.attempts());
                OcrDiagnostics.event("job_result", "status", retake ? "RETAKE" : "COMPLETED", "confidence", text.confidence(),
                    "rawCharacters", text.text().length(), "uncertainWords", text.uncertainWords().size(),
                    "suggestionCount", snapshot.nutrition().values().size(), "warningCount", warnings.size());
                for (String warning : text.warnings()) OcrDiagnostics.event("reader_warning", "reason", warning);
            } catch(Exception e) {
                String code=e instanceof java.util.concurrent.TimeoutException?"OCR_TIMEOUT":"OCR_UNAVAILABLE";
                db.update("UPDATE label_analysis_jobs SET status=?,error_code=?,photo_data=CASE WHEN attempts>=2 THEN NULL ELSE photo_data END WHERE id=? AND status='PROCESSING' AND attempts=?",
                    claimed.attempts()<2?"QUEUED":"FAILED",code,claimed.id(),claimed.attempts());
                OcrDiagnostics.event("job_failed", "reason", code, "exceptionType", e.getClass().getSimpleName(),
                    "nextStatus", claimed.attempts() < 2 ? "QUEUED" : "FAILED");
            }
        }
    }
    private Result result(Job j){return new Result(j.id(),j.status(),Set.of("COMPLETED","RETAKE").contains(j.status())?json.readValue(j.output(),Snapshot.class):null,j.error());}
    private void guard(){db.queryForObject("SELECT id FROM submission_guard WHERE id=1 FOR UPDATE",Integer.class);}
    private List<Job> jobs(String id){return db.query("SELECT * FROM label_analysis_jobs WHERE id=? AND expires_at>=?",(rs,i)->new Job(rs.getString("id"),rs.getString("access_hash"),rs.getString("role"),rs.getString("language"),rs.getString("photo_sha256"),rs.getString("status"),rs.getString("output"),rs.getString("error_code"),rs.getBytes("photo_data"),rs.getInt("attempts")),id,System.currentTimeMillis());}
    private Job job(String id){return jobs(id).stream().findFirst().orElseThrow(()->new SubmissionException(404,"ANALYSIS_NOT_FOUND"));}
    private Job authorized(Job job,String token){if(!MessageDigest.isEqual(job.access().getBytes(StandardCharsets.US_ASCII),hash(token).getBytes(StandardCharsets.US_ASCII)))throw new SubmissionException(404,"ANALYSIS_NOT_FOUND");return job;}
    private static String hash(String s){return LabelPhotos.sha256(s.getBytes(StandardCharsets.UTF_8));}
    private static String token(String token){if(token==null||!token.matches("[A-Za-z0-9_-]{32,100}"))throw new SubmissionException(404,"ANALYSIS_NOT_FOUND");return token;}
    private static String id(String id){try{if(!UUID.fromString(id).toString().equals(id))throw new IllegalArgumentException();return id;}catch(RuntimeException e){throw new SubmissionException(400,"ANALYSIS_ID_INVALID");}}
}
