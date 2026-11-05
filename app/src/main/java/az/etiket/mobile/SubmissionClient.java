package az.etiket.mobile;

import android.content.SharedPreferences;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.json.*;

/** Public submission API only. Reviewer credentials never belong in the Android app. */
final class SubmissionClient {
    static final String[] ROLES = {"front", "barcode", "ingredients"};
    static final String[] PHOTO_ROLES = {"front", "barcode", "ingredients", "nutrition"};
    private SubmissionClient() { }

    static JSONObject submit(String base, JSONObject metadata, Map<String, File> photos) throws ApiClient.Failure {
        HttpURLConnection connection = null;
        try {
            String boundary = "etiket-" + UUID.randomUUID();
            List<byte[]> headers = new ArrayList<>();
            List<String> roles=new ArrayList<>(Arrays.asList(ROLES));if(photos.containsKey("nutrition"))roles.add("nutrition");
            byte[] first = ("--" + boundary + "\r\nContent-Disposition: form-data; name=\"metadata\"\r\nContent-Type: application/json; charset=utf-8\r\n\r\n" + metadata + "\r\n").getBytes(StandardCharsets.UTF_8);
            long length = first.length;
            for (String role : roles) {
                File file = photos.get(role);
                if (file == null || !file.isFile()) throw new ApiClient.Failure("PHOTOS_REQUIRED");
                if (file.length() > 2 * 1024 * 1024) throw new ApiClient.Failure("PHOTO_TOO_LARGE");
                byte[] header = ("--" + boundary + "\r\nContent-Disposition: form-data; name=\"" + role + "\"; filename=\"" + role + ".jpg\"\r\nContent-Type: image/jpeg\r\n\r\n").getBytes(StandardCharsets.UTF_8);
                headers.add(header); length += header.length + file.length() + 2;
            }
            byte[] end = ("--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8); length += end.length;
            connection = connect(base, "/api/v1/submissions"); connection.setRequestMethod("POST"); connection.setDoOutput(true);
            connection.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);
            connection.setFixedLengthStreamingMode(length);
            try (OutputStream out = connection.getOutputStream()) {
                out.write(first);
                for (int i = 0; i < roles.size(); i++) {
                    out.write(headers.get(i));
                    try (InputStream in = new FileInputStream(photos.get(roles.get(i)))) {
                        byte[] buffer = new byte[8192]; int read;
                        while ((read = in.read(buffer)) != -1) out.write(buffer, 0, read);
                    }
                    out.write(new byte[]{13, 10});
                }
                out.write(end);
            }
            JSONObject receipt = response(connection);
            if (!metadata.optString("clientRequestId").equals(receipt.optString("id"))
                    || metadata.has("barcode") && !metadata.optString("barcode").equals(receipt.optString("barcode")))
                throw new ApiClient.Failure("SUBMISSION_UNAVAILABLE");
            return receipt;
        } catch (ApiClient.Failure e) { throw e;
        } catch (Exception e) { throw new ApiClient.Failure("CONNECTION_FAILED");
        } finally { if (connection != null) connection.disconnect(); }
    }

    static JSONObject status(String base, String id) throws ApiClient.Failure {
        HttpURLConnection connection = null;
        try {
            if (!UUID.fromString(id).toString().equals(id)) throw new IllegalArgumentException();
            connection = connect(base, "/api/v1/submissions/" + id + "/status");
            JSONObject receipt = response(connection);
            if (!id.equals(receipt.optString("id"))) throw new ApiClient.Failure("SUBMISSION_UNAVAILABLE");
            return receipt;
        } catch (ApiClient.Failure e) { throw e;
        } catch (Exception e) { throw new ApiClient.Failure("CONNECTION_FAILED");
        } finally { if (connection != null) connection.disconnect(); }
    }
    private static HttpURLConnection connect(String base, String path) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) URI.create(ApiClient.validateBase(base) + path).toURL().openConnection();
        connection.setConnectTimeout(8000); connection.setReadTimeout(30000); connection.setInstanceFollowRedirects(false);
        connection.setRequestProperty("Accept", "application/json"); return connection;
    }
    private static JSONObject response(HttpURLConnection connection) throws Exception {
        int status = connection.getResponseCode();
        InputStream stream = status >= 400 ? connection.getErrorStream() : connection.getInputStream();
        if (stream == null) throw new ApiClient.Failure("SUBMISSION_UNAVAILABLE");
        JSONObject data;
        try (InputStream in = stream; ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096]; int n;
            while ((n = in.read(buffer)) != -1) { if (out.size() + n > 65536) throw new ApiClient.Failure("SUBMISSION_UNAVAILABLE"); out.write(buffer, 0, n); }
            data = new JSONObject(out.toString(StandardCharsets.UTF_8.name()));
        }
        if (status != 200 && status != 201) throw new ApiClient.Failure(data.optString("code", "SUBMISSION_UNAVAILABLE"));
        if (!Set.of("PENDING", "APPROVED", "REJECTED").contains(data.optString("status")) || data.optString("id").isEmpty())
            throw new ApiClient.Failure("SUBMISSION_UNAVAILABLE");
        return data;
    }
    static synchronized void remember(SharedPreferences prefs, String base, JSONObject receipt, String name) {
        try {
            JSONObject copy = new JSONObject(receipt.toString()).put("base", base).put("name", name);
            List<JSONObject> merged = new ArrayList<>(); merged.add(copy);
            for (JSONObject old : receipts(prefs)) if (!old.optString("id").equals(copy.optString("id"))) merged.add(old);
            merged.sort(Comparator.comparingLong((JSONObject row) -> {
                try { return java.time.Instant.parse(row.optString("submittedAt")).toEpochMilli(); }
                catch (RuntimeException e) { return 0L; }
            }).reversed());
            JSONArray result = new JSONArray(); for (JSONObject row : merged) if (result.length() < 50) result.put(row);
            prefs.edit().putString("submissions", result.toString()).apply();
        } catch (JSONException e) { throw new IllegalStateException(e); }
    }
    static List<JSONObject> receipts(SharedPreferences prefs) {
        List<JSONObject> result = new ArrayList<>();
        try {
            JSONArray rows = new JSONArray(prefs.getString("submissions", "[]"));
            for (int i = 0; i < rows.length(); i++) result.add(rows.getJSONObject(i));
        } catch (JSONException ignored) { }
        return result;
    }
}
