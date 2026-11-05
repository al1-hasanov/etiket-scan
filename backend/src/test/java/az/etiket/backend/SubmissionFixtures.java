package az.etiket.backend;

import static az.etiket.backend.SubmissionModels.*;
import java.awt.image.BufferedImage;
import java.io.*;
import java.util.*;
import javax.imageio.ImageIO;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.multipart.MultipartFile;

final class SubmissionFixtures {
    static byte[] photo() {
        try {
            BufferedImage image = new BufferedImage(24, 16, BufferedImage.TYPE_INT_RGB);
            for (int x = 0; x < 24; x++) for (int y = 0; y < 16; y++) image.setRGB(x, y, (x * 10 << 16) | y * 14);
            ByteArrayOutputStream out = new ByteArrayOutputStream(); ImageIO.write(image, "png", out); return out.toByteArray();
        } catch (IOException e) { throw new UncheckedIOException(e); }
    }
    static Draft draft(String code) {
        return new Draft(UUID.randomUUID().toString(), code, "Synthetic test package", "Test fixture", "65 g",
            "", "az", "", true);
    }
    static Map<String, MultipartFile> photos() {
        Map<String, MultipartFile> result = new LinkedHashMap<>();
        for (String role : SubmissionService.ROLES) result.put(role, new MockMultipartFile(role, role + ".png", "image/png", photo()));
        return result;
    }
    static Approval approval(int version, String previous) {
        return new Approval(version, "Synthetic reviewed test package", "Test fixture", "65 g", "buğda unu, su, duz",
            "az", "Süd izləri ola bilər.", previous, true);
    }
}
