package az.etiket.backend;

import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.security.Principal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
final class CatalogueImportController {
    private final CatalogueCsvImport importer;

    CatalogueImportController(CatalogueCsvImport importer) { this.importer = importer; }

    @PostMapping(path = "/api/v1/review/catalogue-import", consumes = "text/csv")
    CatalogueCsvImport.Result importCatalogue(HttpServletRequest request,
            @RequestParam(defaultValue = "true") boolean dryRun,
            @RequestParam(defaultValue = "false") boolean confirmed,
            @RequestParam(defaultValue = "") String digest, Principal reviewer) throws IOException {
        byte[] csv = request.getInputStream().readNBytes(CatalogueCsvImport.MAX_BYTES + 1);
        return importer.importCsv(csv, dryRun, confirmed, digest, reviewer.getName());
    }
}
