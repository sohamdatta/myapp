package com.example.loginapp.core;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.Charset;
import java.nio.charset.CharsetEncoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Letters as PDF, written directly: A4 pages of plain text in Helvetica, the
 * first line in bold as the letterhead, wrapped to the page, with page
 * numbers. No library is needed, which keeps the build simple; the price is
 * that only the Latin-1 characters of the PDF standard fonts can be shown
 * (other characters become "?", and the rupee sign is written "Rs."). An
 * Organization that wants a designed letter uploads its own PDF instead.
 */
public final class Pdf {

    private static final float WIDTH = 595.28f;
    private static final float HEIGHT = 841.89f;
    private static final float MARGIN = 64f;
    private static final float SIZE = 11f;
    private static final float LEADING = 15.5f;
    private static final float HEAD_SIZE = 15f;
    private static final Charset WIN_ANSI = Charset.forName("windows-1252");

    /** Helvetica advance widths, in thousandths of the font size, for characters 32 to 126. */
    private static final int[] WIDTHS = {
        278, 278, 355, 556, 556, 889, 667, 191, 333, 333, 389, 584, 278, 333, 278, 278,
        556, 556, 556, 556, 556, 556, 556, 556, 556, 556, 278, 278, 584, 584, 584, 556,
        1015, 667, 667, 722, 722, 667, 611, 778, 722, 278, 500, 667, 556, 833, 722, 778,
        667, 778, 722, 667, 611, 722, 667, 944, 667, 667, 611, 278, 278, 278, 469, 556,
        333, 556, 556, 500, 556, 556, 278, 556, 556, 222, 222, 500, 222, 833, 556, 556,
        556, 556, 333, 500, 278, 556, 500, 722, 500, 500, 500, 334, 260, 334, 584,
    };

    private record Line(String text, boolean bold, float size, float gapBefore) {}

    private Pdf() {}

    /** A PDF of the text: the first non-empty line is the heading; blank lines separate paragraphs. */
    public static byte[] letter(String title, String text) {
        List<Line> lines = layout(text == null ? "" : text);
        List<List<Line>> pages = paginate(lines);
        return write(title, pages);
    }

    /**
     * One thing placed on a page: text at a point (right-aligned when asked), a horizontal rule, a filled band or an
     * outlined box. y is measured from the top of the page, as people lay out a form. {@code color} is an RGB value
     * such as 0x4F4AC0, or -1 for the default (black text, grey rules, light grey bands).
     */
    public record Item(float x, float y, String text, boolean bold, float size, boolean right, float ruleTo, boolean fill, int color, boolean box) {

        public static Item text(float x, float y, String text, float size, boolean bold) {
            return new Item(x, y, text, bold, size, false, 0, false, -1, false);
        }

        public static Item right(float x, float y, String text, float size, boolean bold) {
            return new Item(x, y, text, bold, size, true, 0, false, -1, false);
        }

        public static Item rule(float x, float y, float to) {
            return new Item(x, y, null, false, 0, false, to, false, -1, false);
        }

        /** A light grey band from x to ruleTo, height size, its top at y. */
        public static Item band(float x, float y, float to, float height) {
            return new Item(x, y, null, false, height, false, to, true, -1, false);
        }

        /** A thin outlined rectangle from x to ruleTo, height size, its top at y. */
        public static Item box(float x, float y, float to, float height) {
            return new Item(x, y, null, false, height, false, to, false, -1, true);
        }

        /** The same thing in another colour: the text, the band's fill, or the rule's or box's line. */
        public Item color(int rgb) {
            return new Item(x, y, text, bold, size, right, ruleTo, fill, rgb, box);
        }
    }

    public static final float PAGE_WIDTH = WIDTH;

    /** A single A4 page of placed text and rules, for forms such as a payslip. */
    public static byte[] page(String title, List<Item> items) {
        return writePage(title, items, null);
    }

