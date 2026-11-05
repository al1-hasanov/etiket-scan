package az.etiket.mobile;

import android.content.*;
import android.graphics.Insets;
import android.os.*;
import android.view.*;
import android.widget.*;
import androidx.activity.ComponentActivity;
import java.util.*;
import java.util.concurrent.*;
import org.json.*;

public class MySubmissionsActivity extends ComponentActivity {
    private boolean az;
    private SharedPreferences prefs;
    private LinearLayout body;
    private Button refresh;
    private TextView notice;
    private final ExecutorService worker=Executors.newSingleThreadExecutor();
    @Override public void onCreate(Bundle state){super.onCreate(state);prefs=getSharedPreferences("etiket",MODE_PRIVATE);az=prefs.getBoolean("az",true);render();}
    private String t(String a,String e){return az?a:e;}
    private int dp(int value){return Math.round(value*getResources().getDisplayMetrics().density);}
    private void line(String value,int size,boolean bold){TextView text=new TextView(this);text.setText(value);text.setTextSize(size);text.setTextColor(0xff19372a);text.setPadding(0,dp(8),0,dp(8));if(bold)text.setTypeface(null,android.graphics.Typeface.BOLD);body.addView(text);}
    private void render(){
        LinearLayout outer=new LinearLayout(this);outer.setOrientation(LinearLayout.VERTICAL);outer.setPadding(dp(20),dp(16),dp(20),dp(16));outer.setBackgroundColor(0xfff6f8f5);setContentView(outer);
        if(Build.VERSION.SDK_INT>=30){getWindow().setDecorFitsSystemWindows(false);outer.setOnApplyWindowInsetsListener((view,insets)->{Insets bars=insets.getInsets(WindowInsets.Type.systemBars());outer.setPadding(dp(20)+bars.left,dp(16)+bars.top,dp(20)+bars.right,dp(16)+bars.bottom);return insets;});outer.requestApplyInsets();}
        ScrollView scroll=new ScrollView(this);body=new LinearLayout(this);body.setOrientation(LinearLayout.VERTICAL);scroll.addView(body);outer.addView(scroll);
        line(t("Göndərdiklərim","My submissions"),25,true);
        line(t("Təsdiqdən sonra barkodu yenidən skan edin və ya kataloqu yeniləyin.","After approval, scan the barcode again or refresh the catalogue."),14,false);
        refresh=new Button(this);refresh.setAllCaps(false);refresh.setText(t("Vəziyyəti yenilə","Refresh status"));refresh.setOnClickListener(view->refresh());body.addView(refresh);
        notice=new TextView(this);notice.setTextColor(0xff9e3232);notice.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);body.addView(notice);
        List<JSONObject> receipts=SubmissionClient.receipts(prefs);
        if(receipts.isEmpty())line(t("Hələ məlumat göndərməmisiniz.","You have not submitted any labels yet."),16,false);
        for(JSONObject receipt:receipts){
            line(receipt.optString("name"),18,true);line(receipt.optString("barcode"),14,false);
            String status=switch(receipt.optString("status")){case "PENDING"->t("Yoxlama gözləyir","Awaiting review");case "APPROVED"->t("Təsdiqlənib","Approved");case "REJECTED"->t("Rədd edilib","Rejected");default->t("Göndərmə hələ təsdiqlənməyib","Upload not yet confirmed");};line(status,16,true);
            if(!receipt.optString("note").isEmpty())line(receipt.optString("note"),14,false);
            String date=receipt.optString("submittedAt");if(date.length()>=10)line(date.substring(0,10),12,false);
        }
    }
    private void refresh(){
        refresh.setEnabled(false);notice.setText(t("Yoxlanılır…","Checking…"));
        worker.execute(()->{
            Set<String> unavailable=new HashSet<>();
            for(JSONObject receipt:SubmissionClient.receipts(prefs)){
                String base=receipt.optString("base");if(unavailable.contains(base))continue;
                try {JSONObject updated=SubmissionClient.status(base,receipt.optString("id"));SubmissionClient.remember(prefs,base,updated,receipt.optString("name"));}
                catch(ApiClient.Failure error){if(!error.code.equals("SUBMISSION_NOT_FOUND"))unavailable.add(base);}
            }
            runOnUiThread(()->{if(isDestroyed())return;render();if(!unavailable.isEmpty())notice.setText(t("Bağlantı alınmadı. Son saxlanmış vəziyyət göstərilir.","Could not connect. Showing the last saved status."));});
        });
    }
    @Override protected void onDestroy(){worker.shutdownNow();super.onDestroy();}
}
