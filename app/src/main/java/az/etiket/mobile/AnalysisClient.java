package az.etiket.mobile;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.json.*;

/** Each analysis is private to the random capability retained in this device's draft cache. */
final class AnalysisClient {
    private AnalysisClient() { }
    static JSONObject start(String base,JSONObject request,File photo) throws ApiClient.Failure {
        HttpURLConnection connection=null;
        try {
            if(photo==null||!photo.isFile())throw new ApiClient.Failure("PHOTOS_REQUIRED");
            if(photo.length()>2*1024*1024)throw new ApiClient.Failure("PHOTO_TOO_LARGE");
            String boundary="etiket-analysis-"+UUID.randomUUID();
            byte[] begin=("--"+boundary+"\r\nContent-Disposition: form-data; name=\"metadata\"\r\nContent-Type: application/json\r\n\r\n"+request+"\r\n--"+boundary+"\r\nContent-Disposition: form-data; name=\"photo\"; filename=\"label.jpg\"\r\nContent-Type: image/jpeg\r\n\r\n").getBytes(StandardCharsets.UTF_8);
            byte[] end=("\r\n--"+boundary+"--\r\n").getBytes(StandardCharsets.UTF_8);
            connection=connect(base,"/api/v1/analysis-jobs");connection.setRequestMethod("POST");connection.setDoOutput(true);
            connection.setRequestProperty("Content-Type","multipart/form-data; boundary="+boundary);connection.setFixedLengthStreamingMode(begin.length+photo.length()+end.length);
            try(OutputStream out=connection.getOutputStream();InputStream in=new FileInputStream(photo)){out.write(begin);byte[] b=new byte[8192];int n;while((n=in.read(b))!=-1)out.write(b,0,n);out.write(end);}
            return response(connection,request.optString("clientRequestId"));
        } catch(ApiClient.Failure e){throw e;}catch(Exception e){throw new ApiClient.Failure("CONNECTION_FAILED");}
        finally{if(connection!=null)connection.disconnect();}
    }
    static JSONObject status(String base,JSONObject reference)throws ApiClient.Failure{
        HttpURLConnection connection=null;
        try{
            String id=UUID.fromString(reference.getString("id")).toString();
            connection=connect(base,"/api/v1/analysis-jobs/"+id);connection.setRequestProperty("Authorization","Bearer "+reference.getString("token"));
            return response(connection,id);
        }catch(ApiClient.Failure e){throw e;}catch(Exception e){throw new ApiClient.Failure("CONNECTION_FAILED");}
        finally{if(connection!=null)connection.disconnect();}
    }
    private static HttpURLConnection connect(String base,String path)throws IOException{
        var c=(HttpURLConnection)URI.create(ApiClient.validateBase(base)+path).toURL().openConnection();
        c.setConnectTimeout(8000);c.setReadTimeout(30000);c.setInstanceFollowRedirects(false);c.setRequestProperty("Accept","application/json");return c;
    }
    private static JSONObject response(HttpURLConnection c,String id)throws Exception{
        int status=c.getResponseCode();InputStream stream=status>=400?c.getErrorStream():c.getInputStream();
        if(stream==null)throw new ApiClient.Failure("OCR_UNAVAILABLE");
        JSONObject data;
        try(InputStream in=stream;ByteArrayOutputStream out=new ByteArrayOutputStream()){
            byte[] b=new byte[4096];int n;while((n=in.read(b))!=-1){if(out.size()+n>65536)throw new ApiClient.Failure("OCR_UNAVAILABLE");out.write(b,0,n);}
            data=new JSONObject(out.toString(StandardCharsets.UTF_8.name()));
        }
        if(status!=200&&status!=202)throw new ApiClient.Failure(data.optString("code","OCR_UNAVAILABLE"));
        if(!id.equals(data.optString("id"))||!java.util.Set.of("QUEUED","PROCESSING","COMPLETED","RETAKE","FAILED").contains(data.optString("status")))throw new ApiClient.Failure("OCR_UNAVAILABLE");
        return data;
    }
}
