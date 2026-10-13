package com.example.loginapp.core;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Letters: offer, appointment, confirmation and others, issued from the
 * Organization's templates as PDF, or uploaded by HR. A letter can ask the
 * joiner to accept it, or to accept or decline it. An offer, when the
 * Organization uses the offer stage, comes first: the joiner sees only the
 * offer until they accept it, and a declined offer ends the onboarding.
 * Letters waiting for an answer block activation.
 */
public final class LetterService {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final Set<String> KINDS = Set.of("offer", "appointment", "confirmation", "other");
    private static final Set<String> RESPONSES = Set.of("none", "accept", "accept_or_decline");

    public record TemplateInput(String kind, String name, String body, String response, Map<String, Object> appliesTo, Boolean active) {}

    public record IssueInput(String templateId, Integer expiresInDays) {}

    private final Db db;
    private final Crypto crypto;
    private final DocumentService documents;
    private final Clock clock;

    public LetterService(Db db, Crypto crypto, DocumentService documents) {
        this(db, crypto, documents, Clock.systemUTC());
    }

    public LetterService(Db db, Crypto crypto, DocumentService documents, Clock clock) {
        this.db = db;
        this.crypto = crypto;
        this.documents = documents;
        this.clock = clock;
    }

    private LocalDate today() {
        return LocalDate.now(clock.withZone(IST));
    }

    // ── templates ───────────────────────────────────────────────────────

    public List<Map<String, Object>> templates(SessionCtx ctx) {
        requireRead(ctx);
        return db.inTenant(ctx.tenantId(), c -> {
            OnboardingDefaults.ensure(c, ctx.tenantId());
            ExitDefaults.ensure(c, ctx.tenantId());
            List<Map<String, Object>> out = new ArrayList<>();
            for (Map<String, Object> t : Sql.query(c, "select * from app.letter_template order by kind, lower(name)")) {
                t.put("appliesTo", AppliesTo.parse((String) t.get("appliesTo")).rules());
                out.add(t);
            }
            return out;
        });
    }

    /** Saves a template. A change to its wording is a new version; letters already issued keep theirs. */
    public Map<String, Object> saveTemplate(SessionCtx ctx, UUID id, TemplateInput in) {
        ctx.require("onboarding.configure");
        return db.inTenant(ctx.tenantId(), c -> {
            OnboardingDefaults.ensure(c, ctx.tenantId());
            Map<String, Object> current = null;
            if (id != null) {
                current = Sql.one(c, "select * from app.letter_template where id = ? for update", id);
                if (current == null) {
                    throw ApiException.notFound("The letter");
                }
            }
            String kind = in.kind() != null ? in.kind().trim() : current == null ? "other" : (String) current.get("kind");
            boolean exit = ExitLetters.KINDS.contains(kind);
            if (!KINDS.contains(kind) && !exit) {
                throw ApiException.badRequest("Not a kind of letter");
            }
            if (current != null && exit != ExitLetters.KINDS.contains((String) current.get("kind"))) {
                throw ApiException.badRequest("A joining letter cannot become an exit letter, or the other way round");
            }
            String name = in.name() != null ? in.name().trim() : current == null ? "" : (String) current.get("name");
            if (name.isEmpty() || name.length() > 80) {
                throw ApiException.badRequest("Give the letter a name of at most 80 characters");
            }
            String body = in.body() != null ? in.body() : current == null ? null : (String) current.get("body");
            if (body == null || body.isBlank() || body.length() > 20000) {
                throw ApiException.badRequest("Write the letter, in at most 20,000 characters");
            }
            Templates.check(body, exit ? Templates.EXIT_LETTER : Templates.LETTER);
            String response = exit ? "none"
                    : in.response() != null ? in.response().trim() : current == null ? "accept" : (String) current.get("response");
            if (!RESPONSES.contains(response)) {
                throw ApiException.badRequest("The response must be none, accept or accept_or_decline");
            }
            if (kind.equals("offer") && !response.equals("accept_or_decline")) {
                response = "accept_or_decline";   // an offer can always be declined
            }
            AppliesTo rules = in.appliesTo() != null ? AppliesTo.from(in.appliesTo())
                    : current == null ? AppliesTo.EVERYONE : AppliesTo.parse((String) current.get("appliesTo"));
            boolean active = in.active() != null ? in.active() : current == null || (Boolean) current.get("active");
            UUID saved;
            if (id == null) {
                saved = AuthService.uuid(Sql.one(c, "insert into app.letter_template (tenant_id, kind, name, body, response, applies_to, active, "
                        + "created_by) values (?, ?, ?, ?, ?, ?::jsonb, ?, ?) returning id", ctx.tenantId(), kind, name, body, response,
                        rules.toJson(), active, ctx.membershipId()).get("id"));
            } else {
                saved = id;
                boolean reworded = !body.equals(current.get("body"));
                Sql.update(c, "update app.letter_template set kind = ?, name = ?, body = ?, response = ?, applies_to = ?::jsonb, active = ?, "
                        + "version = version + ? where id = ?", kind, name, body, response, rules.toJson(), active, reworded ? 1 : 0, id);
            }
            Map<String, Object> after = Sql.one(c, "select * from app.letter_template where id = ?", saved);
            UserService.audit(c, ctx, id == null ? "onboarding.letter_created" : "onboarding.letter_changed", "letter_template", saved,
                    Map.of("name", name, "version", after.get("version")));
            after.put("appliesTo", rules.rules());
            return after;
        });
    }

