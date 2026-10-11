package com.example.loginapp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.Test;

import com.example.loginapp.core.ApiException;
import com.example.loginapp.core.FileSniffer;

/** What an upload really is, by its bytes; photos stored without their metadata. */
class FileSnifferTest {

    static final byte[] PDF = "%PDF-1.4\n%test\n%%EOF\n".getBytes(StandardCharsets.US_ASCII);

    @Test
    void theTypeComesFromTheBytesNotTheName() throws Exception {
        assertEquals("application/pdf", FileSniffer.sniff(PDF));
        assertEquals("image/png", FileSniffer.sniff(image("png")));
        assertEquals("image/jpeg", FileSniffer.sniff(image("jpg")));
        assertNull(FileSniffer.sniff("<html><script>alert(1)</script></html>".getBytes(StandardCharsets.UTF_8)));
        assertNull(FileSniffer.sniff(new byte[] {1, 2}));

        // A PDF named .png is a PDF, and is stored with the right extension.
        FileSniffer.Cleaned pdf = FileSniffer.clean(PDF, "photo.png");
        assertEquals("application/pdf", pdf.contentType());
        assertEquals("photo.pdf", pdf.fileName());
        // An HTML page named .pdf is refused.
        ApiException e = assertThrows(ApiException.class,
                () -> FileSniffer.clean("<html></html>".getBytes(StandardCharsets.UTF_8), "pan.pdf"));
        assertEquals("document.type", e.code());
        assertEquals(415, e.status());
    }

    @Test
    void tenMegabytesIsTheLimit() {
        byte[] big = new byte[FileSniffer.MAX_BYTES + 1];
        System.arraycopy(PDF, 0, big, 0, PDF.length);
        ApiException e = assertThrows(ApiException.class, () -> FileSniffer.clean(big, "big.pdf"));
        assertEquals("document.too_large", e.code());
        assertEquals(413, e.status());
        byte[] fits = new byte[FileSniffer.MAX_BYTES];
        System.arraycopy(PDF, 0, fits, 0, PDF.length);
        assertEquals(FileSniffer.MAX_BYTES, FileSniffer.clean(fits, "fits.pdf").bytes().length);
        assertEquals("document.empty", assertThrows(ApiException.class, () -> FileSniffer.clean(new byte[0], "x.pdf")).code());
    }

    @Test
    void aPhotoIsStoredWithoutItsMetadataAndBigOnesAreScaledDown() throws Exception {
        // A camera photo carries an Exif block with the place it was taken.
        byte[] plain = image("jpg");
        byte[] exif = "Exif\0\0GPS 22.5726N 88.3639E".getBytes(StandardCharsets.ISO_8859_1);
        ByteArrayOutputStream withExif = new ByteArrayOutputStream();
        withExif.write(plain, 0, 2);   // SOI
        withExif.write(0xFF);
        withExif.write(0xE1);
        withExif.write((exif.length + 2) >> 8);
        withExif.write((exif.length + 2) & 0xFF);
        withExif.write(exif);
        withExif.write(plain, 2, plain.length - 2);
        byte[] photo = withExif.toByteArray();
        assertTrue(new String(photo, StandardCharsets.ISO_8859_1).contains("GPS 22.5726N"));

        FileSniffer.Cleaned cleaned = FileSniffer.clean(photo, "IMG_2041.JPG");
        assertEquals("image/jpeg", cleaned.contentType());
        assertEquals("IMG_2041.jpg", cleaned.fileName());
        assertFalse(new String(cleaned.bytes(), StandardCharsets.ISO_8859_1).contains("GPS"), "location kept");
        assertEquals(40, ImageIO.read(new ByteArrayInputStream(cleaned.bytes())).getWidth());

        BufferedImage large = new BufferedImage(4000, 3000, BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(large, "png", out);
        BufferedImage stored = ImageIO.read(new ByteArrayInputStream(FileSniffer.clean(out.toByteArray(), "big.png").bytes()));
        assertEquals(2400, stored.getWidth());
        assertEquals(1800, stored.getHeight());
    }

    @Test
    void namesAreMadeSafe() {
        assertEquals("passwd.pdf", FileSniffer.safeName("../../etc/passwd", "application/pdf"));
        assertEquals("pan card.jpg", FileSniffer.safeName("C:\\fakepath\\pan card.jpeg", "image/jpeg"));
        assertEquals("document.png", FileSniffer.safeName("", "image/png"));
        assertEquals("x_y_z_.pdf", FileSniffer.safeName("x\"y<z>.pdf", "application/pdf"));
        assertEquals("hidden.pdf", FileSniffer.safeName(".hidden", "application/pdf"));
    }

    private static byte[] image(String format) throws Exception {
        BufferedImage img = new BufferedImage(40, 30, BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(img, format.equals("jpg") ? "jpeg" : format, out);
        return out.toByteArray();
    }
}
