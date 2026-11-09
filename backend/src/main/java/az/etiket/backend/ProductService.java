package az.etiket.backend;

import az.etiket.core.Barcode;
import az.etiket.core.DemoCatalog;
import az.etiket.core.Product;
import org.springframework.stereotype.Service;

@Service
final class ProductService {
    private final CatalogueStore catalogue;
    private final NutritionFeeds feeds;
    private final OpenFoodFactsCatalogue external;

    ProductService(CatalogueStore catalogue, NutritionFeeds feeds, OpenFoodFactsCatalogue external) {
        this.catalogue = catalogue;
        this.feeds = feeds;
        this.external = external;
    }

    Product lookup(String input) {
        String barcode = Barcode.normalize(input);
        for (Product demo : DemoCatalog.products()) if (demo.barcode().equals(barcode)) return demo;
        var local = catalogue.find(barcode);
        if (local.isEmpty()) return external.lookup(barcode).product();

        Product product = local.get();
        if (!product.nutrition().values().isEmpty()) return product;
        Product enriched = feeds.addSavedNutrition(product);
        if (enriched.nutritionSource() != null || !external.enabled()) return enriched;
        try {
            var source = external.lookup(barcode);
            var localPack = PackageSize.product(product);
            Product candidate = source.product();
            if (source.azerbaijanMarket() && localPack.isPresent() && localPack.equals(PackageSize.product(candidate)))
                return product.withNutrition(candidate.nutrition(), candidate.nutritionSource());
        } catch (LookupException ignored) {
            // The catalogue result is still useful during a provider outage or a missing match.
        }
        return product;
    }
}
