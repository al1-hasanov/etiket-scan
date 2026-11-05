package az.etiket.mobile;

import android.app.AlertDialog;
import android.content.*;
import android.graphics.*;
import android.net.Uri;
import android.os.Looper;
import android.view.*;
import android.widget.*;
import androidx.core.content.FileProvider;
import androidx.lifecycle.ViewModelProvider;
import androidx.exifinterface.media.ExifInterface;
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
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.*;
import org.robolectric.shadows.ShadowAlertDialog;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,qualifiers="w360dp-h800dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class SubmissionFlowTest {
    private Context context;
    @Before public void reset(){context=RuntimeEnvironment.getApplication();context.getSharedPreferences("etiket",Context.MODE_PRIVATE).edit().clear().putBoolean("az",false).commit();}
    private Intent form(){return new Intent(context,SubmissionActivity.class).putExtra("barcode","5201360553457").putExtra("name","Synthetic package fixture");}
    @Test public void missingProductScreenStartsPrefilledPhotoSubmission(){
        try(var controller=Robolectric.buildActivity(MainActivity.class).setup()){
            click(controller.get().getWindow().getDecorView(),"7 DAYS MİDİ KRUASSAN 65 Q BİŞMİŞ SÜDLÜ");
            click(controller.get().getWindow().getDecorView(),"Submit label photos");
            Intent intent=Shadows.shadowOf(controller.get()).getNextStartedActivity();
            assertEquals(SubmissionActivity.class.getName(),intent.getComponent().getClassName());assertEquals("5201360553457",intent.getStringExtra("barcode"));
        }
    }
    @Test public void formAndDraftIdSurviveRotation() throws Exception {
        try(var controller=Robolectric.buildActivity(SubmissionActivity.class,form()).setup()){
            SubmissionState before=new ViewModelProvider(controller.get()).get(SubmissionState.class);String id=before.id;
            ((EditText)controller.get().findViewById(R.id.submission_quantity)).setText("65 g");
            ((CheckBox)controller.get().findViewById(R.id.submission_consent)).setChecked(true);
            controller.recreate();
            assertEquals(id,new ViewModelProvider(controller.get()).get(SubmissionState.class).id);
            assertEquals("65 g",((EditText)controller.get().findViewById(R.id.submission_quantity)).getText().toString());
            assertTrue(((CheckBox)controller.get().findViewById(R.id.submission_consent)).isChecked());
            capture(controller.get().getWindow().getDecorView(),"submission-form-en.png");
        }
    }
    @Test public void consentAndThreePhotosAreRequiredBeforeAnyNetworkCall(){
        try(var controller=Robolectric.buildActivity(SubmissionActivity.class,form()).setup()){
            View root=controller.get().getWindow().getDecorView();click(root,"Submit for review");
            assertNotNull(find(root,"Please select the publication consent."));
            ((CheckBox)controller.get().findViewById(R.id.submission_consent)).setChecked(true);click(root,"Submit for review");
            assertNotNull(find(root,"Add all three package photos."));
        }
    }
    @Test public void photoPreparationRotatesPixelsAndRemovesGpsMetadata() throws Exception {
        File folder=new File(context.getCacheDir(),"photo-test");folder.mkdirs();File original=new File(folder,"original.jpg"),clean=new File(folder,"clean.jpg");
        Bitmap image=Bitmap.createBitmap(120,80,Bitmap.Config.ARGB_8888);image.eraseColor(Color.GREEN);
        try(OutputStream out=new FileOutputStream(original)){assertTrue(image.compress(Bitmap.CompressFormat.JPEG,90,out));}image.recycle();
        ExifInterface source=new ExifInterface(original);source.setAttribute(ExifInterface.TAG_ORIENTATION,"6");source.setLatLong(0,0);source.saveAttributes();
        Uri uri=Uri.fromFile(original);LabelPhotoProcessor.prepare(context,uri,clean);
        Bitmap prepared=BitmapFactory.decodeFile(clean.getPath());assertEquals(80,prepared.getWidth());assertEquals(120,prepared.getHeight());prepared.recycle();
        ExifInterface stripped=new ExifInterface(clean);assertNull(stripped.getAttribute(ExifInterface.TAG_GPS_LATITUDE));assertTrue(clean.length()<2*1024*1024);
    }
    @Test public void multipartUploadAndReceiptTrackingUseThePublicApiOnly() throws Exception {
        try(MockWebServer server=new MockWebServer()){
            server.start(InetAddress.getByName("127.0.0.1"),0);String base=server.url("/").toString();
            String id=UUID.randomUUID().toString();JSONObject metadata=new JSONObject().put("clientRequestId",id).put("barcode","5201360553457").put("name","Test package").put("consentToPublish",true);
            Map<String,File> files=photos();
            server.enqueue(new MockResponse().setResponseCode(201).setBody(new JSONObject().put("id",id).put("barcode","5201360553457").put("status","PENDING").put("submittedAt","2026-11-05T00:00:00Z").toString()));
            JSONObject receipt=SubmissionClient.submit(base,metadata,files);assertEquals("PENDING",receipt.getString("status"));
            RecordedRequest request=server.takeRequest(2,TimeUnit.SECONDS);assertNotNull(request);assertEquals("/api/v1/submissions",request.getPath());assertNull(request.getHeader("Authorization"));
            String body=request.getBody().readString(StandardCharsets.ISO_8859_1);assertTrue(body.contains("name=\"metadata\""));for(String role:SubmissionClient.ROLES)assertTrue(body.contains("name=\""+role+"\"; filename="));
            var prefs=context.getSharedPreferences("etiket",Context.MODE_PRIVATE);SubmissionClient.remember(prefs,base,receipt,"Test package");
            server.enqueue(new MockResponse().setBody(new JSONObject(receipt.toString()).put("status","APPROVED").toString()));
            JSONObject refreshed=SubmissionClient.status(base,id);SubmissionClient.remember(prefs,base,refreshed,"Test package");
            assertEquals(1,SubmissionClient.receipts(prefs).size());assertEquals("APPROVED",SubmissionClient.receipts(prefs).get(0).getString("status"));
            try(var controller=Robolectric.buildActivity(MySubmissionsActivity.class).setup()){assertNotNull(find(controller.get().getWindow().getDecorView(),"Approved"));}
        }
    }
    @Test public void failedUploadDoesNotInventAPendingReceiptAndAllowsTheSameIdToRetry() throws Exception {
        try(MockWebServer server=new MockWebServer()){
            server.start(InetAddress.getByName("127.0.0.1"),0);String base=server.url("/").toString();String id=UUID.randomUUID().toString();
            JSONObject metadata=new JSONObject().put("clientRequestId",id);
            server.enqueue(new MockResponse().setResponseCode(503).setBody("{\"code\":\"REVIEW_NOT_CONFIGURED\"}"));
            try{SubmissionClient.submit(base,metadata,photos());fail();}catch(ApiClient.Failure e){assertEquals("REVIEW_NOT_CONFIGURED",e.code);}
            server.enqueue(new MockResponse().setResponseCode(201).setBody(new JSONObject().put("id",id).put("status","PENDING").toString()));
            assertEquals(id,SubmissionClient.submit(base,metadata,photos()).getString("id"));
        }
    }
    @Test public void anInFlightUploadSurvivesRotationAndStoresOneConfirmedReceipt() throws Exception {
        try(MockWebServer server=new MockWebServer()){
            server.start(InetAddress.getByName("127.0.0.1"),0);String base=server.url("/").toString();
            var prefs=context.getSharedPreferences("etiket",Context.MODE_PRIVATE);prefs.edit().putString("base",ApiClient.validateBase(base)).commit();
            try(var controller=Robolectric.buildActivity(SubmissionActivity.class,form()).setup()){
                SubmissionState state=new ViewModelProvider(controller.get()).get(SubmissionState.class);String id=state.id;state.photos.putAll(photos());
                assertSame("The upload must use the current application context",context,state.getApplication());
                server.enqueue(new MockResponse().setResponseCode(201).setBodyDelay(1000,TimeUnit.MILLISECONDS).setBody(new JSONObject().put("id",id).put("barcode","5201360553457").put("status","PENDING").put("submittedAt","2026-11-05T00:00:00Z").toString()));
                ((CheckBox)controller.get().findViewById(R.id.submission_consent)).setChecked(true);
                click(controller.get().getWindow().getDecorView(),"Submit for review");
                assertTrue("Upload should have started",state.busy);
                assertNotNull("Wait until the upload is actually in flight",server.takeRequest(5,TimeUnit.SECONDS));
                controller.recreate();
                assertSame("An in-flight upload must retain its ViewModel",state,new ViewModelProvider(controller.get()).get(SubmissionState.class));
                assertEquals(id,new ViewModelProvider(controller.get()).get(SubmissionState.class).id);
                long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(8);
                while((SubmissionClient.receipts(prefs).stream().noneMatch(r->r.optString("status").equals("PENDING"))||ShadowAlertDialog.getLatestDialog()==null)&&System.nanoTime()<deadline){Thread.sleep(20);Shadows.shadowOf(Looper.getMainLooper()).idle();}
                Shadows.shadowOf(Looper.getMainLooper()).idle();
                assertEquals("Only one upload should reach the server",1,server.getRequestCount());
                assertEquals("Device receipt should be stored; event="+state.event.getValue(),1,SubmissionClient.receipts(prefs).size());
                assertEquals("PENDING",SubmissionClient.receipts(prefs).get(0).optString("status"));
                AlertDialog dialog=(AlertDialog)ShadowAlertDialog.getLatestDialog();assertNotNull(dialog);
            }
        }
    }
    @Test public void catalogueRefreshIncludesReviewedProductsAndSavesTheirSourceOrigin() throws Exception {
        try(MockWebServer server=new MockWebServer()){
            server.start(InetAddress.getByName("127.0.0.1"),0);String base=server.url("/").toString();
            Product old=StarterCatalogue.load(context).get(0);String id=UUID.randomUUID().toString();
            JSONObject reviewed=ProductJson.write(old).put("source","REVIEWED_LABEL").put("sourceUrl","/sources/labels/"+id).put("ingredients","su, duz").put("ingredientsLanguage","az");
            reviewed.getJSONObject("evidence").put("labelPhotoChecked",true).put("reviewStatus","LABEL_PHOTO_REVIEWED").put("ingredientsSourceUrl","/api/v1/labels/"+id+"/photos/ingredients");
            server.enqueue(new MockResponse().setBody(new JSONObject().put("page",0).put("total",1).put("items",new JSONArray().put(reviewed)).toString()));
            List<Product> products=ApiClient.catalogue(base);assertEquals(1,products.size());assertEquals(ApiClient.validateBase(base)+"/sources/labels/"+id,products.get(0).sourceUrl());
            ProductStore store=new ProductStore(context.getSharedPreferences("etiket",Context.MODE_PRIVATE));store.cacheCatalogue(base,products);assertEquals(products,store.cachedCatalogue(base));
            assertTrue(store.cachedCatalogue("https://different.example").isEmpty());store.toggleSaved(products.get(0));
            try(var controller=Robolectric.buildActivity(MainActivity.class).setup()){
                click(controller.get().getWindow().getDecorView(),"Saved");click(controller.get().getWindow().getDecorView(),old.name());
                assertNotNull(find(controller.get().getWindow().getDecorView(),"Checked against label photos"));
                assertNotNull(find(controller.get().getWindow().getDecorView(),"Open reviewed label photos"));
                assertNotNull(find(controller.get().getWindow().getDecorView(),"Ingredient explanations (Azerbaijani)"));
                capture(controller.get().getWindow().getDecorView(),"reviewed-product-en.png");
            }
        }
    }
    @Test public void cameraUrisAreLimitedToPrivateDraftCache() throws Exception {
        File folder=new File(context.getCacheDir(),"label-drafts/test");folder.mkdirs();File photo=new File(folder,"front.jpg");photo.createNewFile();
        Uri uri=FileProvider.getUriForFile(context,context.getPackageName()+".labelphotos",photo);assertEquals("content",uri.getScheme());
        File outside=new File(context.getFilesDir(),"private.txt");outside.createNewFile();
        try{FileProvider.getUriForFile(context,context.getPackageName()+".labelphotos",outside);fail();}catch(IllegalArgumentException expected){}
    }
    private Map<String,File> photos() throws Exception {
        Map<String,File> result=new HashMap<>();for(String role:SubmissionClient.ROLES){File file=File.createTempFile(role,".jpg",context.getCacheDir());
            Bitmap image=Bitmap.createBitmap(24,16,Bitmap.Config.ARGB_8888);image.eraseColor(Color.GREEN);try(OutputStream out=new FileOutputStream(file)){image.compress(Bitmap.CompressFormat.JPEG,90,out);}image.recycle();result.put(role,file);}return result;
    }
    private static TextView find(View view,String value){if(view instanceof TextView text&&text.getText().toString().equals(value))return text;if(view instanceof ViewGroup group)for(int i=0;i<group.getChildCount();i++){TextView result=find(group.getChildAt(i),value);if(result!=null)return result;}return null;}
    private static void click(View root,String value){TextView text=find(root,value);assertNotNull(value,text);View button=text;while(!button.isClickable()&&button.getParent() instanceof View)button=(View)button.getParent();assertTrue(button.performClick());}
    private static void capture(View view,String name) throws Exception {
        view.measure(View.MeasureSpec.makeMeasureSpec(1080,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(2400,View.MeasureSpec.EXACTLY));view.layout(0,0,1080,2400);
        Bitmap bitmap=Bitmap.createBitmap(1080,2400,Bitmap.Config.ARGB_8888);view.draw(new Canvas(bitmap));File folder=new File("build/qa");folder.mkdirs();try(OutputStream out=new FileOutputStream(new File(folder,name))){bitmap.compress(Bitmap.CompressFormat.PNG,100,out);}bitmap.recycle();
    }
}
