package com.example.loginapp.core;

import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import javax.imageio.ImageIO;

/**
 * An Organization's own logo. Without one the app shows the product's brand (Nexvance). The Org
 * Admin uploads a PNG or JPEG; it is drawn again as a PNG no larger than 1200 by 400 pixels, which
 * leaves only the pixels, and served to anyone who asks by the Organization's id: a logo is public.
 */
public final class Branding {

    /** A logo file larger than this is refused before it is read. */
    public static final int MAX_BYTES = 2 * 1024 * 1024;
    static final int MAX_WIDTH = 1200;
    static final int MAX_HEIGHT = 400;

    public record Logo(byte[] bytes, int version) {}

    private final Db db;

    public Branding(Db db) {
        this.db = db;
    }

    private static void requireAdmin(SessionCtx ctx) {
        if (!ctx.inOrganization() || ctx.isSupport() || !ctx.canAcrossOrganization("tenant.manage")) {
            throw ApiException.denied("tenant.manage");
        }
    }

    /** What the Organization shows: its logo's version (0 for none) and size. */
    public Map<String, Object> get(SessionCtx ctx) {
        if (!ctx.inOrganization()) {
            throw ApiException.denied("tenant.manage");
        }
        return db.inTenant(ctx.tenantId(), c -> view(c, ctx));
    }

    public Map<String, Object> saveLogo(SessionCtx ctx, byte[] upload) {
        requireAdmin(ctx);
        if (upload == null || upload.length == 0) {
            throw new ApiException(400, "logo.empty", "Choose a logo file");
        }
        if (upload.length > MAX_BYTES) {
            throw new ApiException(413, "logo.too_large", "The logo is larger than 2 MB");
        }
        String type = FileSniffer.sniff(upload);
        if (!"image/png".equals(type) && !"image/jpeg".equals(type)) {
            throw new ApiException(415, "logo.type", "The logo must be a PNG or JPEG file");
        }
        BufferedImage image = redraw(upload);
        byte[] png = png(image);
        return db.inTenant(ctx.tenantId(), c -> {
            Sql.update(c, "insert into app.organization_branding (tenant_id, logo, logo_type, logo_width, logo_height, updated_by) "
                    + "values (?, ?, 'image/png', ?, ?, ?) on conflict (tenant_id) do update set logo = excluded.logo, "
                    + "logo_width = excluded.logo_width, logo_height = excluded.logo_height, version = app.organization_branding.version + 1, "
                    + "updated_by = excluded.updated_by, updated_at = now()", ctx.tenantId(), png, image.getWidth(), image.getHeight(),
                    ctx.membershipId());
            UserService.audit(c, ctx, "organization.logo_changed", "organization", ctx.tenantId(),
                    Map.of("width", image.getWidth(), "height", image.getHeight()));
            return view(c, ctx);
        });
    }

    /** Back to the product's brand. */
    public Map<String, Object> removeLogo(SessionCtx ctx) {
        requireAdmin(ctx);
        return db.inTenant(ctx.tenantId(), c -> {
            if (Sql.update(c, "delete from app.organization_branding where tenant_id = ?", ctx.tenantId()) > 0) {
                UserService.audit(c, ctx, "organization.logo_removed", "organization", ctx.tenantId(), Map.of());
            }
            return view(c, ctx);
        });
    }

    /** The logo of an Organization, or null when it has none. */
    public Logo logo(UUID tenantId) {
        return db.inTenant(tenantId, c -> {
            Map<String, Object> r = Sql.one(c, "select version from app.organization_branding where tenant_id = ?", tenantId);
            if (r == null) {
                return null;
            }
            try (var ps = c.prepareStatement("select logo from app.organization_branding where tenant_id = ?")) {
                ps.setObject(1, tenantId);
                try (var rs = ps.executeQuery()) {
                    return rs.next() ? new Logo(rs.getBytes(1), ((Number) r.get("version")).intValue()) : null;
                }
            }
        });
    }

    /** The version of the Organization's logo, 0 for none; part of what the signed-in person is told. */
    static int version(java.sql.Connection c) throws java.sql.SQLException {
        Map<String, Object> r = Sql.one(c, "select version from app.organization_branding");
        return r == null ? 0 : ((Number) r.get("version")).intValue();
    }

    private static Map<String, Object> view(java.sql.Connection c, SessionCtx ctx) throws java.sql.SQLException {
        Map<String, Object> r = Sql.one(c, "select version, logo_width, logo_height, updated_at from app.organization_branding");
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("logoVersion", r == null ? 0 : r.get("version"));
        out.put("logoWidth", r == null ? null : r.get("logoWidth"));
        out.put("logoHeight", r == null ? null : r.get("logoHeight"));
        out.put("updatedAt", r == null ? null : r.get("updatedAt"));
        out.put("canChange", ctx.canAcrossOrganization("tenant.manage") && !ctx.isSupport());
        return out;
    }

    /** Reads the image and draws it again, scaled down to fit, keeping transparency. */
    static BufferedImage redraw(byte[] bytes) {
        BufferedImage source;
        try {
            source = ImageIO.read(new ByteArrayInputStream(bytes));
        } catch (IOException | RuntimeException e) {
            source = null;
        }
        if (source == null) {
            throw new ApiException(415, "logo.unreadable", "This image could not be read");
        }
        int w = source.getWidth();
        int h = source.getHeight();
        if (w < 16 || h < 16) {
            throw new ApiException(400, "logo.too_small", "The logo is smaller than 16 pixels; use a larger image");
        }
        double scale = Math.min(1.0, Math.min((double) MAX_WIDTH / w, (double) MAX_HEIGHT / h));
        int nw = Math.max(1, (int) Math.round(w * scale));
        int nh = Math.max(1, (int) Math.round(h * scale));
        BufferedImage out = new BufferedImage(nw, nh, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = out.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            g.drawImage(source, 0, 0, nw, nh, null);
        } finally {
            g.dispose();
        }
        return out;
    }

    private static byte[] png(BufferedImage image) {
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            ImageIO.write(image, "png", out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException("A PNG could not be written", e);
        }
    }
}
