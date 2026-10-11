package com.example.loginapp.core;

import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Iterator;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;

/**
 * What an uploaded file really is, judged by its first bytes and never by its
 * name or the type the browser claims. Only PDF, JPEG and PNG are accepted.
 * Photos are drawn again into a fresh image, which leaves behind everything
 * but the pixels: the camera's location, time and serial number.
 */
public final class FileSniffer {

    public static final int MAX_BYTES = 10 * 1024 * 1024;

    /** Photos larger than this on their longer side are scaled down; a document photo needs no more. */
    static final int MAX_SIDE = 2400;

    public record Cleaned(String contentType, byte[] bytes, String fileName) {}

    private FileSniffer() {}

    /** The type the bytes say, or null when they are none of PDF, JPEG or PNG. */
    public static String sniff(byte[] bytes) {
        if (bytes == null || bytes.length < 8) {
            return null;
        }
        if (bytes[0] == '%' && bytes[1] == 'P' && bytes[2] == 'D' && bytes[3] == 'F' && bytes[4] == '-') {
            return "application/pdf";
        }
        if ((bytes[0] & 0xFF) == 0xFF && (bytes[1] & 0xFF) == 0xD8 && (bytes[2] & 0xFF) == 0xFF) {
            return "image/jpeg";
        }
        int[] png = {0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A};
        for (int i = 0; i < png.length; i++) {
            if ((bytes[i] & 0xFF) != png[i]) {
                return null;
            }
        }
        return "image/png";
    }

    /**
     * Checks an upload and returns what to store: its real type, the bytes (a photo redrawn without
     * its metadata) and a safe file name with the right extension.
     */
    public static Cleaned clean(byte[] bytes, String fileName) {
        if (bytes == null || bytes.length == 0) {
            throw new ApiException(400, "document.empty", "The file is empty");
        }
        if (bytes.length > MAX_BYTES) {
            throw new ApiException(413, "document.too_large", "The file is larger than 10 MB. Send a smaller photo or a PDF");
        }
        String type = sniff(bytes);
        if (type == null) {
            throw new ApiException(415, "document.type", "Only PDF, JPEG and PNG files can be uploaded");
        }
        byte[] stored = type.equals("application/pdf") ? bytes : redraw(bytes, type);
        return new Cleaned(type, stored, safeName(fileName, type));
    }

    /** Letters, digits, spaces, dots, dashes and underscores only, at most 100 characters, with the extension of the real type. */
    public static String safeName(String fileName, String contentType) {
        String name = fileName == null ? "" : fileName;
        name = name.replace('\\', '/');
        name = name.substring(name.lastIndexOf('/') + 1);
        int dot = name.lastIndexOf('.');
        String base = dot > 0 ? name.substring(0, dot) : name;
        base = base.replaceAll("[^A-Za-z0-9 ._-]", "_").replaceAll("_+", "_").strip();
        base = base.replaceAll("^[.\\s]+", "");
        if (base.isEmpty()) {
            base = "document";
        }
        if (base.length() > 95) {
            base = base.substring(0, 95).strip();
        }
        String ext = switch (contentType) {
            case "application/pdf" -> ".pdf";
            case "image/png" -> ".png";
            default -> ".jpg";
        };
        return base + ext;
    }

    private static byte[] redraw(byte[] bytes, String type) {
        BufferedImage source;
        try {
            source = ImageIO.read(new ByteArrayInputStream(bytes));
        } catch (IOException | RuntimeException e) {
            source = null;
        }
        if (source == null) {
            throw new ApiException(415, "document.unreadable", "This image could not be read. Take the photo again, or send a PDF");
        }
        int w = source.getWidth();
        int h = source.getHeight();
        double scale = Math.min(1.0, (double) MAX_SIDE / Math.max(w, h));
        int nw = Math.max(1, (int) Math.round(w * scale));
        int nh = Math.max(1, (int) Math.round(h * scale));
        boolean png = type.equals("image/png");
        BufferedImage out = new BufferedImage(nw, nh, png ? BufferedImage.TYPE_INT_ARGB : BufferedImage.TYPE_INT_RGB);
        Graphics2D g = out.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            if (!png) {
                g.setColor(java.awt.Color.WHITE);
                g.fillRect(0, 0, nw, nh);
            }
            g.drawImage(source, 0, 0, nw, nh, null);
        } finally {
            g.dispose();
        }
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        try {
            if (png) {
                ImageIO.write(out, "png", buffer);
            } else {
                Iterator<ImageWriter> writers = ImageIO.getImageWritersByFormatName("jpeg");
                ImageWriter writer = writers.next();
                ImageWriteParam param = writer.getDefaultWriteParam();
                param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
                param.setCompressionQuality(0.85f);
                try (ImageOutputStream ios = ImageIO.createImageOutputStream(buffer)) {
                    writer.setOutput(ios);
                    writer.write(null, new IIOImage(out, null, null), param);
                } finally {
                    writer.dispose();
                }
            }
        } catch (IOException e) {
            throw new ApiException(415, "document.unreadable", "This image could not be read. Take the photo again, or send a PDF");
        }
        return buffer.toByteArray();
    }
}