    private static byte[] writePage(String title, List<Item> items, Image image) {
        ByteArrayOutputStream c = new ByteArrayOutputStream();
        if (image != null) {
            put(c, "q " + num(image.w()) + " 0 0 " + num(image.h()) + " " + num(image.x()) + " " + num(HEIGHT - image.y() - image.h()) + " cm /Im1 Do Q\n");
        }
        for (Item it : items) {
            float y = HEIGHT - it.y();
            if (it.text() == null && it.box()) {
                put(c, rgb(it.color(), 0xD5DAE1, "RG") + " 0.6 w " + num(it.x()) + " " + num(y - it.size()) + " " + num(it.ruleTo() - it.x()) + " "
                        + num(it.size()) + " re S 0 0 0 RG\n");
            } else if (it.text() == null && it.fill()) {
                put(c, rgb(it.color(), 0xF0F2F5, "rg") + " " + num(it.x()) + " " + num(y - it.size()) + " " + num(it.ruleTo() - it.x()) + " "
                        + num(it.size()) + " re f 0 0 0 rg\n");
            } else if (it.text() == null) {
                put(c, rgb(it.color(), 0xBFC7D1, "RG") + " 0.6 w " + num(it.x()) + " " + num(y) + " m " + num(it.ruleTo()) + " " + num(y)
                        + " l S 0 0 0 RG\n");
            } else {
                String text = clean(it.text());
                float x = it.right() ? it.x() - width(text, it.size(), it.bold()) : it.x();
                put(c, (it.color() < 0 ? "" : rgb(it.color(), 0, "rg") + " ") + "BT /" + (it.bold() ? "F2 " : "F1 ") + num(it.size()) + " Tf "
                        + num(x) + " " + num(y) + " Td ");
                c.writeBytes(string(text));
                put(c, " Tj ET" + (it.color() < 0 ? "" : " 0 0 0 rg") + "\n");
            }
        }
        return writeContents(title, List.of(c.toByteArray()), image);
    }

    /**
     * A picture placed on the page: pixels as RGB with an optional alpha mask, drawn in the box {@code w} by {@code h} points
     * whose top left corner is at ({@code x}, {@code y} from the top of the page).
     */
    public record Image(byte[] rgb, byte[] alpha, int width, int height, float x, float y, float w, float h) {}

    /**
     * A PNG or JPEG scaled to fit inside {@code maxW} by {@code maxH} points with its top left corner at (x, y from the top),
     * or null when the bytes are not an image the runtime can read: a page without its picture beats no page.
     */
    public static Image image(byte[] file, float x, float y, float maxW, float maxH) {
        try {
            java.awt.image.BufferedImage src = javax.imageio.ImageIO.read(new java.io.ByteArrayInputStream(file));
            if (src == null || src.getWidth() < 1 || src.getHeight() < 1) {
                return null;
            }
            float scale = Math.min(maxW / src.getWidth(), maxH / src.getHeight());
            float drawW = src.getWidth() * scale;
            float drawH = src.getHeight() * scale;
            // Enough pixels for about 288 dpi at the size it is drawn, and no more: a 1200 pixel logo drawn 120 points wide would otherwise
            // add megabytes to every certificate. Always redrawn as plain ARGB so palette, grey and 16-bit files come out right.
            int w = Math.max(1, Math.min(src.getWidth(), Math.round(drawW * 4)));
            int h = Math.max(1, Math.min(src.getHeight(), Math.round(drawH * 4)));
            java.awt.image.BufferedImage argb = new java.awt.image.BufferedImage(w, h, java.awt.image.BufferedImage.TYPE_INT_ARGB);
            java.awt.Graphics2D g = argb.createGraphics();
            g.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION, java.awt.RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            g.setRenderingHint(java.awt.RenderingHints.KEY_RENDERING, java.awt.RenderingHints.VALUE_RENDER_QUALITY);
            g.drawImage(src, 0, 0, w, h, null);
            g.dispose();
            byte[] rgb = new byte[w * h * 3];
            byte[] alpha = new byte[w * h];
            boolean transparent = false;
            for (int j = 0; j < h; j++) {
                for (int i = 0; i < w; i++) {
                    int p = argb.getRGB(i, j);
                    int k = j * w + i;
                    int a = p >>> 24;
                    transparent |= a != 255;
                    alpha[k] = (byte) a;
                    rgb[3 * k] = (byte) (p >> 16);
                    rgb[3 * k + 1] = (byte) (p >> 8);
                    rgb[3 * k + 2] = (byte) p;
                }
            }
            return new Image(rgb, transparent ? alpha : null, w, h, x, y, drawW, drawH);
        } catch (java.io.IOException | RuntimeException e) {
            return null;
        }
    }

    /** A single A4 page of placed text and rules, and a picture, for forms with a logo. */
    public static byte[] page(String title, List<Item> items, Image image) {
        return image == null ? page(title, items) : writePage(title, items, image);
    }

    // ── layout ──────────────────────────────────────────────────────────

    private static List<Line> layout(String text) {
        List<Line> out = new ArrayList<>();
        boolean headDone = false;
        float gap = 0;
        for (String raw : clean(text).split("\n", -1)) {
            String para = raw.strip();
            if (para.isEmpty()) {
                gap = out.isEmpty() ? 0 : LEADING * 0.6f;
                continue;
            }
            boolean head = !headDone;
            headDone = true;
            float size = head ? HEAD_SIZE : SIZE;
            boolean first = true;
            for (String wrapped : wrap(para, size, head)) {
                out.add(new Line(wrapped, head, size, first ? gap : 0));
                first = false;
            }
            gap = head ? LEADING * 0.8f : 0;
        }
        return out;
    }

