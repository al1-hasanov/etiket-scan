package az.etiket.backend;

import java.io.*;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import org.springframework.mock.web.MockMultipartFile;

class LabelPhotosTest {
    @Test void reencodingDiscardsSourceMetadataAndStoresJpeg() throws Exception {
        byte[] marker="PRIVATE_SOURCE_METADATA_MUST_NOT_SURVIVE".getBytes(StandardCharsets.UTF_8);
        ByteArrayOutputStream source=new ByteArrayOutputStream();source.write(SubmissionFixtures.photo());source.write(marker);
        var clean=new LabelPhotos().sanitize(new MockMultipartFile("photo","source.png","image/png",source.toByteArray()));
        assertEquals(0xff,clean.bytes()[0]&255);assertEquals(0xd8,clean.bytes()[1]&255);
        assertEquals(24,clean.width());assertEquals(16,clean.height());
        assertFalse(new String(clean.bytes(),StandardCharsets.ISO_8859_1).contains("PRIVATE_SOURCE_METADATA_MUST_NOT_SURVIVE"));
        assertEquals(LabelPhotos.sha256(clean.bytes()),clean.sha256());
    }
    @Test void unboundedDimensionsAreRejectedBeforePixelDecoding() {
        byte[] source=SubmissionFixtures.photo();java.nio.ByteBuffer.wrap(source,16,4).putInt(100000);
        java.util.zip.CRC32 crc=new java.util.zip.CRC32();crc.update(source,12,17);java.nio.ByteBuffer.wrap(source,29,4).putInt((int)crc.getValue());
        var error=assertThrows(SubmissionException.class,()->new LabelPhotos().sanitize(new MockMultipartFile("photo","large.png","image/png",source)));
        assertEquals(413,error.status);
    }
    @Test void largeCameraImagesAreSubsampledAndStoredWithinTheDisplayBound() throws Exception {
        var source=new java.awt.image.BufferedImage(4000,3000,java.awt.image.BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream png=new ByteArrayOutputStream();javax.imageio.ImageIO.write(source,"png",png);source.flush();
        var clean=new LabelPhotos().sanitize(new MockMultipartFile("photo","camera.png","image/png",png.toByteArray()));
        assertTrue(clean.width()<=2048);assertTrue(clean.height()<=2048);assertTrue(clean.bytes().length<=LabelPhotos.MAX_BYTES);
    }
}
