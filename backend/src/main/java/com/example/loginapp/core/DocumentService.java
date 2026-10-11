package com.example.loginapp.core;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * A joiner's documents: the list each joiner must give (from the document
 * types whose rules match them), uploads, verifying or rejecting each one,
 * and serving a file. Bytes are checked by {@link FileSniffer}, encrypted
 * like PAN, and only ever sent to the person themselves or to someone with
 * {@code employee.sensitive}; every view is recorded.
 */
public final class DocumentService {

    /** A file to send: its type, a safe name, and the bytes. */
    public record Content(String contentType, String fileName, byte[] bytes) {}

    private final Db db;
    private final Crypto crypto;

    public DocumentService(Db db, Crypto crypto) {
        this.db = db;
        this.crypto = crypto;
    }

    // ── the joiner's own ────────────────────────────────────────────────

    /** The documents the signed-in joiner must give, with what they have uploaded for each. */
    public Map<String, Object> mine(SessionCtx ctx) {
        return db.inTenant(ctx.tenantId(), c -> {
            JoinerService.Loaded l = JoinerService.mineLoaded(c, ctx);
            Map<String, Object> out = checklist(c, l);
            out.put("canUpload", canJoinerUpload(l));
            return out;
        });
    }

    public Map<String, Object> uploadMine(SessionCtx ctx, String typeId, String fileName, byte[] bytes) {
        FileSniffer.Cleaned file = FileSniffer.clean(bytes, fileName);
        return db.inTenant(ctx.tenantId(), c -> {
            JoinerService.Loaded l = JoinerService.mineLoaded(c, ctx);
            if (!canJoinerUpload(l)) {
                throw new ApiException(409, "onboarding.closed", "Your onboarding is closed; send documents to HR");
            }
            if (l.row().get("noticeId") == null) {
                throw new ApiException(409, "onboarding.locked", "Accept the notice first");
            }
            UUID type = type(c, l, typeId);
            if (type != null && Sql.one(c, "select 1 from app.document where person_id = ? and document_type_id = ? and status = 'verified'",
                    l.personId(), type) != null) {
                throw new ApiException(409, "document.verified", "This document is already verified. Ask HR if it needs replacing");
            }
            return store(c, ctx, l, type, file);
        });
    }

    /** The joiner's own file. */
    public Content myContent(SessionCtx ctx, UUID documentId) {
        return db.inTenant(ctx.tenantId(), c -> {
            JoinerService.Loaded l = JoinerService.mineLoaded(c, ctx);
            Map<String, Object> d = Sql.one(c, "select id from app.document where id = ? and person_id = ?", documentId, l.personId());
            if (d == null) {
                throw ApiException.notFound("The document");
            }
            return read(c, ctx, documentId);
        });
    }

    // ── HR ───────────────────────────────────────────────────────────────

    /** A joiner's documents for HR or a verifier: the list to give, and whether the caller can decide them. */
    public Map<String, Object> forJoiner(SessionCtx ctx, UUID onboardingId) {
        return db.inTenant(ctx.tenantId(), c -> {
            JoinerService.Loaded l = JoinerService.load(c, onboardingId);
            JoinerService.requireSee(c, ctx, l);
            Map<String, Object> out = checklist(c, l);
            out.put("canVerify", JoinerService.canVerify(c, ctx, l, "documents", 1));
            out.put("canOpen", canOpen(ctx));
            out.put("canUpload", ctx.canAcrossOrganization("employee.write") && ctx.membershipId() != null
                    && !"withdrawn".equals(l.row().get("status")));
            return out;
        });
    }

    /** HR uploads a document for the joiner, for example one handed over on paper. */
    public Map<String, Object> uploadForJoiner(SessionCtx ctx, UUID onboardingId, String typeId, String fileName, byte[] bytes) {
        ctx.require("employee.write");
        if (!ctx.canAcrossOrganization("employee.write") || ctx.membershipId() == null) {
            throw ApiException.denied("employee.write");
        }
        FileSniffer.Cleaned file = FileSniffer.clean(bytes, fileName);
        return db.inTenant(ctx.tenantId(), c -> {
            JoinerService.Loaded l = JoinerService.load(c, onboardingId);
            if ("withdrawn".equals(l.row().get("status"))) {
                throw new ApiException(409, "onboarding.closed", "This joiner was withdrawn");
            }
            return store(c, ctx, l, type(c, l, typeId), file);
        });
    }

