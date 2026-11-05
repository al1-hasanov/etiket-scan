package az.etiket.backend;

import static az.etiket.backend.SubmissionModels.*;
import az.etiket.core.Product;
import java.security.Principal;
import java.util.*;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.*;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.*;

@RestController
final class SubmissionController {
    private final SubmissionService service;
    SubmissionController(SubmissionService service) { this.service = service; }

    @PostMapping(path="/api/v1/submissions", consumes=MediaType.MULTIPART_FORM_DATA_VALUE)
    ResponseEntity<Receipt> submit(@RequestPart("metadata") Draft draft, @RequestPart("front") MultipartFile front,
            @RequestPart("barcode") MultipartFile barcode, @RequestPart("ingredients") MultipartFile ingredients,
            @RequestPart(value="nutrition", required=false) MultipartFile nutrition) {
        Map<String,MultipartFile> files=new LinkedHashMap<>(Map.of("front",front,"barcode",barcode,"ingredients",ingredients));
        if(nutrition!=null&&!nutrition.isEmpty())files.put("nutrition",nutrition);
        return ResponseEntity.status(201).body(service.create(draft, files));
    }
    @GetMapping("/api/v1/submissions/{id}/status") Receipt status(@PathVariable String id) { return service.status(id); }
    @GetMapping("/api/v1/review/csrf") Map<String, String> csrf(CsrfToken token) {
        return Map.of("token", token.getToken(), "headerName", token.getHeaderName());
    }
    @GetMapping("/api/v1/review/submissions") Map<String, Object> queue(
            @RequestParam(defaultValue="PENDING") String status, @RequestParam(defaultValue="0") int page) { return service.queue(status, page); }
    @GetMapping("/api/v1/review/submissions/{id}") Detail detail(@PathVariable String id) { return service.detail(id); }
    @PostMapping("/api/v1/review/submissions/{id}/approve") Product approve(
            @PathVariable String id, @RequestBody Approval input, Principal reviewer) { return service.approve(id, input, reviewer.getName()); }
    @PostMapping("/api/v1/review/submissions/{id}/reject") Receipt reject(
            @PathVariable String id, @RequestBody Rejection input, Principal reviewer) { return service.reject(id, input, reviewer.getName()); }
    @GetMapping("/api/v1/review/submissions/{id}/photos/{role}") ResponseEntity<byte[]> privatePhoto(
            @PathVariable String id, @PathVariable String role) { return image(service.photo(id, role, true)); }
    @GetMapping("/api/v1/labels/{id}") PublicLabel label(@PathVariable String id) { return service.publicLabel(id); }
    @GetMapping("/api/v1/labels/{id}/photos/{role}") ResponseEntity<byte[]> publicPhoto(
            @PathVariable String id, @PathVariable String role) { return image(service.photo(id, role, false)); }
    @GetMapping(path={"/review", "/review/"}, produces=MediaType.TEXT_HTML_VALUE) ClassPathResource review() {
        return new ClassPathResource("static/review/index.html");
    }
    @GetMapping(path="/sources/labels/{id}", produces=MediaType.TEXT_HTML_VALUE) ClassPathResource source(@PathVariable String id) {
        service.publicLabel(id);
        return new ClassPathResource("static/assets/label-source.html");
    }
    private ResponseEntity<byte[]> image(byte[] bytes) {
        return ResponseEntity.ok().contentType(MediaType.IMAGE_JPEG).cacheControl(CacheControl.noStore())
            .header("X-Content-Type-Options", "nosniff").body(bytes);
    }
}

@RestControllerAdvice
class SubmissionErrors {
    @ExceptionHandler(SubmissionException.class) ResponseEntity<Map<String, String>> domain(SubmissionException error) {
        var response = ResponseEntity.status(error.status);
        if (error.status == 429) response.header("Retry-After", "60");
        return response.body(Map.of("code", error.code));
    }
    @ExceptionHandler(MaxUploadSizeExceededException.class) ResponseEntity<Map<String, String>> oversized() {
        return ResponseEntity.status(413).body(Map.of("code", "PHOTO_TOO_LARGE"));
    }
}