    /** A template filled for one joiner, as text, to read before issuing it. */
    public Map<String, Object> preview(SessionCtx ctx, UUID onboardingId, UUID templateId) {
        requireRead(ctx);
        return db.inTenant(ctx.tenantId(), c -> {
            JoinerService.Loaded l = JoinerService.load(c, onboardingId);
            Map<String, Object> t = Sql.one(c, "select * from app.letter_template where id = ?", templateId);
            if (t == null) {
                throw ApiException.notFound("The letter");
            }
            Map<String, String> values = TemplateValues.forJoiner(c, l, null);
            values.put("offer.expires_on", TemplateValues.DATE.format(today().plusDays(expiryDays(c, null))));
            return Map.of("text", Templates.render((String) t.get("body"), values));
        });
    }

    // ── one joiner's letters ────────────────────────────────────────────

    public Map<String, Object> forJoiner(SessionCtx ctx, UUID onboardingId) {
        return db.inTenant(ctx.tenantId(), c -> {
            OnboardingDefaults.ensure(c, ctx.tenantId());
            JoinerService.Loaded l = JoinerService.load(c, onboardingId);
            JoinerService.requireSee(c, ctx, l);
            AppliesTo.Subject subject = JoinerService.subject(c, l);
            List<Map<String, Object>> templates = new ArrayList<>();
            for (Map<String, Object> t : Sql.query(c, "select id, kind, name, version, response, applies_to from app.letter_template "
                    + "where active and kind in ('offer','appointment','confirmation','other') order by kind, lower(name)")) {
                if (AppliesTo.parse((String) t.remove("appliesTo")).matches(subject)) {
                    templates.add(t);
                }
            }
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("letters", letters(c, l, today()));
            out.put("templates", templates);
            out.put("canIssue", isHr(ctx) && l.open());
            out.put("offerExpiryDays", expiryDays(c, null));
            return out;
        });
    }

    /** Issues a letter from a template: filled for the joiner, made a PDF, and kept as a document of theirs. */
    public Map<String, Object> issue(SessionCtx ctx, UUID onboardingId, IssueInput in) {
        requireHr(ctx);
        UUID templateId;
        try {
            templateId = UUID.fromString(in.templateId() == null ? "" : in.templateId().trim());
        } catch (IllegalArgumentException e) {
            throw ApiException.badRequest("Choose a letter to issue");
        }
        LocalDate today = today();
        return db.inTenant(ctx.tenantId(), c -> {
            JoinerService.Loaded l = JoinerService.load(c, onboardingId);
            JoinerService.requireOpen(l);
            Map<String, Object> t = Sql.one(c, "select * from app.letter_template where id = ? and active "
                    + "and kind in ('offer','appointment','confirmation','other')", templateId);
            if (t == null) {
                throw ApiException.badRequest("Choose a letter that is in use");
            }
            String kind = (String) t.get("kind");
            int days = expiryDays(c, in.expiresInDays());
            LocalDate expires = kind.equals("offer") ? today.plusDays(days) : null;
            Map<String, String> values = TemplateValues.forJoiner(c, l, crypto);
            values.put("offer.expires_on", expires == null ? "" : TemplateValues.DATE.format(expires));
            String text = Templates.render((String) t.get("body"), values);
            String title = (String) t.get("name");
            byte[] pdf = Pdf.letter(title, text);
            FileSniffer.Cleaned file = new FileSniffer.Cleaned("application/pdf", pdf, FileSniffer.safeName(title, "application/pdf"));
            UUID document = documents.insert(c, ctx, l.tenantId(), l.personId(), l.id(), null, file);
            UUID letter = AuthService.uuid(Sql.one(c, "insert into app.issued_letter (tenant_id, person_id, template_id, template_version, "
                    + "document_id, issued_by, expires_on, onboarding_id, kind, title, response_needed) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) "
                    + "returning id", l.tenantId(), l.personId(), templateId, t.get("version"), document, ctx.membershipId(), expires, l.id(),
                    kind, title, t.get("response")).get("id"));
            return issued(c, ctx, l, letter, kind, title);
        });
    }