    public Map<String, Object> verify(SessionCtx ctx, UUID onboardingId, UUID documentId) {
        return decide(ctx, onboardingId, documentId, true, null);
    }

    public Map<String, Object> reject(SessionCtx ctx, UUID onboardingId, UUID documentId, String note) {
        if (note == null || note.trim().length() < 3) {
            throw ApiException.badRequest("Say what is wrong with the document");
        }
        return decide(ctx, onboardingId, documentId, false, note.trim());
    }

    private Map<String, Object> decide(SessionCtx ctx, UUID onboardingId, UUID documentId, boolean verified, String note) {
        return db.inTenant(ctx.tenantId(), c -> {
            JoinerService.Loaded l = JoinerService.load(c, onboardingId);
            if (ctx.membershipId() != null && ctx.membershipId().equals(l.membershipId())) {
                throw new ApiException(409, "onboarding.own_section", "Nobody verifies their own documents");
            }
            if (!JoinerService.canVerify(c, ctx, l, "documents", 1)) {
                throw new ApiException(403, "permission.denied", "You are not a verifier for this joiner's documents");
            }
            Map<String, Object> d = Sql.one(c, "select d.status, d.file_name, t.name as type_name from app.document d "
                    + "left join app.document_type t on t.id = d.document_type_id where d.id = ? and d.person_id = ? for update of d",
                    documentId, l.personId());
            if (d == null) {
                throw ApiException.notFound("The document");
            }
            if (!"uploaded".equals(d.get("status"))) {
                throw new ApiException(409, "document.decided", "This document is " + d.get("status") + " already");
            }
            Sql.update(c, "update app.document set status = ?, decision_note = ?, decided_by = ?, decided_at = now() where id = ?",
                    verified ? "verified" : "rejected", note, ctx.membershipId(), documentId);
            refreshSection(c, l);
            Map<String, Object> detail = new LinkedHashMap<>();
            detail.put("document", d.get("typeName") == null ? d.get("fileName") : d.get("typeName"));
            if (note != null) {
                detail.put("note", note);
            }
            UserService.audit(c, ctx, verified ? "joiner.document_verified" : "joiner.document_rejected", "onboarding", onboardingId, detail);
            return checklist(c, JoinerService.load(c, onboardingId));
        });
    }

    /**
     * Any document, for someone with {@code employee.sensitive} across the Organization, or the
     * person it belongs to. Every view is recorded.
     */
    public Content content(SessionCtx ctx, UUID documentId) {
        return db.inTenant(ctx.tenantId(), c -> {
            Map<String, Object> d = Sql.one(c, "select d.person_id from app.document d where d.id = ?", documentId);
            if (d == null) {
                throw ApiException.notFound("The document");
            }
            boolean own = ctx.membershipId() != null && Sql.one(c, "select 1 from app.membership where id = ? and person_id = ?::uuid",
                    ctx.membershipId(), d.get("personId")) != null;
            if (!own && !canOpen(ctx)) {
                throw ApiException.notFound("The document");
            }
            return read(c, ctx, documentId);
        });
    }

    // ── what the joiner must give, and what blocks activation ───────────

    /**
     * The document types that apply to this joiner, each with its current document (the newest one
     * not replaced), and the other documents uploaded without a type.
     */
    static Map<String, Object> checklist(Connection c, JoinerService.Loaded l) throws SQLException {
        AppliesTo.Subject subject = JoinerService.subject(c, l);
        List<Map<String, Object>> types = new ArrayList<>();
        for (Map<String, Object> t : applicableTypes(c, subject)) {
            Map<String, Object> row = new LinkedHashMap<>(t);
            row.remove("appliesTo");
            if ("within_days".equals(t.get("required"))) {
                row.put("dueOn", l.joiningDate().plusDays(((Number) t.get("withinDays")).longValue()).toString());
            }
            row.put("documents", Sql.query(c, DOC_SELECT + " where d.person_id = ? and d.document_type_id = ?::uuid "
                    + "order by (d.status = 'superseded'), d.created_at desc limit 5", l.personId(), t.get("id")));
            types.add(row);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("types", types);
        out.put("others", Sql.query(c, DOC_SELECT + " where d.person_id = ? and d.document_type_id is null "
                + "and not exists (select 1 from app.onboarding_task k where k.attachment_id = d.id) order by d.created_at desc", l.personId()));
        Map<String, Object> s = l.sections().get("documents");
        out.put("status", s == null ? null : s.get("status"));
        out.put("joiningDate", l.joiningDate().toString());
        return out;
    }

    private static final String DOC_SELECT = "select d.id, d.document_type_id, d.file_name, d.content_type, d.size_bytes, d.status, "
            + "d.decision_note, d.decided_at, d.created_at, du.email as decided_by, uu.email as uploaded_by "
            + "from app.document d left join app.member_directory() du on du.membership_id = d.decided_by "
            + "left join app.member_directory() uu on uu.membership_id = d.uploaded_by";

    static List<Map<String, Object>> applicableTypes(Connection c, AppliesTo.Subject subject) throws SQLException {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> t : Sql.query(c, "select id, name, required, within_days, instructions, applies_to from app.document_type "
                + "where active order by position, lower(name)")) {
            if (AppliesTo.parse((String) t.get("appliesTo")).matches(subject)) {
                out.add(t);
            }
        }
        return out;
    }

