package com.example.loginapp.core;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.Iterator;
import java.util.concurrent.Semaphore;

import javax.imageio.ImageIO;
import javax.imageio.ImageReadParam;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;

/**
 * Decodes an uploaded image without trusting the size it declares. A small file can declare a huge picture
 * (a "decompression bomb"): decoding it whole would need gigabytes of heap. This reads the declared size first,
 * refuses anything over {@link #MAX_PIXELS}, and decodes a large picture at reduced resolution, since the caller
 * scales it down anyway.
 */
final class ImageDecode {

    /** Above this many pixels the image is refused (a 48 megapixel phone photo is well inside it). */
    static final long MAX_PIXELS = 100_000_000L;
    /** Above this many pixels the image is decoded with subsampling, so the decoded raster stays near this size. */
    private static final long FULL_DECODE_PIXELS = 16_000_000L;

    /**
     * How many images are decoded at once. Each decode can hold tens of megabytes, and every request runs on its own
     * virtual thread, so without a limit a burst of uploads decodes all at the same time. The rest wait their turn.
     */
    private static final Semaphore SLOTS = new Semaphore(3);

    private ImageDecode() {}

    /** Thrown when the image is readable but larger than {@link #MAX_PIXELS}. */
    static final class TooLarge extends RuntimeException {
        TooLarge() {
            super("image too large", null, false, false);
        }
    }

    /**
     * The image, or null when it cannot be read.
     *
     * @throws TooLarge when it declares more than {@link #MAX_PIXELS} pixels
     */
    static BufferedImage read(byte[] bytes) {
        SLOTS.acquireUninterruptibly();
        try {
            return decode(bytes);
        } finally {
            SLOTS.release();
        }
    }

    private static BufferedImage decode(byte[] bytes) {
        try (ImageInputStream in = ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))) {
            if (in == null) {
                return null;
            }
            Iterator<ImageReader> readers = ImageIO.getImageReaders(in);
            if (!readers.hasNext()) {
                return null;
            }
            ImageReader reader = readers.next();
            try {
                reader.setInput(in, true, true);
                long w = reader.getWidth(0);
                long h = reader.getHeight(0);
                long pixels = w * h;
                if (pixels > MAX_PIXELS) {
                    throw new TooLarge();
                }
                ImageReadParam param = reader.getDefaultReadParam();
                if (pixels > FULL_DECODE_PIXELS) {
                    int step = (int) Math.ceil(Math.sqrt((double) pixels / FULL_DECODE_PIXELS));
                    param.setSourceSubsampling(step, step, 0, 0);
                }
                return reader.read(0, param);
            } finally {
                reader.dispose();
            }
        } catch (IOException | RuntimeException e) {
            if (e instanceof TooLarge large) {
                throw large;
            }
            return null;
        }
    }
}
