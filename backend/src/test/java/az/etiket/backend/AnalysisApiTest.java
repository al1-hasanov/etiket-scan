package az.etiket.backend;

import az.etiket.core.NutritionLabel;
import static az.etiket.backend.SubmissionModels.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;

@SpringBootTest(properties={"spring.datasource.url=jdbc:h2:mem:analysis;DB_CLOSE_DELAY=-1","review.password=only-for-local-tests","ocr.worker-enabled=false","ocr.max-pending=2"})
class AnalysisApiTest {
    @Autowired LabelAnalysis analysis;
    @Autowired SubmissionService submissions;
    @Autowired JdbcTemplate db;
    static final AtomicInteger failures=new AtomicInteger();
    static final AtomicBoolean incomplete=new AtomicBoolean();
    @TestConfiguration static class Config {
        @Bean @Primary OcrEngine deterministic(){return (bytes,lang)->{
            if(failures.getAndDecrement()>0)throw new java.io.IOException("Transient test failure");
            return new OcrEngine.Text("Per 100 ml\nEnergy 60 kcal\nFat 3 g\nProtein 4 g\nSalt 0 g",94,List.of(),0,
                incomplete.get()?List.of("RETAKE_INCOMPLETE_TEXT"):List.of());
        };}
    }
    @BeforeEach void reset(){
        for(String table:List.of("reviewed_products","submission_ocr","submission_nutrition_photos","submission_photos","label_submissions","label_analysis_jobs"))db.update("DELETE FROM "+table);
        failures.set(0);
        incomplete.set(false);
    }
    LabelAnalysis.Request request(){return new LabelAnalysis.Request(UUID.randomUUID().toString(),UUID.randomUUID().toString().replace("-","")+"testsecret","nutrition","en");}
    MockMultipartFile image(){return new MockMultipartFile("photo","label.png","image/png",SubmissionFixtures.photo());}
    @Test void jobsArePrivateIdempotentAndDoNotPublishAnything(){
        var r=request();assertEquals("QUEUED",analysis.create(r,image()).status());assertEquals(r.clientRequestId(),analysis.create(r,image()).id());
        assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM label_analysis_jobs",Integer.class));
        assertThrows(SubmissionException.class,()->analysis.get(r.clientRequestId(),"wrongsecretwrongsecretwrongsecretwrong"));
        analysis.runNext();var result=analysis.get(r.clientRequestId(),r.token());assertEquals("COMPLETED",result.status());
        assertEquals("PER_100_ML",result.result().nutrition().basis());assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM reviewed_products",Integer.class));
        assertNull(db.queryForObject("SELECT photo_data FROM label_analysis_jobs WHERE id=?",byte[].class,r.clientRequestId()));
    }
    @Test void incompleteReadsCannotSuggestNutritionValues() {
        incomplete.set(true);
        var request = request();
        analysis.create(request, image());
        analysis.runNext();
        var result = analysis.get(request.clientRequestId(), request.token());
        assertEquals("RETAKE", result.status());
        assertTrue(result.result().rawText().contains("Fat 3 g"));
        assertTrue(result.result().nutrition().values().isEmpty());
        assertTrue(result.result().warnings().contains("RETAKE_INCOMPLETE_TEXT"));
    }

    @Test void queueLimitsConflictingRequestsAndUnsupportedLanguagesAreHandled(){
        var r=request();analysis.create(r,image());analysis.create(request(),image());
        assertEquals(429,assertThrows(SubmissionException.class,()->analysis.create(request(),image())).status);
        var changed=new LabelAnalysis.Request(r.clientRequestId(),r.token(),"ingredients","en");
        assertEquals(409,assertThrows(SubmissionException.class,()->analysis.create(changed,image())).status);
        assertEquals(400,assertThrows(SubmissionException.class,()->analysis.create(new LabelAnalysis.Request(UUID.randomUUID().toString(),r.token(),"ingredients","xx"),image())).status);
    }
    @Test void retriesAndExpiredWorkerLeasesAreBoundedAndSurviveNewServiceInstances(){
        var r=request();analysis.create(r,image());failures.set(3);analysis.runNext();assertEquals("QUEUED",analysis.get(r.clientRequestId(),r.token()).status());
        analysis.runNext();assertEquals("FAILED",analysis.get(r.clientRequestId(),r.token()).status());
        var next=request();analysis.create(next,image());
        db.update("UPDATE label_analysis_jobs SET status='PROCESSING',attempts=1,lease_until=0 WHERE id=?",next.clientRequestId());failures.set(0);
        analysis.runNext();assertEquals("COMPLETED",analysis.get(next.clientRequestId(),next.token()).status());
        db.update("UPDATE label_analysis_jobs SET expires_at=0 WHERE id=?",next.clientRequestId());assertEquals(404,assertThrows(SubmissionException.class,()->analysis.get(next.clientRequestId(),next.token())).status);
    }
    @Test void nutritionNeedsItsOwnPhotoAndReviewerConfirmationAndKeepsRawOcrPrivate(){
        var r=request();analysis.create(r,image());analysis.runNext();var old=SubmissionFixtures.draft("3017620422003");
        var nutrition=new NutritionLabel("PER_100_ML",Map.of("protein",4d,"salt",0d));
        var draft=new Draft(old.clientRequestId(),old.barcode(),old.name(),old.brand(),old.quantity(),"Milk, salt","en","",true,nutrition,Map.of("nutrition",new LabelAnalysis.Link(r.clientRequestId(),r.token())));
        var photos=new LinkedHashMap<>(SubmissionFixtures.photos());
        assertEquals(400,assertThrows(SubmissionException.class,()->submissions.create(draft,photos)).status);
        photos.put("nutrition",image());submissions.create(draft,photos);
        var detail=submissions.detail(draft.clientRequestId());assertTrue(detail.analysis().get("nutrition").rawText().contains("Per 100 ml"));
        assertTrue(detail.submitted().analysis().isEmpty());
        var base=SubmissionFixtures.approval(0,"");
        var unconfirmed=new Approval(0,base.name(),base.brand(),base.quantity(),base.ingredients(),"az","","",true,nutrition,false);
        assertEquals(400,assertThrows(SubmissionException.class,()->submissions.approve(draft.clientRequestId(),unconfirmed,"test")).status);
        var product=submissions.approve(draft.clientRequestId(),new Approval(0,base.name(),base.brand(),base.quantity(),base.ingredients(),"az","","",true,nutrition,true),"test");
        assertEquals("PER_100_ML",product.nutrition().basis());assertTrue(product.nutrients().isEmpty());assertEquals(4,submissions.publicLabel(draft.clientRequestId()).photos().size());
        assertArrayEquals(submissions.photo(draft.clientRequestId(),"nutrition",true),submissions.photo(draft.clientRequestId(),"nutrition",false));
    }
    @Test void replacingThePhotoInvalidatesOcrEvidence(){
        var r=request();analysis.create(r,image());analysis.runNext();
        var files=new HashMap<String,LabelPhotos.Stored>();files.put("nutrition",new LabelPhotos.Stored(new byte[0],1,1,"different"));
        assertEquals(409,assertThrows(SubmissionException.class,()->analysis.snapshots(Map.of("nutrition",new LabelAnalysis.Link(r.clientRequestId(),r.token())),files)).status);
    }
}