    /** Documents needed before activation that are not verified yet, in words. */
    static List<String> blockers(Connection c, JoinerService.Loaded l) throws SQLException {
        if (!l.sections().containsKey("documents")) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        for (Map<String, Object> t : applicableTypes(c, JoinerService.subject(c, l))) {
            if (!"before_activation".equals(t.get("required"))) {
                continue;
            }
            String state = current(c, l.personId(), t.get("id"));
            if (!"verified".equals(state)) {
                out.add(t.get("name") + ": " + switch (state == null ? "" : state) {
                    case "uploaded" -> "waiting to be verified";
                    case "rejected" -> "rejected, waiting for a new one";
                    default -> "not uploaded yet";
                });
            }
        }
        return out;
    }

    /** The status of the newest document of a type that has not been replaced, or null when there is none. */
    private static String current(Connection c, UUID personId, Object typeId) throws SQLException {
        Map<String, Object> d = Sql.one(c, "select status from app.document where person_id = ? and document_type_id = ?::uuid "
                + "and status <> 'superseded' order by created_at desc limit 1", personId, typeId.toString());
        return d == null ? null : (String) d.get("status");
    }

    /**
     * Works out the Documents section's status from the documents, so lists and the joiner's progress
     * read it like any other section: verified when everything needed before activation is verified,
     * returned when something was rejected, submitted when everything needed is waiting to be checked.
     */
    static void refreshSection(Connection c, JoinerService.Loaded l) throws SQLException {
        if (!l.sections().containsKey("documents")) {
            return;
        }
        boolean rejected = false;
        boolean allVerified = true;
        boolean allGiven = true;
        boolean any = Sql.one(c, "select 1 from app.document where person_id = ? and status <> 'superseded' limit 1", l.personId()) != null;
        String note = null;
        for (Map<String, Object> t : applicableTypes(c, JoinerService.subject(c, l))) {
            String state = current(c, l.personId(), t.get("id"));
            if ("rejected".equals(state)) {
                rejected = true;
                Map<String, Object> r = Sql.one(c, "select decision_note from app.document where person_id = ? and document_type_id = ?::uuid "
                        + "and status = 'rejected' order by created_at desc limit 1", l.personId(), t.get("id").toString());
                note = note == null ? t.get("name") + ": " + r.get("decisionNote") : note;
            }
            if ("before_activation".equals(t.get("required"))) {
                allVerified &= "verified".equals(state);
                allGiven &= "verified".equals(state) || "uploaded".equals(state);
            }
        }
        String status = rejected ? "returned" : allVerified ? "verified" : allGiven ? "submitted" : any ? "draft" : "not_started";
        // Who "sent" the section: whoever uploaded last, or whoever added the joiner when nothing is needed.
        Map<String, Object> by = Sql.one(c, "select coalesce(s.submitted_by, "
                + "(select d.uploaded_by from app.document d where d.person_id = ? and d.uploaded_by is not null order by d.created_at desc limit 1), "
                + "o.created_by) as by from app.onboarding_section s join app.onboarding o on o.id = s.onboarding_id "
                + "where s.onboarding_id = ? and s.section = 'documents'", l.personId(), l.id());
        Object submitter = by == null ? null : by.get("by");
        if (submitter == null && !status.equals("draft")) {
            status = "not_started";
        }
        Sql.update(c, "update app.onboarding_section set status = ?, note = ?, level = case when ? = 'verified' then 1 else 0 end, "
                + "submitted_by = ?::uuid, submitted_at = coalesce(submitted_at, case when ?::uuid is null then null else now() end) "
                + "where onboarding_id = ? and section = 'documents'",
                status, rejected ? note : null, status, submitter, submitter, l.id());
    }

