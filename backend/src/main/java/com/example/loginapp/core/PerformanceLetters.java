package com.example.loginapp.core;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * The letters performance decisions issue: confirmation, probation extension, increment and promotion. Each is filled
 * from the Organization's active template of its kind, made a PDF in the employee's documents, and
 * the employee is told. An Organization that has turned a template off issues nothing for it.
 */
public final class PerformanceLetters {

    /** The letter kinds that belong to performance. */
    public static final Set<String> KINDS = Set.of("confirmation", "probation_extension", "increment", "promotion");

    private PerformanceLetters() {}

    /**
     * Issues the active letter of this kind for the employment, linked to the record it decides
     * ({@code linkColumn} on issued_letter, such as probation_review_id). Returns the letter's id,
     * or null when the Organization has no active template of that kind.
     */
    static UUID issue(Connection c, SessionCtx ctx, DocumentService documents, UUID employmentId, String kind,
                      Map<String, String> values, String linkColumn, UUID linkId) throws SQLException {
        if (!Set.of("probation_review_id", "review_outcome_id").contains(linkColumn)) {
            throw new IllegalArgumentException("Not a letter link: " + linkColumn);
        }
        Map<String, Object> t = Sql.one(c, "select * from app.letter_template where kind = ? and active order by created_at limit 1", kind);
        if (t == null) {
            return null;
        }
        Map<String, Object> e = Sql.one(c, "select person_id, membership_id from app.employment where id = ?", employmentId);
        String title = (String) t.get("name");
        byte[] pdf = Pdf.letter(title, Templates.render((String) t.get("body"), values));
        FileSniffer.Cleaned file = FileSniffer.clean(pdf, title + ".pdf");
        UUID personId = AuthService.uuid(e.get("personId"));
        UUID document = documents.insert(c, ctx, ctx.tenantId(), personId, null, null, file);
        UUID letter = AuthService.uuid(Sql.one(c, "insert into app.issued_letter (tenant_id, person_id, template_id, template_version, "
                + "document_id, issued_by, kind, title, response_needed, " + linkColumn + ") values (?, ?, ?::uuid, ?, ?, ?, ?, ?, 'none', ?) "
                + "returning id", ctx.tenantId(), personId, t.get("id"), t.get("version"), document, ctx.membershipId(), kind, title, linkId)
                .get("id"));
        UserService.audit(c, ctx, "performance.letter_issued", "employment", employmentId, Map.of("letter", title, "kind", kind));
        UUID employee = AuthService.uuid(e.get("membershipId"));
        if (employee != null) {
            Map<String, String> v = new java.util.HashMap<>(values);
            v.put("letter.title", title.toLowerCase(Locale.ENGLISH));
            Notifications.send(c, ctx.tenantId(), employee, "probation_letter_issued", v, "my-performance");
        }
        return letter;
    }
}
