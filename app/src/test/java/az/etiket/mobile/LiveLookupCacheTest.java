package az.etiket.mobile;

import android.content.Context;
import android.content.SharedPreferences;
import az.etiket.core.Product;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35)
public class LiveLookupCacheTest {
    @Test public void onlineDiscoveryCanBeOpenedOfflineOnlyOnTheSameBackend() {
        Context app = RuntimeEnvironment.getApplication();
        SharedPreferences prefs = app.getSharedPreferences("live-cache-test", Context.MODE_PRIVATE);
        prefs.edit().clear().commit();
        ProductStore store = new ProductStore(prefs);
        Product original = StarterCatalogue.load(app).get(0);
        store.cacheLiveLookup("https://dev.example.test", original);
        assertEquals(original, store.cachedLiveLookup("https://dev.example.test", original.barcode()).orElseThrow());
        assertTrue(store.cachedLiveLookup("https://prod.example.test", original.barcode()).isEmpty());
        store.clear();
        assertTrue(store.cachedLiveLookup("https://dev.example.test", original.barcode()).isEmpty());
    }
    @Test public void oldEntriesAreNotUsedAsFallback() throws Exception {
        Context app = RuntimeEnvironment.getApplication();
        SharedPreferences prefs = app.getSharedPreferences("live-cache-expiration-test", Context.MODE_PRIVATE);
        prefs.edit().clear().commit();
        ProductStore store = new ProductStore(prefs);
        Product original = StarterCatalogue.load(app).get(0);
        store.cacheLiveLookup("https://dev.example.test", original);
        org.json.JSONArray data = new org.json.JSONArray(prefs.getString("liveLookups", "[]"));
        data.getJSONObject(0).put("cachedAt", System.currentTimeMillis() - 8L * 86_400_000L);
        prefs.edit().putString("liveLookups", data.toString()).commit();
        assertTrue(store.cachedLiveLookup("https://dev.example.test", original.barcode()).isEmpty());
    }
}