    // ── storing and reading ─────────────────────────────────────────────

    private Map<String, Object> store(Connection c, SessionCtx ctx, JoinerService.Loaded l, UUID type, FileSniffer.Cleaned file)
            throws SQLException {
        if (type != null) {
            // A new upload replaces the earlier ones of the same type; they are kept, marked replaced.
            Sql.update(c, "update app.document set status = 'superseded' where person_id = ? and document_type_id = ? "
                    + "and status <> 'superseded'", l.personId(), type);
        }
        UUID id = insert(c, ctx, l.tenantId(), l.personId(), l.id(), type, file);
        refreshSection(c, JoinerService.load(c, l.id()));
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("document", type == null ? file.fileName()
                : Sql.one(c, "select name from app.document_type where id = ?", type).get("name"));
        detail.put("bytes", file.bytes().length);
        UserService.audit(c, ctx, "joiner.document_uploaded", "onboarding", l.id(), detail);
        return Map.of("id", id.toString(), "fileName", file.fileName(), "contentType", file.contentType());
    }

    /** Writes a document and its encrypted bytes. Used for joiners' documents and checklist attachments. */
    UUID insert(Connection c, SessionCtx ctx, UUID tenantId, UUID personId, UUID onboardingId, UUID type, FileSniffer.Cleaned file)
            throws SQLException {
        UUID id = AuthService.uuid(Sql.one(c, "insert into app.document (tenant_id, person_id, document_type_id, file_name, content_type, "
                + "size_bytes, sha256, uploaded_by, onboarding_id) values (?, ?, ?, ?, ?, ?, ?, ?, ?) returning id", tenantId, personId, type,
                file.fileName(), file.contentType(), file.bytes().length, sha256(file.bytes()), ctx.membershipId(), onboardingId).get("id"));
        Sql.update(c, "insert into app.document_content (document_id, tenant_id, content_enc) values (?, ?, ?)", id, tenantId,
                crypto.encrypt(tenantId, file.bytes()));
        return id;
    }

    private Content read(Connection c, SessionCtx ctx, UUID documentId) throws SQLException {
        Map<String, Object> d = Sql.one(c, "select d.file_name, d.content_type, d.person_id, d.onboarding_id, t.name as type_name "
                + "from app.document d left join app.document_type t on t.id = d.document_type_id where d.id = ?", documentId);
        byte[] sealed;
        try (PreparedStatement ps = c.prepareStatement("select content_enc from app.document_content where document_id = ?")) {
            ps.setObject(1, documentId);
            try (ResultSet rs = ps.executeQuery()) {
                sealed = rs.next() ? rs.getBytes(1) : null;
            }
        }
        if (sealed == null) {
            throw ApiException.notFound("The document");
        }
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("document", d.get("typeName") == null ? d.get("fileName") : d.get("typeName"));
        detail.put("documentId", documentId.toString());
        UserService.audit(c, ctx, "document.viewed", d.get("onboardingId") == null ? "person" : "onboarding",
                AuthService.uuid(d.get("onboardingId") == null ? d.get("personId") : d.get("onboardingId")), detail);
        UUID tenant = ctx.tenantId();
        return new Content((String) d.get("contentType"), (String) d.get("fileName"), crypto.decrypt(tenant, sealed));
    }

    // ── helpers ─────────────────────────────────────────────────────────

    private static boolean canOpen(SessionCtx ctx) {
        return ctx.reachesEveryone("employee.sensitive") && !ctx.isSupport();
    }

    /** While joining, and afterwards for documents due within days of joining. Never once withdrawn. */
    private static boolean canJoinerUpload(JoinerService.Loaded l) {
        return Set.of("draft", "offered", "invited", "in_progress", "active").contains((String) l.row().get("status"));
    }

    private static UUID type(Connection c, JoinerService.Loaded l, String typeId) throws SQLException {
        if (typeId == null || typeId.isBlank()) {
            return null;
        }
        UUID id;
        try {
            id = UUID.fromString(typeId.trim());
        } catch (IllegalArgumentException e) {
            throw ApiException.badRequest("Not a valid document type id");
        }
        AppliesTo.Subject subject = JoinerService.subject(c, l);
        if (applicableTypes(c, subject).stream().noneMatch(t -> id.toString().equals(t.get("id")))) {
            throw ApiException.badRequest("That document is not one this joiner is asked for");
        }
        return id;
    }

    static byte[] sha256(byte[] bytes) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(bytes);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