    private static List<String> wrap(String para, float size, boolean bold) {
        float max = WIDTH - 2 * MARGIN;
        List<String> out = new ArrayList<>();
        String line = "";
        for (String word : para.split(" +")) {
            String candidate = line.isEmpty() ? word : line + " " + word;
            if (width(candidate, size, bold) <= max) {
                line = candidate;
                continue;
            }
            if (!line.isEmpty()) {
                out.add(line);
            }
            // A word longer than the line (a long link, say) is broken where it has to be.
            String rest = word;
            while (width(rest, size, bold) > max) {
                int cut = rest.length() - 1;
                while (cut > 1 && width(rest.substring(0, cut), size, bold) > max) {
                    cut--;
                }
                out.add(rest.substring(0, cut));
                rest = rest.substring(cut);
            }
            line = rest;
        }
        if (!line.isEmpty()) {
            out.add(line);
        }
        return out;
    }

    static float width(String s, float size, boolean bold) {
        float units = 0;
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            units += ch >= 32 && ch <= 126 ? WIDTHS[ch - 32] : 556;
        }
        return units * size / 1000f * (bold ? 1.06f : 1f);
    }

    private static List<List<Line>> paginate(List<Line> lines) {
        List<List<Line>> pages = new ArrayList<>();
        List<Line> page = new ArrayList<>();
        float y = HEIGHT - MARGIN;
        float bottom = MARGIN + 24;   // room for the page number
        for (Line l : lines) {
            float need = l.gapBefore() + (l.bold() ? HEAD_SIZE + 6 : LEADING);
            if (y - need < bottom && !page.isEmpty()) {
                pages.add(page);
                page = new ArrayList<>();
                y = HEIGHT - MARGIN;
                l = new Line(l.text(), l.bold(), l.size(), 0);
                need = l.bold() ? HEAD_SIZE + 6 : LEADING;
            }
            page.add(l);
            y -= need;
        }
        pages.add(page);
        return pages;
    }

    /** Characters the standard fonts cannot show are replaced; the rupee sign is spelt out. */
    static String clean(String text) {
        String s = text.replace("\r\n", "\n").replace('\r', '\n').replace("\t", "    ").replace("₹", "Rs.")
                .replace(' ', ' ');
        StringBuilder out = new StringBuilder();
        CharsetEncoder enc = WIN_ANSI.newEncoder();
        s.codePoints().forEach(cp -> {
            String ch = new String(Character.toChars(cp));
            out.append(cp == '\n' || (cp >= 32 && enc.canEncode(ch)) ? ch : "?");
        });
        return out.toString();
    }

    // ── writing ─────────────────────────────────────────────────────────

    private static byte[] write(String title, List<List<Line>> pages) {
        List<byte[]> contents = new ArrayList<>();
        for (int i = 0; i < pages.size(); i++) {
            contents.add(content(pages.get(i), i + 1, pages.size()));
        }
        return writeContents(title, contents, null);
    }

    private static byte[] writeContents(String title, List<byte[]> pages, Image image) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        List<Integer> offsets = new ArrayList<>();
        int pageCount = pages.size();
        int firstPage = 6;   // 1 catalog, 2 pages, 3 regular font, 4 bold font, 5 info; then a page and its content each
        int imageObj = firstPage + 2 * pageCount;   // then the picture, and its transparency mask when it has one
        put(out, "%PDF-1.4\n%âãÏÓ\n");

        StringBuilder kids = new StringBuilder();
        for (int i = 0; i < pageCount; i++) {
            kids.append(firstPage + 2 * i).append(" 0 R ");
        }
        object(out, offsets, 1, "<< /Type /Catalog /Pages 2 0 R >>");
        object(out, offsets, 2, "<< /Type /Pages /Kids [" + kids.toString().trim() + "] /Count " + pageCount + " >>");
        object(out, offsets, 3, "<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica /Encoding /WinAnsiEncoding >>");
        object(out, offsets, 4, "<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica-Bold /Encoding /WinAnsiEncoding >>");
        offsets.add(out.size());
        put(out, "5 0 obj\n<< /Title ");
        out.writeBytes(string(title == null ? "Letter" : clean(title)));
        put(out, " /Producer (HRMS) >>\nendobj\n");

        for (int i = 0; i < pageCount; i++) {
            int pageObj = firstPage + 2 * i;
            object(out, offsets, pageObj, "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 " + num(WIDTH) + " " + num(HEIGHT) + "] "
                    + "/Resources << /Font << /F1 3 0 R /F2 4 0 R >>" + (image == null ? "" : " /XObject << /Im1 " + imageObj + " 0 R >>")
                    + " >> /Contents " + (pageObj + 1) + " 0 R >>");
            byte[] content = pages.get(i);
            offsets.add(out.size());
            put(out, (pageObj + 1) + " 0 obj\n<< /Length " + content.length + " >>\nstream\n");
            out.writeBytes(content);
            put(out, "\nendstream\nendobj\n");
        }

        if (image != null) {
            imageObject(out, offsets, imageObj, image.width(), image.height(), "/DeviceRGB", image.rgb(), image.alpha() == null ? 0 : imageObj + 1);
            if (image.alpha() != null) {
                imageObject(out, offsets, imageObj + 1, image.width(), image.height(), "/DeviceGray", image.alpha(), 0);
            }
        }

        int xref = out.size();
        int count = offsets.size() + 1;
        put(out, "xref\n0 " + count + "\n0000000000 65535 f \n");
        for (int off : offsets) {
            put(out, String.format("%010d 00000 n \n", off));
        }
        put(out, "trailer\n<< /Size " + count + " /Root 1 0 R /Info 5 0 R >>\nstartxref\n" + xref + "\n%%EOF\n");
        return out.toByteArray();
    }

    private static byte[] content(List<Line> lines, int page, int pages) {
        ByteArrayOutputStream c = new ByteArrayOutputStream();
        float y = HEIGHT - MARGIN;
        for (Line l : lines) {
            y -= l.gapBefore() + (l.bold() ? HEAD_SIZE + 6 : LEADING);
            put(c, "BT /" + (l.bold() ? "F2 " : "F1 ") + num(l.size()) + " Tf " + num(MARGIN) + " " + num(y) + " Td ");
            c.writeBytes(string(l.text()));
            put(c, " Tj ET\n");
        }
        if (pages > 1) {
            String label = "Page " + page + " of " + pages;
            put(c, "BT /F1 9 Tf " + num(WIDTH - MARGIN - width(label, 9, false)) + " " + num(MARGIN - 8) + " Td ");
            c.writeBytes(string(label));
            put(c, " Tj ET\n");
        }
        return c.toByteArray();
    }

    /** A PDF string: the text in the standard encoding, with parentheses and backslashes escaped. */
    private static byte[] string(String s) {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        b.write('(');
        byte[] bytes;
        try {
            CharsetEncoder enc = WIN_ANSI.newEncoder().onUnmappableCharacter(CodingErrorAction.REPLACE)
                    .onMalformedInput(CodingErrorAction.REPLACE).replaceWith(new byte[] {'?'});
            ByteBuffer buf = enc.encode(CharBuffer.wrap(s));
            bytes = new byte[buf.remaining()];
            buf.get(bytes);
        } catch (java.nio.charset.CharacterCodingException e) {
            bytes = s.getBytes(StandardCharsets.US_ASCII);
        }
        for (byte x : bytes) {
            if (x == '(' || x == ')' || x == '\\') {
                b.write('\\');
            }
            b.write(x);
        }
        b.write(')');
        return b.toByteArray();
    }

    /** An image object: the samples deflated, with the object that masks it where it has transparency. */
    private static void imageObject(ByteArrayOutputStream out, List<Integer> offsets, int number, int width, int height, String colorSpace,
                                    byte[] samples, int mask) {
        ByteArrayOutputStream z = new ByteArrayOutputStream();
        try (java.util.zip.DeflaterOutputStream d = new java.util.zip.DeflaterOutputStream(z)) {
            d.write(samples);
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);   // writing to memory cannot fail
        }
        byte[] data = z.toByteArray();
        offsets.add(out.size());
        put(out, number + " 0 obj\n<< /Type /XObject /Subtype /Image /Width " + width + " /Height " + height + " /ColorSpace " + colorSpace
                + " /BitsPerComponent 8 /Filter /FlateDecode " + (mask == 0 ? "" : "/SMask " + mask + " 0 R ") + "/Length " + data.length
                + " >>\nstream\n");
        out.writeBytes(data);
        put(out, "\nendstream\nendobj\n");
    }

    private static void object(ByteArrayOutputStream out, List<Integer> offsets, int number, String body) {
        offsets.add(out.size());
        put(out, number + " 0 obj\n" + body + "\nendobj\n");
    }

    private static void put(ByteArrayOutputStream out, String s) {
        out.writeBytes(s.getBytes(StandardCharsets.ISO_8859_1));
    }

    /** A colour operator: the RGB value given, or the fallback when it is -1. */
    private static String rgb(int color, int fallback, String op) {
        int v = color < 0 ? fallback : color;
        return num(((v >> 16) & 0xFF) / 255f) + " " + num(((v >> 8) & 0xFF) / 255f) + " " + num((v & 0xFF) / 255f) + " " + op;
    }

    private static String num(float f) {
        return String.format(java.util.Locale.ROOT, "%.2f", f);
    }
}