    /** Issues a letter HR wrote elsewhere, as a PDF. */
    public Map<String, Object> upload(SessionCtx ctx, UUID onboardingId, String title, String kind, String response, Integer expiresInDays,
                                      byte[] bytes) {
        requireHr(ctx);
        String k = kind == null || kind.isBlank() ? "other" : kind.trim();
        if (!KINDS.contains(k)) {
            throw ApiException.badRequest("The kind must be offer, appointment, confirmation or other");
        }
        String r = k.equals("offer") ? "accept_or_decline" : response == null || response.isBlank() ? "accept" : response.trim();
        if (!RESPONSES.contains(r)) {
            throw ApiException.badRequest("The response must be none, accept or accept_or_decline");
        }
        String t = title == null || title.isBlank() ? "Letter" : title.trim();
        if (t.length() > 80) {
            throw ApiException.badRequest("Keep the title to 80 characters");
        }
        FileSniffer.Cleaned file = FileSniffer.clean(bytes, t + ".pdf");
        if (!file.contentType().equals("application/pdf")) {
            throw new ApiException(415, "document.type", "A letter must be a PDF");
        }
        LocalDate today = today();
        return db.inTenant(ctx.tenantId(), c -> {
            JoinerService.Loaded l = JoinerService.load(c, onboardingId);
            JoinerService.requireOpen(l);
            LocalDate expires = k.equals("offer") ? today.plusDays(expiryDays(c, expiresInDays)) : null;
            UUID document = documents.insert(c, ctx, l.tenantId(), l.personId(), l.id(), null, file);
            UUID letter = AuthService.uuid(Sql.one(c, "insert into app.issued_letter (tenant_id, person_id, document_id, issued_by, expires_on, "
                    + "onboarding_id, kind, title, response_needed) values (?, ?, ?, ?, ?, ?, ?, ?, ?) returning id", l.tenantId(), l.personId(),
                    document, ctx.membershipId(), expires, l.id(), k, t, r).get("id"));
            return issued(c, ctx, l, letter, k, t);
        });
    }

    /** After a letter is issued: an offer puts the onboarding in the offered state; the joiner is told. */
    private Map<String, Object> issued(Connection c, SessionCtx ctx, JoinerService.Loaded l, UUID letter, String kind, String title)
            throws SQLException {
        if (kind.equals("offer") && Set.of("draft", "invited", "in_progress").contains((String) l.row().get("status"))) {
            Sql.update(c, "update app.onboarding set status = 'offered' where id = ?", l.id());
        }
        UserService.audit(c, ctx, "joiner.letter_issued", "onboarding", l.id(), Map.of("letter", title, "kind", kind));
        Notifications.aboutJoiner(c, l, l.membershipId(), kind.equals("offer") ? "offer_issued" : "section_returned",
                Map.of("section.title", "letters", "note", "There is a letter for you to read: " + title), "my-onboarding");
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", letter.toString());
        out.put("letters", letters(c, JoinerService.load(c, l.id()), today()));
        return out;
    }

    // ── the joiner's own ────────────────────────────────────────────────

    public List<Map<String, Object>> mine(SessionCtx ctx) {
        return db.inTenant(ctx.tenantId(), c -> letters(c, JoinerService.mineLoaded(c, ctx), today()));
    }

