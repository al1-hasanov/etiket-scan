package az.etiket.mobile;

import android.content.*;
import android.graphics.*;
import android.os.Looper;
import android.view.*;
import android.widget.*;
import androidx.lifecycle.ViewModelProvider;
import az.etiket.core.*;
import java.io.*;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.TimeUnit;
import okhttp3.mockwebserver.*;
import org.json.*;
import org.junit.*;
import static org.junit.Assert.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,qualifiers="w360dp-h800dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class AnalysisFlowTest {
    Context context;
    @Before public void reset(){context=RuntimeEnvironment.getApplication();context.getSharedPreferences("etiket",Context.MODE_PRIVATE).edit().clear().putBoolean("az",false).commit();}
    File photo()throws Exception{
        File f=File.createTempFile("label-test",".jpg",context.getCacheDir());Bitmap b=Bitmap.createBitmap(40,30,Bitmap.Config.ARGB_8888);b.eraseColor(Color.WHITE);
        try(OutputStream out=new FileOutputStream(f)){b.compress(Bitmap.CompressFormat.JPEG,90,out);}b.recycle();return f;
    }
    JSONObject result()throws Exception{return new JSONObject().put("rawText","Milk, sugar, salt.").put("confidence",91).put("uncertainWords",new JSONArray()).put("nutrition",new JSONObject().put("basis","PER_100_ML").put("values",new JSONObject().put("salt",0).put("protein",3)));}
    @Test public void analysisUsesPrivateCapabilitiesAndPreservesOriginalText()throws Exception{
        try(MockWebServer server=new MockWebServer()){
            server.start(InetAddress.getByName("127.0.0.1"),0);String base=server.url("/").toString(),id=UUID.randomUUID().toString(),token="a".repeat(64);
            JSONObject request=new JSONObject().put("clientRequestId",id).put("token",token).put("role","ingredients").put("language","en");
            server.enqueue(new MockResponse().setResponseCode(202).setBody(new JSONObject().put("id",id).put("status","QUEUED").toString()));
            assertEquals("QUEUED",AnalysisClient.start(base,request,photo()).getString("status"));
            RecordedRequest posted=server.takeRequest(2,TimeUnit.SECONDS);assertEquals("/api/v1/analysis-jobs",posted.getPath());assertNull(posted.getHeader("Authorization"));assertTrue(posted.getBody().readString(StandardCharsets.UTF_8).contains("name=\"photo\""));
            server.enqueue(new MockResponse().setBody(new JSONObject().put("id",id).put("status","COMPLETED").put("result",result()).toString()));
            assertEquals("Milk, sugar, salt.",AnalysisClient.status(base,new JSONObject().put("id",id).put("token",token)).getJSONObject("result").getString("rawText"));
            RecordedRequest read=server.takeRequest(2,TimeUnit.SECONDS);assertEquals("Bearer "+token,read.getHeader("Authorization"));assertFalse(read.getPath().contains(token));
        }
    }
    @Test public void completedDraftSurvivesRotationWithoutOverwritingUserEdits()throws Exception{
        try(MockWebServer server=new MockWebServer()){
            server.start(InetAddress.getByName("127.0.0.1"),0);String base=ApiClient.validateBase(server.url("/").toString());
            var intent=new Intent(context,SubmissionActivity.class).putExtra("barcode","5201360553457").putExtra("name","Synthetic test package");
            try(var controller=Robolectric.buildActivity(SubmissionActivity.class,intent).setup()){
                SubmissionState state=new ViewModelProvider(controller.get()).get(SubmissionState.class);
                File destination=new File(state.directory(),"ingredients.jpg");java.nio.file.Files.copy(photo().toPath(),destination.toPath());state.photos.put("ingredients",destination);
                EditText editor=controller.get().findViewById(R.id.submission_ingredients);editor.setText("My corrected text");
                server.setDispatcher(new Dispatcher(){@Override public MockResponse dispatch(RecordedRequest r){try{String id;
                    if(r.getMethod().equals("POST")){String body=r.getBody().readString(StandardCharsets.UTF_8);java.util.regex.Matcher m=java.util.regex.Pattern.compile("\\\"clientRequestId\\\":\\\"([^\\\"]+)\\\"").matcher(body);if(!m.find())return new MockResponse().setResponseCode(400);id=m.group(1);}
                    else id=r.getPath().substring(r.getPath().lastIndexOf('/')+1);
                    return new MockResponse().setResponseCode(r.getMethod().equals("POST")?202:200).setBody(new JSONObject().put("id",id).put("status","COMPLETED").put("result",result()).toString());
                }catch(Exception e){return new MockResponse().setResponseCode(500);}}});
                state.analyze(base,"ingredients","en");long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(8);
                while(state.busy&&System.nanoTime()<deadline){Thread.sleep(20);Shadows.shadowOf(Looper.getMainLooper()).idle();}
                Shadows.shadowOf(Looper.getMainLooper()).idle();assertFalse(state.busy);assertTrue(state.analysisResults.containsKey("ingredients"));
                assertEquals("My corrected text",editor.getText().toString());assertEquals(1,state.references(base).length());assertEquals(0,state.references("https://other.example").length());
                controller.recreate();assertEquals("My corrected text",((EditText)controller.get().findViewById(R.id.submission_ingredients)).getText().toString());
                assertTrue(new ViewModelProvider(controller.get()).get(SubmissionState.class).analysisResults.containsKey("ingredients"));
                capture(controller.get().getWindow().getDecorView(),"ocr-draft-en.png");
            }
        }
    }
    @Test public void firstReadUsesTheStraightenedCropAndRejectsAnIncompleteDraft() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.start(InetAddress.getByName("127.0.0.1"), 0);
            String base = ApiClient.validateBase(server.url("/").toString());
            context.getSharedPreferences("etiket", Context.MODE_PRIVATE).edit().putString("base", base).commit();
            server.setDispatcher(new Dispatcher() {
                @Override public MockResponse dispatch(RecordedRequest request) {
                    try {
                        String body = request.getBody().clone().readString(StandardCharsets.UTF_8);
                        int start = body.indexOf('{');
                        String id = new JSONObject(body.substring(start, body.indexOf('}', start) + 1)).getString("clientRequestId");
                        return new MockResponse().setResponseCode(202).setBody(new JSONObject().put("id", id)
                            .put("status", "RETAKE").put("result", result()).toString());
                    } catch (Exception e) {
                        return new MockResponse().setResponseCode(400);
                    }
                }
            });
            try (var parent = Robolectric.buildActivity(SubmissionActivity.class).setup()) {
                SubmissionState state = new ViewModelProvider(parent.get()).get(SubmissionState.class);
                File original = new File(state.directory(), "ingredients.jpg");
                Bitmap label = Bitmap.createBitmap(800, 450, Bitmap.Config.ARGB_8888);
                label.eraseColor(Color.WHITE);
                Canvas canvas = new Canvas(label);
                Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
                paint.setColor(0xfff5d36c);
                canvas.drawRect(100, 100, 700, 350, paint);
                paint.setColor(Color.BLACK);
                paint.setTextSize(34);
                canvas.drawText("Zutaten: Wasser, Zucker, Fruktose", 148, 225, paint);
                LabelPhotoProcessor.writeJpeg(label, original, 95);
                label.recycle();
                byte[] originalBytes = java.nio.file.Files.readAllBytes(original.toPath());
                state.photos.put("ingredients", original);
                EditText ingredients = parent.get().findViewById(R.id.submission_ingredients);
                ingredients.setText("My corrected ingredients");
                ((Spinner) parent.get().findViewById(R.id.submission_language)).setSelection(4);

                parent.get().findViewById(R.id.read_ingredients).performClick();
                var started = Shadows.shadowOf(parent.get()).getNextStartedActivityForResult();
                assertEquals(LabelCropActivity.class.getName(), started.intent.getComponent().getClassName());
                assertEquals(0, server.getRequestCount());
                try (var editor = Robolectric.buildActivity(LabelCropActivity.class, started.intent).setup()) {
                    float[] selected = {.16f, .28f, .84f, .24f, .83f, .72f, .18f, .68f};
                    LabelCropView view = editor.get().findViewById(R.id.label_crop_canvas);
                    view.restore(selected, null);
                    assertTrue(view.toggleZoom());
                    editor.recreate();
                    view = editor.get().findViewById(R.id.label_crop_canvas);
                    assertArrayEquals(selected, view.corners(), .001f);
                    assertTrue(view.window().width() < 1);
                    capture(editor.get().getWindow().getDecorView(), "label-crop-en.png");
                    editor.get().findViewById(R.id.label_crop_use).performClick();
                    await(() -> editor.get().isFinishing());
                    var finished = Shadows.shadowOf(editor.get());
                    assertEquals(android.app.Activity.RESULT_OK, finished.getResultCode());
                    parent.get().getActivityResultRegistry().dispatchResult(started.requestCode,
                        finished.getResultCode(), finished.getResultIntent());
                }
                await(() -> !state.busy && state.analysisResults.containsKey("ingredients"));
                assertTrue(state.hasCrop("ingredients"));
                assertArrayEquals(originalBytes, java.nio.file.Files.readAllBytes(
                    new File(state.directory(), "ingredients-original.jpg").toPath()));
                assertFalse(Arrays.equals(originalBytes, java.nio.file.Files.readAllBytes(original.toPath())));
                RecordedRequest request = server.takeRequest(2, TimeUnit.SECONDS);
                assertNotNull(request);
                String body = request.getBody().readString(StandardCharsets.UTF_8);
                int metadata = body.indexOf('{');
                assertEquals("de", new JSONObject(body.substring(metadata, body.indexOf('}', metadata) + 1)).getString("language"));
                assertFalse(parent.get().findViewById(R.id.use_ocr_ingredients).isEnabled());
                assertEquals(0, state.references(base).length());
                assertEquals("My corrected ingredients", ingredients.getText().toString());
            }
        }
    }

    private void await(java.util.function.BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) {
            Thread.sleep(20);
            Shadows.shadowOf(Looper.getMainLooper()).idle();
        }
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        assertTrue(condition.getAsBoolean());
    }

    @Test public void nutritionReferenceAndUnknownFieldsSurviveSavingAndRotation()throws Exception{
        var intent=new Intent(context,SubmissionActivity.class).putExtra("barcode","5201360553457").putExtra("name","Synthetic test package");
        try(var controller=Robolectric.buildActivity(SubmissionActivity.class,intent).setup()){
            ((Spinner)controller.get().findViewById(R.id.nutrition_basis)).setSelection(2);
            ((EditText)controller.get().findViewById(R.id.nutrition_salt)).setText("0");controller.recreate();
            assertEquals(2,((Spinner)controller.get().findViewById(R.id.nutrition_basis)).getSelectedItemPosition());assertEquals("0",((EditText)controller.get().findViewById(R.id.nutrition_salt)).getText().toString());
            assertEquals("",((EditText)controller.get().findViewById(R.id.nutrition_sugars)).getText().toString());
        }
        var old=StarterCatalogue.load(context).get(0);
        var product=new Product(old.barcode(),old.name(),old.brand(),old.quantity(),old.ingredients(),old.ingredientsLanguage(),"",Map.of(),List.of(),List.of(),"REVIEWED_LABEL","https://example.com/source","","",false,null,null,new NutritionLabel("PER_100_ML",Map.of("salt",0d,"protein",3d)));
        assertEquals(product,ProductJson.read(ProductJson.write(product)));
        ProductStore store=new ProductStore(context.getSharedPreferences("etiket",Context.MODE_PRIVATE));store.toggleSaved(product);assertEquals(product,store.list("saved").get(0));
    }
    private void capture(View view,String name)throws Exception{
        view.measure(View.MeasureSpec.makeMeasureSpec(1080,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(2400,View.MeasureSpec.EXACTLY));view.layout(0,0,1080,2400);
        Bitmap b=Bitmap.createBitmap(1080,2400,Bitmap.Config.ARGB_8888);view.draw(new Canvas(b));File folder=new File("build/qa");folder.mkdirs();try(OutputStream out=new FileOutputStream(new File(folder,name))){b.compress(Bitmap.CompressFormat.PNG,100,out);}b.recycle();
    }
}
