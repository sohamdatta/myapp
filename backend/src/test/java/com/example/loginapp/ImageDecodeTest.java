package com.example.loginapp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.Test;

import com.example.loginapp.core.ApiException;
import com.example.loginapp.core.FileSniffer;

/**
 * A small file can declare a huge picture. These tests send such files (a few kilobytes each) and check they are refused
 * quickly, without decoding them, and that ordinary photos still go through, also when many arrive at once.
 */
class ImageDecodeTest {

    private static byte[] png(int width, int height) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(width, height, BufferedImage.TYPE_BYTE_BINARY), "png", out);
        return out.toByteArray();
    }

    @Test
    void aFileDeclaringTooManyPixelsIsRefusedQuickly() throws Exception {
        byte[] bomb = png(12_000, 12_000);   // 144 million pixels in a file of a few kilobytes
        assertTrue(bomb.length < 100_000, "the test file is small");
        long start = System.nanoTime();
        ApiException e = assertThrows(ApiException.class, () -> FileSniffer.clean(bomb, "photo.png"));
        assertEquals(413, e.status());
        assertTrue(Duration.ofNanos(System.nanoTime() - start).toMillis() < 1000, "refused without decoding it");
    }

    @Test
    void anOrdinaryLargePhotoStillGoesThroughAndIsScaledDown() throws Exception {
        FileSniffer.Cleaned cleaned = FileSniffer.clean(png(6000, 4000), "photo.png");
        BufferedImage out = ImageIO.read(new java.io.ByteArrayInputStream(cleaned.bytes()));
        assertTrue(Math.max(out.getWidth(), out.getHeight()) <= 2400, "scaled to at most 2400 pixels");
    }

    @Test
    void manyLargePhotosAtOnceAllFinish() throws Exception {
        byte[] photo = png(6000, 4000);
        ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor();
        try {
            List<Future<Integer>> results = new ArrayList<>();
            for (int i = 0; i < 20; i++) {
                results.add(pool.submit(() -> FileSniffer.clean(photo, "photo.png").bytes().length));
            }
            for (Future<Integer> f : results) {
                assertTrue(f.get() > 0);
            }
        } finally {
            pool.shutdown();
        }
    }

    @Test
    void notAnImageIsStillRejectedAsUnreadable() {
        assertThrows(ApiException.class, () -> FileSniffer.clean(new byte[] {1, 2, 3, 4}, "photo.png"));
    }
}