    /** The joiner accepts or declines a letter. Declining an offer ends the onboarding. */
    public Map<String, Object> respond(SessionCtx ctx, UUID letterId, boolean accept) {
        LocalDate today = today();
        Map<String, Object> result = db.inTenant(ctx.tenantId(), c -> {
            JoinerService.Loaded l = JoinerService.mineLoaded(c, ctx);
            JoinerService.requireOpen(l);
            Map<String, Object> letter = Sql.one(c, "select * from app.issued_letter where id = ? and person_id = ? for update", letterId,
                    l.personId());
            if (letter == null) {
                throw ApiException.notFound("The letter");
            }
            if (letter.get("response") != null) {
                throw new ApiException(409, "letter.answered", "You have already answered this letter");
            }
            String needed = (String) letter.get("responseNeeded");
            if (needed.equals("none") || (!accept && !needed.equals("accept_or_decline"))) {
                throw new ApiException(409, "letter.no_response", accept ? "This letter needs no answer" : "This letter cannot be declined");
            }
            if (letter.get("expiresOn") != null && LocalDate.parse((String) letter.get("expiresOn")).isBefore(today)) {
                throw new ApiException(409, "letter.expired", "This letter expired on "
                        + TemplateValues.DATE.format(LocalDate.parse((String) letter.get("expiresOn"))) + ". Ask HR for a new one");
            }
            Sql.update(c, "update app.issued_letter set response = ?, responded_at = now() where id = ?", accept ? "accepted" : "declined",
                    letterId);
            boolean offer = "offer".equals(letter.get("kind"));
            UserService.audit(c, ctx, accept ? "joiner.letter_accepted" : "joiner.letter_declined", "onboarding", l.id(),
                    Map.of("letter", letter.get("title")));
            UUID ended = null;
            if (offer && accept) {
                Sql.update(c, "update app.onboarding set offer_accepted_at = now(), status = case when status = 'offered' then "
                        + "case when notice_id is null then 'invited' else 'in_progress' end else status end where id = ?", l.id());
                ChecklistService.schedule(c, l.id());
            } else if (offer) {
                Sql.update(c, "update app.onboarding set status = 'offer_declined' where id = ?", l.id());
                Sql.update(c, "update app.employment set status = 'withdrawn' where id = ?", l.employmentId());
                if (l.membershipId() != null) {
                    Sql.update(c, "delete from app.membership_role where membership_id = ?", l.membershipId());
                    Sql.update(c, "update app.membership set status = 'ended', ended_at = now() where id = ? and status <> 'ended'",
                            l.membershipId());
                    ended = l.membershipId();
                }
            }
            JoinerService.noteIfReady(c, l.id());
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("letters", letters(c, JoinerService.load(c, l.id()), today));
            out.put("ended", ended == null ? null : ended.toString());
            return out;
        });
        if (result.get("ended") != null) {
            UUID membership = UUID.fromString((String) result.get("ended"));
            db.inAuth(c -> Sql.update(c, "update auth.session set revoked_at = now() where membership_id = ? and revoked_at is null", membership));
        }
        return result;
    }

    // ── shared ──────────────────────────────────────────────────────────

    static List<Map<String, Object>> letters(Connection c, JoinerService.Loaded l, LocalDate today) throws SQLException {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> r : Sql.query(c, "select il.id, il.kind, il.title, il.document_id, il.issued_at, il.expires_on, il.response, "
                + "il.responded_at, il.response_needed, il.template_version, d.email as issued_by from app.issued_letter il "
                + "left join app.member_directory() d on d.membership_id = il.issued_by where il.person_id = ? order by il.issued_at desc",
                l.personId())) {
            Map<String, Object> m = new LinkedHashMap<>(r);
            boolean expired = r.get("response") == null && r.get("expiresOn") != null
                    && LocalDate.parse((String) r.get("expiresOn")).isBefore(today);
            m.put("expired", expired);
            m.put("waiting", r.get("response") == null && !"none".equals(r.get("responseNeeded")) && !expired);
            out.add(m);
        }
        return out;
    }

    /** Letters of this onboarding still waiting for the joiner's answer, in words. */
    static List<String> blockers(Connection c, JoinerService.Loaded l) throws SQLException {
        List<String> out = new ArrayList<>();
        for (Map<String, Object> r : Sql.query(c, "select title, response from app.issued_letter where onboarding_id = ? "
                + "and response_needed <> 'none' and response is null "
                + "and (expires_on is null or expires_on >= (now() at time zone 'Asia/Kolkata')::date) order by issued_at", l.id())) {
            out.add("Letter: " + r.get("title") + " waiting for the joiner");
        }
        return out;
    }

    private static int expiryDays(Connection c, Integer given) throws SQLException {
        if (given != null) {
            if (given < 1 || given > 60) {
                throw ApiException.badRequest("An offer can be open for 1 to 60 days");
            }
            return given;
        }
        Map<String, Object> s = Sql.one(c, "select offer_expiry_days from app.onboarding_settings");
        return s == null ? 7 : ((Number) s.get("offerExpiryDays")).intValue();
    }

    private static boolean isHr(SessionCtx ctx) {
        return ctx.canAcrossOrganization("employee.write") && ctx.membershipId() != null;
    }

    private static void requireHr(SessionCtx ctx) {
        if (!isHr(ctx)) {
            throw ApiException.denied("employee.write");
        }
    }

    private static void requireRead(SessionCtx ctx) {
        if (!ctx.reachesEveryone("employee.read")) {
            throw ApiException.denied("employee.read");
        }
    }
}
