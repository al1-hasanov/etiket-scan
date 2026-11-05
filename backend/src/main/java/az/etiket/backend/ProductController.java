package az.etiket.backend;

import az.etiket.core.*;
import java.util.*;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1")
class ProductController {
    private final ProductService service;
    private final CatalogueStore catalogue;
    ProductController(ProductService service, CatalogueStore catalogue) { this.service = service; this.catalogue = catalogue; }
    @GetMapping("/health") Map<String, String> health() { return Map.of("status", "UP", "version", "0.5.0"); }
    @GetMapping("/catalogue/summary") Map<String, Object> summary() { return catalogue.summary(); }
    @GetMapping("/catalogue/products") Map<String, Object> products(
            @RequestParam(defaultValue="0") int page, @RequestParam(defaultValue="20") int size) {
        return catalogue.page(page, size);
    }
    @GetMapping("/products/demo") List<Product> demo() { return DemoCatalog.products(); }
    @GetMapping("/products/{barcode}") Product product(@PathVariable String barcode) { return service.lookup(barcode); }
}

@RestControllerAdvice
class Errors {
    @ExceptionHandler(InvalidCataloguePageException.class)
    ResponseEntity<Map<String, String>> invalidPage() {
        return ResponseEntity.badRequest().body(Map.of("code", "CATALOGUE_PAGE_INVALID"));
    }
    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<Map<String, String>> invalid(IllegalArgumentException e) {
        return ResponseEntity.badRequest().body(Map.of("code", "BARCODE_INVALID"));
    }
    @ExceptionHandler(LookupException.class)
    ResponseEntity<Map<String, String>> lookup(LookupException e) {
        var response = ResponseEntity.status(e.status);
        if (e.status == 429) response.header("Retry-After", "5");
        return response.body(Map.of("code", e.code));
    }
}
