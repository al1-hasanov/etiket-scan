package az.etiket.backend;

import java.util.Map;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController
final class AnalysisController {
    private final LabelAnalysis service;
    AnalysisController(LabelAnalysis service){this.service=service;}
    @PostMapping(path="/api/v1/analysis-jobs",consumes=MediaType.MULTIPART_FORM_DATA_VALUE)
    ResponseEntity<LabelAnalysis.Result> analyze(@RequestPart("metadata") LabelAnalysis.Request input,@RequestPart("photo") MultipartFile photo){
        return ResponseEntity.status(202).cacheControl(CacheControl.noStore()).body(service.create(input,photo));
    }
    @GetMapping("/api/v1/analysis-jobs/{id}")
    ResponseEntity<LabelAnalysis.Result> get(@PathVariable String id,@RequestHeader(value="Authorization",defaultValue="") String authorization){
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.get(id,authorization.startsWith("Bearer ")?authorization.substring(7):""));
    }
}
