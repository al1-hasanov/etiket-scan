package az.etiket.mobile;

import az.etiket.core.Product;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import org.json.JSONObject;

final class ApiClient {
    static final class Failure extends Exception {
        final String code;
        Failure(String code) { super(code); this.code = code; }
    }
    static String validateBase(String input) {
        String base = input.trim().replaceAll("/+$", "");
        try {
            URI uri = URI.create(base);
            String scheme = uri.getScheme(), host = uri.getHost();
            if (host == null || !("http".equals(scheme) || "https".equals(scheme)) || uri.getRawUserInfo() != null
                || uri.getRawQuery() != null || uri.getRawFragment() != null || !uri.getPath().isEmpty()
                || uri.getPort() > 65535) throw new IllegalArgumentException();
            if ("http".equals(scheme) && !localHost(host)) throw new IllegalArgumentException();
            return base;
        } catch (RuntimeException e) { throw new IllegalArgumentException("BASE_URL_INVALID"); }
    }
    private static boolean localHost(String host) {
        if (host.equals("localhost") || host.equals("[::1]")) return true;
        if (!host.matches("[0-9]+\\.[0-9]+\\.[0-9]+\\.[0-9]+")) return false;
        String[] parts = host.split("\\."); int[] ip = new int[4];
        for (int i = 0; i < 4; i++) { ip[i] = Integer.parseInt(parts[i]); if (ip[i] > 255) return false; }
        return ip[0] == 127 || ip[0] == 10 || (ip[0] == 192 && ip[1] == 168)
            || (ip[0] == 172 && ip[1] >= 16 && ip[1] <= 31);
    }
    static Product lookup(String base, String barcode) throws Failure {
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) URI.create(validateBase(base) + "/api/v1/products/" + barcode).toURL().openConnection();
            connection.setConnectTimeout(5000); connection.setReadTimeout(12000);
            connection.setInstanceFollowRedirects(false);
            connection.setRequestProperty("Accept", "application/json");
            int status = connection.getResponseCode();
            if (status == 404) throw new Failure("PRODUCT_NOT_FOUND");
            if (status == 429) throw new Failure("LOOKUP_RATE_LIMITED");
            if (status == 400) throw new Failure("BARCODE_INVALID");
            if (status != 200) throw new Failure("SOURCE_UNAVAILABLE");
            try (InputStream in = connection.getInputStream(); ByteArrayOutputStream bytes = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[4096]; int read;
                while ((read = in.read(buffer)) != -1) {
                    if (bytes.size() + read > 1_000_000) throw new Failure("SOURCE_UNAVAILABLE");
                    bytes.write(buffer, 0, read);
                }
                Product product = ProductJson.read(new JSONObject(bytes.toString(StandardCharsets.UTF_8.name())));
                if (!product.barcode().equals(barcode)) throw new Failure("SOURCE_UNAVAILABLE");
                return product;
            }
        } catch (Failure e) { throw e;
        } catch (org.json.JSONException e) { throw new Failure("SOURCE_UNAVAILABLE");
        } catch (Exception e) { throw new Failure("CONNECTION_FAILED");
        } finally { if (connection != null) connection.disconnect(); }
    }
}
