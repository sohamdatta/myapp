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
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        List<Integer> offsets = new ArrayList<>();
        int pageCount = pages.size();
        int firstPage = 6;   // 1 catalog, 2 pages, 3 regular font, 4 bold font, 5 info; then a page and its content each
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
                    + "/Resources << /Font << /F1 3 0 R /F2 4 0 R >> >> /Contents " + (pageObj + 1) + " 0 R >>");
            byte[] content = content(pages.get(i), i + 1, pageCount);
            offsets.add(out.size());
            put(out, (pageObj + 1) + " 0 obj\n<< /Length " + content.length + " >>\nstream\n");
            out.writeBytes(content);
            put(out, "\nendstream\nendobj\n");
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

    private static void object(ByteArrayOutputStream out, List<Integer> offsets, int number, String body) {
        offsets.add(out.size());
        put(out, number + " 0 obj\n" + body + "\nendobj\n");
    }

    private static void put(ByteArrayOutputStream out, String s) {
        out.writeBytes(s.getBytes(StandardCharsets.ISO_8859_1));
    }

    private static String num(float f) {
        return String.format(java.util.Locale.ROOT, "%.2f", f);
    }
}
