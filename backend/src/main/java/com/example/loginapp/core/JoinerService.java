package com.example.loginapp.core;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Onboarding a joiner: HR adds them, invites them, the joiner accepts the
 * privacy notice and fills their sections, verifiers check each section at
 * the flow's levels, and the joiner becomes an active employee on the joining
 * date. There is no background job: invitations and activations that have
 * fallen due are applied whenever joiners are read, and for a joiner on every
 * request they make.
 */
public final class JoinerService {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    public record NewJoiner(String legalName, String personalEmail, String mobile, String joiningDate, String employmentType,
                            String designation, String departmentId, String managerId, String workLocationId, String shiftId,
                            List<Integer> weeklyOffs, String employeeCode, String flowId, Boolean invite, String rehireOf,
                            String contractEnd) {}

    private final Db db;
    private final Crypto crypto;
    private final Clock clock;
    private final JoinerSections sections;

    public JoinerService(Db db, Crypto crypto) {
        this(db, crypto, Clock.systemUTC());
    }

    public JoinerService(Db db, Crypto crypto, Clock clock) {
        this.db = db;
        this.crypto = crypto;
        this.clock = clock;
        this.sections = new JoinerSections(crypto);
    }

    private LocalDate today() {
        return LocalDate.now(clock.withZone(IST));
    }

    // ── HR: the list and adding a joiner ────────────────────────────────

    /** Joiners and tiles. Status is open (the default: not yet active or withdrawn), active, withdrawn or all. */
    public Map<String, Object> list(SessionCtx ctx, String status) {
        requireHrRead(ctx);
        String wanted = status == null || status.isBlank() ? "open" : status;
        if (!Set.of("open", "active", "withdrawn", "all").contains(wanted)) {
            throw ApiException.badRequest("Status must be open, active, withdrawn or all");
        }
        LocalDate today = today();
        Map<String, Object> out = db.inTenant(ctx.tenantId(), c -> {
            OnboardingDefaults.ensure(c, ctx.tenantId());
            applyDue(c, ctx.tenantId(), today, ctx);
            String filter = switch (wanted) {
                case "open" -> " where o.status in ('draft','offered','invited','in_progress')";
                case "active" -> " where o.status = 'active'";
                case "withdrawn" -> " where o.status in ('withdrawn','offer_declined')";
                default -> "";
            };
            List<Map<String, Object>> rows = new ArrayList<>();
            int week = 0;
            int waitingJoiner = 0;
            int waitingHr = 0;
            int ready = 0;
            int overdue = 0;
            for (Map<String, Object> o : Sql.query(c, LIST_SELECT + filter + " order by e.joining_date, p.legal_name limit 500", today, today)) {
                Map<String, Object> row = new LinkedHashMap<>(o);
                boolean open = Set.of("draft", "offered", "invited", "in_progress").contains((String) o.get("status"));
                if (open) {
                    Loaded l = load(c, AuthService.uuid(o.get("id")));
                    List<String> blockers = blockers(c, l);
                    row.put("ready", blockers.isEmpty());
                    row.put("waitingOn", waitingOn(c, l));
                    LocalDate joining = LocalDate.parse((String) o.get("joiningDate"));
                    if (!joining.isBefore(today) && joining.isBefore(today.plusDays(7))) {
                        week++;
                    }
                    overdue += ((Number) o.get("overdueTasks")).intValue();
                    switch ((String) row.get("waitingOn")) {
                        case "joiner" -> waitingJoiner++;
                        case "hr" -> waitingHr++;
                        default -> ready++;
                    }
                }
                rows.add(row);
            }
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("tiles", Map.of("joiningThisWeek", week, "waitingOnJoiner", waitingJoiner, "waitingOnHr", waitingHr,
                    "ready", ready, "overdueTasks", overdue));
            result.put("joiners", rows);
            return result;
        });
        purgeWithdrawn(ctx.tenantId());
        return out;
    }

    /**
     * Adds a joiner with what HR knows: name, contact, joining date and the job. The rehire check
     * looks for an earlier person with the same personal email, mobile or PAN, as the Organization
     * chooses; if it finds one, HR must say whether this is them ({@code rehireOf} = their person id)
     * or someone new ({@code rehireOf} = "new").
     */
    public Map<String, Object> add(SessionCtx ctx, NewJoiner in) {
        requireHrWrite(ctx);
        String name = required(in.legalName(), "the joiner's full name", 120);
        String email = blank(in.personalEmail()) ? null : IdChecks.email(in.personalEmail());
        String mobile = blank(in.mobile()) ? null : IdChecks.mobile(in.mobile());
        LocalDate joining = date(in.joiningDate(), "joining date");
        if (joining == null) {
            throw ApiException.badRequest("Enter the joining date");
        }
        LocalDate today = today();
        if (joining.isBefore(today.minusDays(90))) {
            throw ApiException.badRequest("The joining date cannot be more than 90 days ago; add earlier staff under Employees");
        }
        String type = blank(in.employmentType()) ? "permanent" : in.employmentType().trim();
        if (!AppliesTo.EMPLOYMENT_TYPES.contains(type)) {
            throw ApiException.badRequest("Employment type must be permanent, probation, fixed_term, intern or consultant");
        }
        LocalDate contractEnd = date(in.contractEnd(), "contract end");
        if (type.equals("fixed_term") && contractEnd == null) {
            throw ApiException.badRequest("Enter when the fixed-term contract ends");
        }
        if (contractEnd != null && contractEnd.isBefore(joining)) {
            throw ApiException.badRequest("The contract cannot end before the joining date");
        }
        String designation = required(in.designation(), "the designation", 80);
        boolean invite = Boolean.TRUE.equals(in.invite());
        if (invite && email == null) {
            throw ApiException.badRequest("Enter the joiner's personal email to invite them");
        }
        return db.inTenant(ctx.tenantId(), c -> {
            OnboardingDefaults.ensure(c, ctx.tenantId());
            Map<String, Object> settings = settings(c);

            UUID personId = null;
            String rehire = blank(in.rehireOf()) ? null : in.rehireOf().trim();
            if (rehire == null || !rehire.equals("new")) {
                List<Map<String, Object>> candidates = rehireCandidates(c, settings, email, mobile);
                if (rehire == null && !candidates.isEmpty()) {
                    Map<String, Object> out = new LinkedHashMap<>();
                    out.put("possibleRehire", candidates);
                    return out;
                }
                if (rehire != null) {
                    personId = id(rehire, "earlier person");
                    UUID chosen = personId;
                    if (candidates.stream().noneMatch(r -> chosen.toString().equals(r.get("personId")))) {
                        throw ApiException.badRequest("That person is not one the rehire check found");
                    }
                    if (Sql.one(c, "select 1 from app.employment where person_id = ? and status in ('onboarding','active')", personId) != null) {
                        throw new ApiException(409, "employee.already_employed", "That person is already employed or joining");
                    }
                    Sql.update(c, "update app.person set personal_email = coalesce(?, personal_email), mobile = coalesce(?, mobile) "
                            + "where id = ?", email, mobile, personId);
                }
            }
            if (personId == null) {
                personId = AuthService.uuid(Sql.one(c, "insert into app.person (tenant_id, legal_name, personal_email, mobile) "
                        + "values (?, ?, ?, ?) returning id", ctx.tenantId(), name, email, mobile).get("id"));
            }
            UUID employmentId = AuthService.uuid(Sql.one(c, "insert into app.employment (tenant_id, person_id, employment_type, "
                    + "joining_date, contract_end, status, created_by) values (?, ?, ?, ?, ?, 'onboarding', ?) returning id",
                    ctx.tenantId(), personId, type, joining, contractEnd, ctx.membershipId()).get("id"));

            UUID flowId;
            UUID departmentId = blank(in.departmentId()) ? null : id(in.departmentId(), "department");
            UUID locationId = blank(in.workLocationId()) ? null : id(in.workLocationId(), "work location");
            if (!blank(in.flowId())) {
                flowId = id(in.flowId(), "flow");
                if (Sql.one(c, "select 1 from app.onboarding_flow where id = ? and active", flowId) == null) {
                    throw ApiException.badRequest("Choose an onboarding flow that is in use");
                }
            } else {
                flowId = OnboardingConfigService.pickFlow(c, new AppliesTo.Subject(departmentId, locationId, type, designation));
            }
            String inviteWhen = (String) settings.get("inviteWhen");
            LocalDate inviteDue = inviteWhen.equals("days_before_joining")
                    ? joining.minusDays(((Number) settings.get("inviteDaysBefore")).intValue()) : null;
            UUID onboardingId = AuthService.uuid(Sql.one(c, "insert into app.onboarding (tenant_id, person_id, employment_id, flow_id, "
                    + "invite_due_on, rehire_of, created_by) values (?, ?, ?, ?, ?, ?, ?) returning id", ctx.tenantId(), personId,
                    employmentId, flowId, inviteDue, rehire == null || rehire.equals("new") ? null : personId, ctx.membershipId()).get("id"));
            // The sections this joiner has: those the flow shows, copied now so later configuration changes leave them be.
            for (Map<String, Object> s : Sql.query(c, "select section from app.section_setting where flow_id = ? and enabled "
                    + "order by position", flowId)) {
                if (!FieldCatalog.LATER.contains((String) s.get("section"))) {
                    Sql.update(c, "insert into app.onboarding_section (tenant_id, onboarding_id, section) values (?, ?, ?)",
                            ctx.tenantId(), onboardingId, s.get("section"));
                }
            }

            // HR's part, the Employment section, from what was given.
            Loaded l = load(c, onboardingId);
            Map<String, Object> employment = new LinkedHashMap<>();
            employment.put("employment.designation", designation);
            employment.put("employment.department", departmentId == null ? null : departmentId.toString());
            employment.put("employment.manager", blank(in.managerId()) ? null : in.managerId().trim());
            employment.put("employment.work_location", locationId == null ? null : locationId.toString());
            employment.put("employment.shift", blank(in.shiftId()) ? null : in.shiftId().trim());
            employment.put("employment.weekly_offs", in.weeklyOffs() == null ? List.of(0) : in.weeklyOffs());
            if (!blank(in.employeeCode())) {
                employment.put("employment.employee_code", in.employeeCode().trim());
            }
            sections.save(c, ctx, l.joiner(), "employment", employment, allKeys(c, l, "employment"), true);
            if (l.sections().containsKey("employment")) {
                Sql.update(c, "update app.onboarding_section set status = 'draft' where onboarding_id = ? and section = 'employment'",
                        onboardingId);
                if (missing(c, load(c, onboardingId), "employment").isEmpty()) {
                    submit(c, ctx, load(c, onboardingId), "employment");
                }
            }
            ChecklistService.createTasks(c, load(c, onboardingId));
            audit(c, ctx, "joiner.added", onboardingId, detail("name", name, "joiningDate", joining.toString(),
                    "flow", Sql.one(c, "select name from app.onboarding_flow where id = ?", flowId).get("name"),
                    "rehireOf", rehire == null || rehire.equals("new") ? null : rehire));

            Map<String, Object> out = new LinkedHashMap<>();
            out.put("id", onboardingId.toString());
            if (invite || inviteWhen.equals("on_create") && email != null) {
                out.put("invitation", invitation(c, ctx, load(c, onboardingId), settings));
            }
            return out;
        });
    }

    // ── HR: one joiner ──────────────────────────────────────────────────

    public Map<String, Object> get(SessionCtx ctx, UUID onboardingId) {
        LocalDate today = today();
        return db.inTenant(ctx.tenantId(), c -> {
            applyDue(c, ctx.tenantId(), today, ctx);
            Loaded l = load(c, onboardingId);
            requireSee(c, ctx, l);
            Map<String, Object> out = new LinkedHashMap<>(Sql.one(c, LIST_SELECT + " where o.id = ?", today, today, onboardingId));
            out.put("flow", Sql.one(c, "select id, name, verification_levels from app.onboarding_flow where id = ?", l.flowId()));
            List<Map<String, Object>> list = new ArrayList<>();
            for (Map<String, Object> s : sectionRows(c, l)) {
                list.add(s);
            }
            out.put("sections", list);
            out.put("blockers", l.open() ? blockers(c, l) : List.of());
            out.put("invitation", Sql.one(c, "select i.created_at, i.expires_at, (i.expires_at <= now()) as expired from app.invitation i "
                    + "where i.person_id = ? and i.accepted_at is null and i.revoked_at is null", l.personId()));
            out.put("hasLogin", l.membershipId() != null);
            out.put("noticeAcceptedAt", l.row().get("noticeAcceptedAt"));
            out.put("activation", settings(c).get("activation"));
            out.put("canManage", ctx.canAcrossOrganization("employee.write") && ctx.membershipId() != null);
            return out;
        });
    }

    /** One section of a joiner, for HR or a verifier: the form, the values (identifiers masked), status and history. */
    public Map<String, Object> section(SessionCtx ctx, UUID onboardingId, String section) {
        return db.inTenant(ctx.tenantId(), c -> {
            Loaded l = load(c, onboardingId);
            requireSee(c, ctx, l);
            requireSection(l, section);
            Map<String, Object> out = sectionView(c, l, section);
            boolean hr = ctx.canAcrossOrganization("employee.write") && ctx.membershipId() != null;
            out.put("canEdit", hr && l.open());
            Map<String, Object> s = l.sections().get(section);
            int next = ((Number) s.get("level")).intValue() + 1;
            out.put("canVerify", l.open() && "submitted".equals(s.get("status")) && canVerify(c, ctx, l, section, next));
            out.put("nextLevel", next);
            out.put("reviews", Sql.query(c, "select r.level, r.decision, r.note, r.decided_at, d.email as decided_by "
                    + "from app.section_review r left join app.member_directory() d on d.membership_id = r.decided_by "
                    + "where r.onboarding_id = ? and r.section = ? order by r.decided_at desc", onboardingId, section));
            return out;
        });
    }

    /** HR fills or corrects a section for the joiner. Audited; the joiner sees the change. */
    public Map<String, Object> saveSection(SessionCtx ctx, UUID onboardingId, String section, Map<String, Object> values) {
        requireHrWrite(ctx);
        return db.inTenant(ctx.tenantId(), c -> {
            Loaded l = load(c, onboardingId);
            requireOpen(l);
            requireSection(l, section);
            sections.save(c, ctx, l.joiner(), section, values == null ? Map.of() : values, allKeys(c, l, section), true);
            Map<String, Object> s = l.sections().get(section);
            if (!section.equals("documents") && ("not_started".equals(s.get("status")) || "verified".equals(s.get("status")))) {
                // A correction to a verified section sends it back through verification.
                Sql.update(c, "update app.onboarding_section set status = 'draft', level = 0 where onboarding_id = ? and section = ?",
                        onboardingId, section);
            }
            if (section.equals("employment")) {
                ChecklistService.schedule(c, onboardingId);   // the joining date or manager may have changed
            }
            if (section.equals("documents")) {
                DocumentService.refreshSection(c, load(c, onboardingId));
            }
            audit(c, ctx, "joiner.section_filled_by_hr", onboardingId,
                    Map.of("section", section, "fields", String.join(",", values == null ? List.of() : values.keySet())));
            return sectionView(c, load(c, onboardingId), section);
        });
    }

    /** HR submits a section they filled, for verification. */
    public Map<String, Object> submitSection(SessionCtx ctx, UUID onboardingId, String section) {
        requireHrWrite(ctx);
        return db.inTenant(ctx.tenantId(), c -> {
            Loaded l = load(c, onboardingId);
            requireOpen(l);
            requireSection(l, section);
            submit(c, ctx, l, section);
            return sectionView(c, load(c, onboardingId), section);
        });
    }

    /** Verifies a section at its next level. The last level, or a section that is not verified, makes it verified. */
    public Map<String, Object> verify(SessionCtx ctx, UUID onboardingId, String section, String note) {
        return decide(ctx, onboardingId, section, true, note);
    }

    /** Returns a section to the joiner with a note saying what to fix. */
    public Map<String, Object> returnSection(SessionCtx ctx, UUID onboardingId, String section, String note) {
        if (note == null || note.trim().length() < 3) {
            throw ApiException.badRequest("Say what needs fixing");
        }
        return decide(ctx, onboardingId, section, false, note);
    }

    private Map<String, Object> decide(SessionCtx ctx, UUID onboardingId, String section, boolean verified, String note) {
        refuseDocuments(section);
        if (ctx.membershipId() == null) {
            throw new ApiException(403, "permission.denied", "Only a user of the Organization can verify");
        }
        return db.inTenant(ctx.tenantId(), c -> {
            Loaded l = load(c, onboardingId);
            requireOpen(l);
            requireSection(l, section);
            if (ctx.membershipId().equals(l.membershipId())) {
                throw new ApiException(409, "onboarding.own_section", "Nobody verifies their own details");
            }
            Map<String, Object> s = Sql.one(c, "select status, level from app.onboarding_section where onboarding_id = ? and section = ? "
                    + "for update", onboardingId, section);
            if (!"submitted".equals(s.get("status"))) {
                throw new ApiException(409, "onboarding.not_submitted", "This section is not waiting to be verified");
            }
            int level = ((Number) s.get("level")).intValue() + 1;
            if (!canVerify(c, ctx, l, section, level)) {
                throw new ApiException(403, "permission.denied", "You are not a verifier for this section at level " + level);
            }
            String clean = note == null || note.isBlank() ? null : note.trim();
            Sql.update(c, "insert into app.section_review (tenant_id, onboarding_id, section, level, decision, note, decided_by) "
                    + "values (?, ?, ?, ?, ?, ?, ?)", ctx.tenantId(), onboardingId, section, (short) level,
                    verified ? "verified" : "returned", clean, ctx.membershipId());
            if (!verified) {
                Sql.update(c, "update app.onboarding_section set status = 'returned', level = 0, note = ? "
                        + "where onboarding_id = ? and section = ?", clean, onboardingId, section);
            } else if (level >= levelsFor(c, l, section)) {
                Sql.update(c, "update app.onboarding_section set status = 'verified', level = ?, note = null "
                        + "where onboarding_id = ? and section = ?", (short) level, onboardingId, section);
            } else {
                Sql.update(c, "update app.onboarding_section set level = ? where onboarding_id = ? and section = ?",
                        (short) level, onboardingId, section);
            }
            audit(c, ctx, verified ? "joiner.section_verified" : "joiner.section_returned", onboardingId,
                    detail("section", section, "level", level, "note", clean));
            if (verified) {
                noteIfReady(c, onboardingId);
            } else {
                Notifications.aboutJoiner(c, l, l.membershipId(), "section_returned",
                        Map.of("section.title", String.valueOf(sectionRows(c, l).stream().filter(r -> section.equals(r.get("section")))
                                .findFirst().map(r -> r.get("title")).orElse(section)), "note", clean), "my-onboarding");
            }
            return sectionView(c, load(c, onboardingId), section);
        });
    }

    /** Sends (or sends again) the invitation link. Returns the token for the link, which is shown once. */
    public Map<String, Object> invite(SessionCtx ctx, UUID onboardingId) {
        requireHrWrite(ctx);
        return db.inTenant(ctx.tenantId(), c -> {
            Loaded l = load(c, onboardingId);
            requireOpen(l);
            return invitation(c, ctx, l, settings(c));
        });
    }

    /**
     * Activates the joiner: at once when the joining date has come, otherwise on that date. Refused
     * with the list of what is missing while anything blocks it.
     */
    public Map<String, Object> activate(SessionCtx ctx, UUID onboardingId) {
        requireHrWrite(ctx);
        LocalDate today = today();
        return db.inTenant(ctx.tenantId(), c -> {
            Loaded l = load(c, onboardingId);
            requireOpen(l);
            List<String> blockers = blockers(c, l);
            if (!blockers.isEmpty()) {
                throw new ApiException(409, "onboarding.blocked", "Not ready to activate: " + String.join("; ", blockers));
            }
            Map<String, Object> out = new LinkedHashMap<>();
            if (l.joiningDate().isAfter(today)) {
                Sql.update(c, "update app.onboarding set activation_requested_at = now() where id = ?", onboardingId);
                audit(c, ctx, "joiner.activation_scheduled", onboardingId, Map.of("on", l.joiningDate().toString()));
                out.put("activated", false);
                out.put("on", l.joiningDate().toString());
            } else {
                activateNow(c, ctx.tenantId(), l, ctx);
                out.put("activated", true);
            }
            return out;
        });
    }

    /** A joiner who will not join: the login, link and onboarding end, and their data is deleted after the retention period. */
    public void withdraw(SessionCtx ctx, UUID onboardingId, String reason) {
        requireHrWrite(ctx);
        if (reason == null || reason.trim().length() < 5) {
            throw ApiException.badRequest("Give a reason of at least 5 characters");
        }
        UUID membership = db.inTenant(ctx.tenantId(), c -> {
            Loaded l = load(c, onboardingId);
            requireOpen(l);
            Sql.update(c, "update app.onboarding set status = 'withdrawn', withdrawn_at = now(), withdrawn_reason = ? where id = ?",
                    reason.trim(), onboardingId);
            Sql.update(c, "update app.employment set status = 'withdrawn' where id = ?", l.employmentId());
            Sql.update(c, "update app.invitation set revoked_at = now() where person_id = ? and accepted_at is null and revoked_at is null",
                    l.personId());
            if (l.membershipId() != null) {
                Sql.update(c, "delete from app.membership_role where membership_id = ?", l.membershipId());
                Sql.update(c, "update app.membership set status = 'ended', ended_at = now() where id = ? and status <> 'ended'",
                        l.membershipId());
            }
            audit(c, ctx, "joiner.withdrawn", onboardingId, Map.of("reason", reason.trim()));
            return l.membershipId();
        });
        if (membership != null) {
            db.inAuth(c -> Sql.update(c, "update auth.session set revoked_at = now() where membership_id = ? and revoked_at is null",
                    membership));
        }
    }

    /** Everything recorded about this onboarding, newest first. */
    public List<Map<String, Object>> history(SessionCtx ctx, UUID onboardingId) {
        return db.inTenant(ctx.tenantId(), c -> {
            Loaded l = load(c, onboardingId);
            requireSee(c, ctx, l);
            return Sql.query(c, "select at, actor_kind, actor_label, action, detail from app.audit_event "
                    + "where target_id = ? or (target_type = 'employment' and target_id = ?) order by at desc, id desc limit 200",
                    onboardingId, l.employmentId());
        });
    }

    // ── the joiner's own onboarding ─────────────────────────────────────

    /** The signed-in joiner's onboarding: welcome, notice, sections and their status. */
    public Map<String, Object> mine(SessionCtx ctx) {
        LocalDate today = today();
        return db.inTenant(ctx.tenantId(), c -> {
            Loaded l = mineLoaded(c, ctx);
            applyDue(c, ctx.tenantId(), today, null);
            l = load(c, l.id());
            Map<String, Object> out = new LinkedHashMap<>();
            Map<String, Object> f = Sql.one(c, "select f.name, f.welcome_message, f.welcome_video_url, f.day_one_note, d.email as hr_contact "
                    + "from app.onboarding_flow f left join app.member_directory() d on d.membership_id = f.hr_contact_id where f.id = ?",
                    l.flowId());
            Map<String, Object> job = Sql.one(c, LIST_SELECT + " where o.id = ?", today, today, l.id());
            out.put("id", l.id().toString());
            out.put("status", l.row().get("status"));
            out.put("organization", ctx.tenantName());
            out.put("legalName", job.get("legalName"));
            out.put("joiningDate", l.joiningDate().toString());
            out.put("designation", job.get("designation"));
            out.put("managerName", job.get("managerName"));
            out.put("workLocation", job.get("workLocation"));
            out.put("welcome", f);
            Map<String, Object> notice = Sql.one(c, "select id, version, body from app.privacy_notice order by version desc limit 1");
            out.put("notice", notice);
            out.put("noticeAccepted", l.row().get("noticeId") != null);
            List<Map<String, Object>> list = new ArrayList<>();
            for (Map<String, Object> s : sectionRows(c, l)) {
                Map<String, Object> m = new LinkedHashMap<>(s);
                m.put("yours", !s.get("section").equals("employment"));
                list.add(m);
            }
            out.put("sections", list);
            return out;
        });
    }

    public Map<String, Object> acceptNotice(SessionCtx ctx, UUID noticeId) {
        return db.inTenant(ctx.tenantId(), c -> {
            Loaded l = mineLoaded(c, ctx);
            requireOpen(l);
            Map<String, Object> latest = Sql.one(c, "select id from app.privacy_notice order by version desc limit 1");
            if (latest == null || !latest.get("id").equals(noticeId == null ? null : noticeId.toString())) {
                throw new ApiException(409, "onboarding.notice_changed", "The notice has changed. Read the new version, then accept it");
            }
            Sql.update(c, "update app.onboarding set notice_id = ?, notice_accepted_at = now(), "
                    + "status = case when status in ('draft','invited') then 'in_progress' else status end where id = ?", noticeId, l.id());
            audit(c, ctx, "joiner.notice_accepted", l.id(), Map.of("noticeId", noticeId.toString()));
            return Map.of("accepted", true);
        });
    }

    public Map<String, Object> mySection(SessionCtx ctx, String section) {
        return db.inTenant(ctx.tenantId(), c -> {
            Loaded l = mineLoaded(c, ctx);
            requireSection(l, section);
            Map<String, Object> out = sectionView(c, l, section);
            out.put("canEdit", joinerCanEdit(l, section));
            return out;
        });
    }

    public Map<String, Object> saveMySection(SessionCtx ctx, String section, Map<String, Object> values) {
        return db.inTenant(ctx.tenantId(), c -> {
            Loaded l = mineLoaded(c, ctx);
            requireSection(l, section);
            requireOpen(l);
            if (!joinerCanEdit(l, section)) {
                throw new ApiException(409, "onboarding.locked", section.equals("employment")
                        ? "HR fills in your employment details" : l.row().get("noticeId") == null
                        ? "Accept the notice first" : "This section has been sent for checking; HR will return it if anything needs changing");
            }
            sections.save(c, ctx, l.joiner(), section, values == null ? Map.of() : values, joinerKeys(c, l, section), false);
            Sql.update(c, "update app.onboarding_section set status = 'draft' where onboarding_id = ? and section = ? "
                    + "and status = 'not_started'", l.id(), section);
            Map<String, Object> out = sectionView(c, load(c, l.id()), section);
            out.put("canEdit", true);
            return out;
        });
    }

    public Map<String, Object> submitMySection(SessionCtx ctx, String section) {
        return db.inTenant(ctx.tenantId(), c -> {
            Loaded l = mineLoaded(c, ctx);
            requireSection(l, section);
            requireOpen(l);
            if (!joinerCanEdit(l, section)) {
                throw new ApiException(409, "onboarding.locked", "This section cannot be sent now");
            }
            submit(c, ctx, l, section);
            Map<String, Object> out = sectionView(c, load(c, l.id()), section);
            out.put("canEdit", joinerCanEdit(load(c, l.id()), section));
            return out;
        });
    }

    // ── what has fallen due ─────────────────────────────────────────────

    /** For the session check: activates this joiner if their day has come and nothing blocks it. */
    public static boolean activateIfDue(Connection c, UUID tenantId, UUID membershipId, LocalDate today) throws SQLException {
        Map<String, Object> o = Sql.one(c, "select o.id from app.onboarding o join app.employment e on e.id = o.employment_id "
                + "join app.membership m on m.person_id = o.person_id "
                + "where m.id = ? and o.status in ('draft','invited','in_progress') and e.joining_date <= ?", membershipId, today);
        if (o == null) {
            return false;
        }
        Loaded l = load(c, AuthService.uuid(o.get("id")));
        if (!dueForActivation(c, l, settings(c), today)) {
            return false;
        }
        activateNow(c, tenantId, l, null);
        return true;
    }

    private void applyDue(Connection c, UUID tenantId, LocalDate today, SessionCtx ctx) throws SQLException {
        Map<String, Object> settings = settings(c);
        // Invitations scheduled for a number of days before joining.
        for (Map<String, Object> o : Sql.query(c, "select o.id from app.onboarding o join app.person p on p.id = o.person_id "
                + "where o.status = 'draft' and o.invite_due_on <= ? and p.personal_email is not null "
                + "and not exists (select 1 from app.invitation i where i.person_id = o.person_id and i.revoked_at is null)", today)) {
            Loaded l = load(c, AuthService.uuid(o.get("id")));
            try {
                invitation(c, null, l, settings);
            } catch (ApiException e) {
                // The email belongs to an existing user, or similar: HR sees it when they invite by hand.
            }
        }
        // Activations whose day has come.
        for (Map<String, Object> o : Sql.query(c, "select o.id from app.onboarding o join app.employment e on e.id = o.employment_id "
                + "where o.status in ('draft','invited','in_progress') and e.joining_date <= ?", today)) {
            Loaded l = load(c, AuthService.uuid(o.get("id")));
            if (dueForActivation(c, l, settings, today)) {
                activateNow(c, tenantId, l, null);
            }
        }
    }

    private static boolean dueForActivation(Connection c, Loaded l, Map<String, Object> settings, LocalDate today) throws SQLException {
        if (l.joiningDate().isAfter(today)) {
            return false;
        }
        boolean automatic = "automatic".equals(settings.get("activation"));
        if (!automatic && l.row().get("activationRequestedAt") == null) {
            return false;
        }
        return blockers(c, l).isEmpty();
    }

    /**
     * The joiner becomes an employee: employment and onboarding active, the Joiner role swapped for
     * Employee, the employee code and joining date carried to the login (so leave accrues from that
     * date), and attendance assigned from the joining date with the location, shift and offs HR set.
     */
    private static void activateNow(Connection c, UUID tenantId, Loaded l, SessionCtx ctx) throws SQLException {
        // Claim the onboarding first, conditionally: two requests arriving together (a page load fires several)
        // both see it due; the second waits on the row lock, then finds it active and does nothing, so the
        // joiner is activated, numbered and told only once.
        int claimed = Sql.update(c, "update app.onboarding set status = 'active', activated_at = now() "
                + "where id = ? and status in ('draft','invited','in_progress','offered')", l.id());
        if (claimed == 0) {
            return;
        }
        Sql.update(c, "update app.employment set status = 'active', activated_at = now() where id = ?", l.employmentId());
        ChecklistService.schedule(c, l.id());
        UUID membership = l.membershipId();
        if (membership == null) {
            Map<String, Object> m = Sql.one(c, "select id from app.membership where person_id = ? and status = 'active'", l.personId());
            membership = m == null ? null : AuthService.uuid(m.get("id"));
        }
        Map<String, Object> e = Sql.one(c, "select employee_code from app.employment where id = ?", l.employmentId());
        if (e.get("employeeCode") == null) {
            // No code was typed: the series that matches the joiner gives the next one.
            String code = NumberSeries.draw(c, l);
            if (code != null) {
                Sql.update(c, "update app.employment set employee_code = ? where id = ?", code, l.employmentId());
                e = Sql.one(c, "select employee_code from app.employment where id = ?", l.employmentId());
            }
        }
        if (membership != null) {
            Sql.update(c, "update app.employment set membership_id = ? where id = ?", membership, l.employmentId());
            Sql.update(c, "delete from app.membership_role mr using app.role r where r.id = mr.role_id and mr.membership_id = ? "
                    + "and r.code = 'joiner'", membership);
            Sql.update(c, "insert into app.membership_role (tenant_id, membership_id, role_id) select ?, ?, r.id from app.role r "
                    + "where r.code = 'employee' and r.tenant_id is null "
                    + "and not exists (select 1 from app.membership_role x where x.membership_id = ? and x.role_id = r.id)",
                    tenantId, membership, membership);
            Sql.update(c, "update app.membership set joined_at = (?::date)::timestamp at time zone 'Asia/Kolkata', "
                    + "employee_code = coalesce(?, employee_code) where id = ?", l.joiningDate(), e.get("employeeCode"), membership);
            Map<String, Object> o = Sql.one(c, "select work_location_id, shift_id, weekly_offs from app.onboarding where id = ?", l.id());
            if (o.get("workLocationId") != null && o.get("shiftId") != null) {
                Sql.update(c, "insert into app.attendance_assignment (tenant_id, membership_id, work_location_id, shift_id, weekly_offs, "
                        + "effective_from, created_by) values (?, ?, ?::uuid, ?::uuid, (select weekly_offs from app.onboarding where id = ?), ?, ?)",
                        tenantId, membership, o.get("workLocationId"), o.get("shiftId"), l.id(), l.joiningDate(),
                        ctx == null ? null : ctx.membershipId());
            }
        }
        Sql.update(c, "insert into app.audit_event (tenant_id, actor_kind, actor_membership_id, actor_label, action, target_type, target_id, detail) "
                + "values (?, ?, ?, ?, 'joiner.activated', 'onboarding', ?, ?::jsonb)", tenantId,
                ctx == null ? "system" : ctx.actorKind(), ctx == null ? null : ctx.membershipId(), ctx == null ? "system" : ctx.email(),
                l.id(), Json.object(Map.of("joiningDate", l.joiningDate().toString(), "login", membership == null ? "none" : "linked",
                        "employeeCode", e.get("employeeCode") == null ? "" : e.get("employeeCode"))));
        Loaded now = load(c, l.id());
        Notifications.aboutJoiner(c, now, membership, "activated", null, null);
        Map<String, Object> boss = Sql.one(c, "select boss.membership_id from app.job_assignment j join app.employment boss on boss.id = j.manager_id "
                + "where j.employment_id = ? order by j.effective_from desc, j.created_at desc limit 1", l.employmentId());
        if (boss != null) {
            Notifications.aboutJoiner(c, now, AuthService.uuid(boss.get("membershipId")), "welcome_team", null, null);
        }
    }

    /** Tells whoever added the joiner, once, when nothing blocks activation any more. */
    static void noteIfReady(Connection c, UUID onboardingId) throws SQLException {
        Loaded l = load(c, onboardingId);
        if (!l.open() || l.row().get("readyNotifiedAt") != null || !blockers(c, l).isEmpty()) {
            return;
        }
        Sql.update(c, "update app.onboarding set ready_notified_at = now() where id = ?", onboardingId);
        Notifications.aboutJoiner(c, l, AuthService.uuid(l.row().get("createdBy")), "joiner_ready", null, "people");
    }

    // ── blockers and status ─────────────────────────────────────────────

    /** What stops activation, in words. Empty when the joiner is ready. */
    private static List<String> blockers(Connection c, Loaded l) throws SQLException {
        List<String> out = new ArrayList<>();
        for (Map<String, Object> s : sectionRows(c, l)) {
            String status = (String) s.get("status");
            if (!"verified".equals(status) && !s.get("section").equals("documents")) {
                String who = switch (status) {
                    case "submitted" -> "waiting to be verified";
                    case "returned" -> "returned for a correction";
                    default -> s.get("section").equals("employment") ? "HR to complete" : "waiting for the joiner";
                };
                out.add(s.get("title") + ": " + who);
            }
        }
        out.addAll(DocumentService.blockers(c, l));
        out.addAll(ChecklistService.blockers(c, l));
        out.addAll(LetterService.blockers(c, l));
        out.addAll(PolicyService.blockers(c, l));
        Map<String, Object> settings = settings(c);
        if (!Boolean.TRUE.equals(settings.get("allowActivationWithoutPan"))
                && Sql.one(c, "select 1 from app.statutory_id where person_id = ? and kind = 'pan' and replaced_at is null "
                        + "and pending_reason is null", l.personId()) == null) {
            out.add("PAN: the Organization does not activate anyone without one");
        }
        return out;
    }

    private static String waitingOn(Connection c, Loaded l) throws SQLException {
        boolean hr = false;
        boolean joiner = false;
        for (Map<String, Object> s : sectionRows(c, l)) {
            switch ((String) s.get("status")) {
                case "submitted" -> hr = true;
                case "verified" -> { }
                default -> {
                    if (s.get("section").equals("employment")) {
                        hr = true;
                    } else {
                        joiner = true;
                    }
                }
            }
        }
        if (!LetterService.blockers(c, l).isEmpty() || !PolicyService.blockers(c, l).isEmpty()) {
            joiner = true;   // a letter to answer or a policy to acknowledge
        }
        if (!joiner && !hr && !ChecklistService.blockers(c, l).isEmpty()) {
            hr = true;   // only tasks are left
        }
        return joiner ? "joiner" : hr ? "hr" : "nobody";
    }

    private void submit(Connection c, SessionCtx ctx, Loaded l, String section) throws SQLException {
        refuseDocuments(section);
        List<String> missing = missing(c, l, section);
        if (!missing.isEmpty()) {
            throw new ApiException(400, "onboarding.missing", "Still needed: " + String.join(", ", missing));
        }
        Map<String, Object> s = Sql.one(c, "select status from app.onboarding_section where onboarding_id = ? and section = ? for update",
                l.id(), section);
        if ("verified".equals(s.get("status"))) {
            throw new ApiException(409, "onboarding.verified", "This section is already verified");
        }
        boolean verified = levelsFor(c, l, section) == 0;
        Sql.update(c, "update app.onboarding_section set status = ?, level = 0, note = null, submitted_by = ?, submitted_at = now() "
                + "where onboarding_id = ? and section = ?", verified ? "verified" : "submitted",
                ctx.membershipId() != null ? ctx.membershipId() : l.membershipId(), l.id(), section);
        audit(c, ctx, "joiner.section_submitted", l.id(), Map.of("section", section));
    }

    /** Required fields with no value; the employee code is not needed when a series will give one at activation. */
    private List<String> missing(Connection c, Loaded l, String section) throws SQLException {
        List<Map<String, Object>> fields = fieldsOf(c, l, section);
        if (section.equals("employment") && NumberSeries.applies(c, subject(c, l))) {
            fields = fields.stream().filter(f -> !"employment.employee_code".equals(f.get("key"))).toList();
        }
        return sections.missing(c, l.joiner(), section, fields);
    }

    /** How many levels verify this section: the flow's levels, or none when the section is set not to be verified. */
    private static int levelsFor(Connection c, Loaded l, String section) throws SQLException {
        Map<String, Object> s = Sql.one(c, "select ss.verify, f.verification_levels from app.onboarding_flow f "
                + "left join app.section_setting ss on ss.flow_id = f.id and ss.section = ? where f.id = ?", section, l.flowId());
        if (s == null || Boolean.FALSE.equals(s.get("verify"))) {
            return 0;
        }
        return ((Number) s.get("verificationLevels")).intValue();
    }

    /**
     * Whether the caller verifies this section at this level: named in a step for the level
     * (by role, as the named user, or as the joiner's reporting manager), or the Org Admin.
     */
    static boolean canVerify(Connection c, SessionCtx ctx, Loaded l, String section, int level) throws SQLException {
        if (ctx.membershipId() == null || ctx.membershipId().equals(l.membershipId())) {
            return false;
        }
        if (hasRole(c, ctx.membershipId(), "owner")) {
            return true;
        }
        for (Map<String, Object> step : Sql.query(c, "select s.verifier, r.code as role_code, s.membership_id from app.verification_step s "
                + "left join app.role r on r.id = s.role_id where s.flow_id = ? and s.level = ? and (s.section is null or s.section = ?)",
                l.flowId(), (short) level, section)) {
            switch ((String) step.get("verifier")) {
                case "role" -> {
                    if (hasRole(c, ctx.membershipId(), (String) step.get("roleCode"))) {
                        return true;
                    }
                }
                case "user" -> {
                    if (ctx.membershipId().toString().equals(step.get("membershipId"))) {
                        return true;
                    }
                }
                default -> {
                    if (isManager(c, ctx.membershipId(), l)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    static boolean hasRole(Connection c, UUID membershipId, String code) throws SQLException {
        return Sql.one(c, "select 1 from app.membership_role mr join app.role r on r.id = mr.role_id join app.membership m on m.id = mr.membership_id "
                + "where mr.membership_id = ? and r.code = ? and m.status = 'active'", membershipId, code) != null;
    }

    static boolean isManager(Connection c, UUID membershipId, Loaded l) throws SQLException {
        return Sql.one(c, "select 1 from app.job_assignment j join app.employment boss on boss.id = j.manager_id "
                + "where j.id = (select id from app.job_assignment where employment_id = ? order by effective_from desc, created_at desc limit 1) "
                + "and boss.membership_id = ?", l.employmentId(), membershipId) != null;
    }

    private static boolean joinerCanEdit(Loaded l, String section) {
        if (!l.open() || "offered".equals(l.row().get("status")) || section.equals("employment") || l.row().get("noticeId") == null) {
            return false;
        }
        String status = (String) l.sections().get(section).get("status");
        return Set.of("not_started", "draft", "returned").contains(status);
    }

    // ── loading ─────────────────────────────────────────────────────────

    record Loaded(UUID id, UUID personId, UUID employmentId, UUID flowId, UUID membershipId, LocalDate joiningDate,
                          Map<String, Object> row, Map<String, Map<String, Object>> sections, UUID tenantId) {

        boolean open() {
            return Set.of("draft", "offered", "invited", "in_progress").contains((String) row.get("status"));
        }

        JoinerSections.Joiner joiner() {
            return new JoinerSections.Joiner(tenantId, id, personId, employmentId, joiningDate);
        }
    }

    static Loaded load(Connection c, UUID onboardingId) throws SQLException {
        Map<String, Object> o = Sql.one(c, "select o.*, e.joining_date, e.membership_id as employment_membership_id, "
                + "(select m.id from app.membership m where m.person_id = o.person_id and m.status <> 'ended' limit 1) as person_membership_id "
                + "from app.onboarding o join app.employment e on e.id = o.employment_id where o.id = ?", onboardingId);
        if (o == null) {
            throw ApiException.notFound("The joiner");
        }
        Map<String, Map<String, Object>> sections = new LinkedHashMap<>();
        for (Map<String, Object> s : Sql.query(c, "select os.section, os.status, os.level, os.note from app.onboarding_section os "
                + "where os.onboarding_id = ?", onboardingId)) {
            sections.put((String) s.get("section"), s);
        }
        UUID membership = AuthService.uuid(o.get("employmentMembershipId") != null ? o.get("employmentMembershipId") : o.get("personMembershipId"));
        if (membership != null && o.get("employmentMembershipId") == null) {
            // The joiner accepted the invitation: their login now belongs to this employment.
            Sql.update(c, "update app.employment set membership_id = ? where id = ?::uuid", membership, o.get("employmentId"));
            Sql.update(c, "update app.onboarding_task set assignee_id = ? where onboarding_id = ? and owner = 'joiner' and assignee_id is null",
                    membership, onboardingId);
            if ("invited".equals(o.get("status")) || "draft".equals(o.get("status"))) {
                Sql.update(c, "update app.onboarding set invited_at = coalesce(invited_at, now()), "
                        + "status = case when status = 'draft' then 'invited' else status end where id = ?", onboardingId);
            }
        }
        return new Loaded(onboardingId, AuthService.uuid(o.get("personId")), AuthService.uuid(o.get("employmentId")),
                AuthService.uuid(o.get("flowId")), membership, LocalDate.parse((String) o.get("joiningDate")), o, sections,
                AuthService.uuid(o.get("tenantId")));
    }

    static Loaded mineLoaded(Connection c, SessionCtx ctx) throws SQLException {
        if (ctx.membershipId() == null) {
            throw ApiException.notFound("Your onboarding");
        }
        Map<String, Object> o = Sql.one(c, "select o.id from app.onboarding o join app.membership m on m.person_id = o.person_id "
                + "where m.id = ? order by (o.status in ('draft','offered','invited','in_progress')) desc, o.created_at desc limit 1",
                ctx.membershipId());
        if (o == null) {
            throw ApiException.notFound("Your onboarding");
        }
        return load(c, AuthService.uuid(o.get("id")));
    }

    /** The joiner's sections in the flow's order, with titles. */
    private static List<Map<String, Object>> sectionRows(Connection c, Loaded l) throws SQLException {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> s : Sql.query(c, "select os.section, os.status, os.level, os.note, os.submitted_at, ss.title, ss.position "
                + "from app.onboarding_section os left join app.section_setting ss on ss.flow_id = ? and ss.section = os.section "
                + "where os.onboarding_id = ? order by ss.position nulls last, os.section", l.flowId(), l.id())) {
            Map<String, Object> m = new LinkedHashMap<>(s);
            if (m.get("title") == null) {
                m.put("title", FieldCatalog.SECTION_TITLES.get((String) s.get("section")));
            }
            m.put("levels", levelsFor(c, l, (String) s.get("section")));
            out.add(m);
        }
        return out;
    }

    private Map<String, Object> sectionView(Connection c, Loaded l, String section) throws SQLException {
        Map<String, Object> form = formSection(c, l, section);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("section", section);
        out.put("title", form.get("title"));
        out.put("intro", form.get("intro"));
        out.put("fields", form.get("fields"));
        out.putAll(sections.read(c, l.joiner(), section));
        Map<String, Object> s = l.sections().get(section);
        out.put("status", s.get("status"));
        out.put("level", s.get("level"));
        out.put("levels", levelsFor(c, l, section));
        out.put("note", s.get("note"));
        out.put("employmentId", l.employmentId().toString());
        return out;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> formSection(Connection c, Loaded l, String section) throws SQLException {
        for (Map<String, Object> s : OnboardingConfigService.form(c, l.flowId(), subject(c, l))) {
            if (s.get("section").equals(section)) {
                List<Map<String, Object>> fields = new ArrayList<>();
                for (Map<String, Object> f : (List<Map<String, Object>>) s.get("fields")) {
                    if (!FieldCatalog.LATER_TYPES.contains(f.get("type"))) {
                        fields.add(f);
                    }
                }
                Map<String, Object> out = new LinkedHashMap<>(s);
                out.put("fields", fields);
                return out;
            }
        }
        // The flow switched the section off after this joiner was added: it keeps its built-in title and no fields.
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("section", section);
        out.put("title", FieldCatalog.SECTION_TITLES.get(section));
        out.put("fields", List.of());
        return out;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> fieldsOf(Connection c, Loaded l, String section) throws SQLException {
        return (List<Map<String, Object>>) formSection(c, l, section).get("fields");
    }

    private static Set<String> allKeys(Connection c, Loaded l, String section) throws SQLException {
        Set<String> out = new HashSet<>();
        for (Map<String, Object> f : fieldsOf(c, l, section)) {
            out.add((String) f.get("key"));
        }
        if (section.equals("employment")) {
            // HR can always set these, whatever the form shows, since activation depends on them.
            out.addAll(List.of("employment.designation", "employment.department", "employment.manager", "employment.work_location",
                    "employment.shift", "employment.weekly_offs", "employment.employee_code", "employment.joining_date",
                    "employment.employment_type", "employment.probation_end", "employment.contract_end", "employment.official_email"));
        }
        return out;
    }

    private static Set<String> joinerKeys(Connection c, Loaded l, String section) throws SQLException {
        Set<String> out = new HashSet<>();
        for (Map<String, Object> f : fieldsOf(c, l, section)) {
            if ("joiner".equals(f.get("editableBy"))) {
                out.add((String) f.get("key"));
            }
        }
        return out;
    }

    static AppliesTo.Subject subject(Connection c, Loaded l) throws SQLException {
        Map<String, Object> s = Sql.one(c, "select e.employment_type, o.work_location_id, j.department_id, j.designation "
                + "from app.onboarding o join app.employment e on e.id = o.employment_id "
                + "left join lateral (select * from app.job_assignment j where j.employment_id = e.id "
                + "  order by j.effective_from desc, j.created_at desc limit 1) j on true where o.id = ?", l.id());
        return new AppliesTo.Subject(AuthService.uuid(s.get("departmentId")), AuthService.uuid(s.get("workLocationId")),
                (String) s.get("employmentType"), (String) s.get("designation"));
    }

    static Map<String, Object> settings(Connection c) throws SQLException {
        return Sql.one(c, "select * from app.onboarding_settings");
    }

    // ── invitation, rehire, retention ───────────────────────────────────

    /** Creates the joiner's invitation, or a fresh link for the one already pending. The token is returned once. */
    private Map<String, Object> invitation(Connection c, SessionCtx ctx, Loaded l, Map<String, Object> settings) throws SQLException {
        Map<String, Object> p = Sql.one(c, "select personal_email from app.person where id = ?", l.personId());
        String email = (String) p.get("personalEmail");
        if (email == null) {
            throw ApiException.badRequest("Add the joiner's personal email first");
        }
        if (l.membershipId() != null) {
            throw new ApiException(409, "onboarding.has_login", "The joiner has already accepted an invitation and can sign in");
        }
        Map<String, Object> member = Sql.one(c, "select m.id from app.membership m join app.member_directory() d on d.membership_id = m.id "
                + "where d.email = ?::citext and m.status <> 'ended'", email);
        if (member != null) {
            throw new ApiException(409, "membership.exists", "Someone with this email is already a user of the Organization");
        }
        int days = ((Number) settings.get("linkValidDays")).intValue();
        // The link stays valid at least until a week after joining, so a joiner added early can still use it.
        long untilJoining = ChronoUnit.DAYS.between(today(), l.joiningDate().plusDays(7));
        int valid = (int) Math.max(days, Math.min(untilJoining, 120));
        String token = Secrets.newToken();
        Instant expires = Instant.now(clock).plus(valid, ChronoUnit.DAYS);
        Map<String, Object> pending = Sql.one(c, "select id from app.invitation where tenant_id = ? and email = ?::citext "
                + "and accepted_at is null and revoked_at is null", l.tenantId(), email);
        if (pending != null) {
            Sql.update(c, "update app.invitation set token_hash = ?, expires_at = ?, person_id = ?, "
                    + "role_ids = array(select id from app.role where code = 'joiner' and tenant_id is null) where id = ?::uuid",
                    Secrets.sha256(token), Timestamp.from(expires), l.personId(), pending.get("id"));
        } else {
            Sql.update(c, "insert into app.invitation (tenant_id, email, person_id, role_ids, token_hash, invited_by, expires_at) "
                    + "select ?, ?::citext, ?, array[r.id], ?, ?, ? from app.role r where r.code = 'joiner' and r.tenant_id is null",
                    l.tenantId(), email, l.personId(), Secrets.sha256(token), ctx == null ? null : ctx.membershipId(), Timestamp.from(expires));
        }
        Sql.update(c, "update app.onboarding set status = case when status = 'draft' then 'invited' else status end, "
                + "invited_at = now() where id = ?", l.id());
        ChecklistService.schedule(c, l.id());
        if (ctx != null) {
            audit(c, ctx, "joiner.invited", l.id(), Map.of("email", email, "validDays", valid));
        } else {
            Sql.update(c, "insert into app.audit_event (tenant_id, actor_kind, actor_label, action, target_type, target_id, detail) "
                    + "values (?, 'system', 'system', 'joiner.invited', 'onboarding', ?, ?::jsonb)", l.tenantId(), l.id(),
                    Json.object(Map.of("email", email, "validDays", valid, "scheduled", "true")));
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("token", token);
        out.put("expiresAt", expires.toString());
        out.put("email", email);
        return out;
    }

    /** Earlier people of this Organization who match the joiner on the fields the Organization chose. */
    private static List<Map<String, Object>> rehireCandidates(Connection c, Map<String, Object> settings, String email, String mobile)
            throws SQLException {
        @SuppressWarnings("unchecked")
        List<String> match = (List<String>) settings.get("rehireMatch");
        List<String> conditions = new ArrayList<>();
        List<Object> params = new ArrayList<>();
        if (match.contains("personal_email") && email != null) {
            conditions.add("p.personal_email = ?::citext");
            params.add(email);
        }
        if (match.contains("mobile") && mobile != null) {
            conditions.add("p.mobile = ?");
            params.add(mobile);
        }
        if (conditions.isEmpty()) {
            return List.of();
        }
        return Sql.query(c, "select p.id as person_id, p.legal_name, p.personal_email, p.mobile, "
                + "(select e.status from app.employment e where e.person_id = p.id order by e.joining_date desc limit 1) as last_status, "
                + "(select e.joining_date from app.employment e where e.person_id = p.id order by e.joining_date desc limit 1) as last_joined, "
                + "(select e.exit_date from app.employment e where e.person_id = p.id order by e.joining_date desc limit 1) as last_exit "
                + "from app.person p where " + String.join(" or ", conditions) + " order by p.created_at", params.toArray());
    }

    /**
     * Deletes the data of joiners withdrawn longer ago than the Organization keeps it, unless the
     * person also has an employment that went ahead. Runs on the owner connection, scoped to this
     * Organization, since the application role may not delete these records. Logged without values.
     */
    private void purgeWithdrawn(UUID tenantId) {
        db.inOwner(c -> {
            List<Map<String, Object>> people = Sql.query(c,
                    "select distinct o.person_id from app.onboarding o join app.onboarding_settings s on s.tenant_id = o.tenant_id "
                            + "where o.tenant_id = ? and o.status in ('withdrawn','offer_declined') "
                            + "and coalesce(o.withdrawn_at, o.created_at) < now() - make_interval(days => s.withdrawn_retention_days) "
                            + "and not exists (select 1 from app.employment e where e.person_id = o.person_id "
                            + "  and e.status in ('onboarding','active','exited'))", tenantId);
            for (Map<String, Object> row : people) {
                UUID person = AuthService.uuid(row.get("personId"));
                String[] deletes = {
                    "delete from app.custom_field_value where person_id = ?",
                    "delete from app.statutory_id where person_id = ?",
                    "delete from app.bank_account where person_id = ?",
                    "delete from app.person_address where person_id = ?",
                    "delete from app.emergency_contact where person_id = ?",
                    "delete from app.nomination where person_id = ?",
                    "delete from app.family_member where person_id = ?",
                    "delete from app.tax_regime_choice where person_id = ?",
                    "delete from app.section_review where onboarding_id in (select id from app.onboarding where person_id = ?)",
                    "delete from app.onboarding_section where onboarding_id in (select id from app.onboarding where person_id = ?)",
                    "delete from app.onboarding_task where onboarding_id in (select id from app.onboarding where person_id = ?)",
                    "delete from app.issued_letter where person_id = ?",
                    "delete from app.policy_acknowledgement where person_id = ?",
                    "update app.person set photo_document_id = null where id = ?",
                    "delete from app.document_content where document_id in (select id from app.document where person_id = ?)",
                    "delete from app.document where person_id = ?",
                    "delete from app.onboarding where person_id = ?",
                    "delete from app.job_assignment where employment_id in (select id from app.employment where person_id = ?)",
                    "delete from app.employment where person_id = ?",
                    "update app.membership set person_id = null where person_id = ?",
                    "update app.invitation set person_id = null where person_id = ?",
                    "delete from app.person where id = ?",
                };
                for (String sql : deletes) {
                    Sql.update(c, sql, person);
                }
                Sql.update(c, "insert into app.audit_event (tenant_id, actor_kind, actor_label, action, target_type, target_id, detail) "
                        + "values (?, 'system', 'system', 'joiner.deleted', 'person', ?, '{\"reason\":\"retention period passed after withdrawal\"}'::jsonb)",
                        tenantId, person);
            }
            return null;
        });
    }

    // ── permissions and helpers ─────────────────────────────────────────

    private static final String LIST_SELECT =
            "select o.id, o.status, o.person_id, o.employment_id, o.invited_at, o.notice_accepted_at, o.activation_requested_at, "
                    + "o.activated_at, o.withdrawn_at, o.withdrawn_reason, o.created_at, p.legal_name, p.preferred_name, p.personal_email, "
                    + "p.mobile, e.joining_date, e.employment_type, e.employee_code, j.designation, d.name as department_name, "
                    + "mp.legal_name as manager_name, wl.name as work_location, f.name as flow_name, "
                    + "(select count(*) from app.onboarding_section s where s.onboarding_id = o.id and s.status = 'verified') as verified, "
                    + "(select count(*) from app.onboarding_section s where s.onboarding_id = o.id) as section_count, "
                    + "(select count(*) from app.onboarding_task k where k.onboarding_id = o.id and k.done_at is not null) as tasks_done, "
                    + "(select count(*) from app.onboarding_task k where k.onboarding_id = o.id) as task_count, "
                    + "(select count(*) from app.onboarding_task k where k.onboarding_id = o.id and k.done_at is null "
                    + "  and k.due_date < ?::date) as overdue_tasks "
                    + "from app.onboarding o join app.person p on p.id = o.person_id join app.employment e on e.id = o.employment_id "
                    + "join app.onboarding_flow f on f.id = o.flow_id "
                    + "left join lateral (select * from app.job_assignment j where j.employment_id = e.id "
                    + "  and j.effective_from <= greatest(?::date, e.joining_date) order by j.effective_from desc, j.created_at desc limit 1) j on true "
                    + "left join app.department d on d.id = j.department_id "
                    + "left join app.employment me on me.id = j.manager_id left join app.person mp on mp.id = me.person_id "
                    + "left join app.work_location wl on wl.id = o.work_location_id";

    private static void requireHrRead(SessionCtx ctx) {
        if (!ctx.reachesEveryone("employee.read")) {
            throw ApiException.denied("employee.read");
        }
    }

    private static void requireHrWrite(SessionCtx ctx) {
        ctx.require("employee.write");
        if (ctx.membershipId() == null) {
            throw new ApiException(403, "permission.denied", "Only a user of the Organization can do this");
        }
    }

    /** HR sees every joiner; a verifier or the joiner's manager sees theirs. Anyone else is told the joiner does not exist. */
    static void requireSee(Connection c, SessionCtx ctx, Loaded l) throws SQLException {
        if (ctx.reachesEveryone("employee.read")) {
            return;
        }
        if (ctx.membershipId() != null && !ctx.membershipId().equals(l.membershipId())) {
            if (isManager(c, ctx.membershipId(), l)) {
                return;
            }
            for (int level = 1; level <= 3; level++) {
                for (String section : l.sections().keySet()) {
                    if (canVerify(c, ctx, l, section, level)) {
                        return;
                    }
                }
            }
        }
        throw ApiException.notFound("The joiner");
    }

    private static void refuseDocuments(String section) {
        if ("documents".equals(section)) {
            throw new ApiException(409, "onboarding.documents", "Documents are sent and checked one at a time, not as a section");
        }
    }

    static void requireOpen(Loaded l) {
        if (!l.open()) {
            throw new ApiException(409, "onboarding.closed", "This onboarding is " + l.row().get("status") + " and can no longer change");
        }
    }

    private static void requireSection(Loaded l, String section) {
        if (section == null || !l.sections().containsKey(section)) {
            throw ApiException.notFound("That section");
        }
    }

    private static void audit(Connection c, SessionCtx ctx, String action, UUID onboardingId, Map<String, ?> detail) throws SQLException {
        UserService.audit(c, ctx, action, "onboarding", onboardingId, detail);
    }

    private static Map<String, Object> detail(Object... pairs) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            if (pairs[i + 1] != null) {
                out.put((String) pairs[i], pairs[i + 1]);
            }
        }
        return out;
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }

    private static String required(String value, String what, int max) {
        if (blank(value)) {
            throw ApiException.badRequest("Enter " + what);
        }
        String v = value.trim();
        if (v.length() > max) {
            throw ApiException.badRequest("Shorten " + what + " to " + max + " characters");
        }
        return v;
    }

    private static LocalDate date(String value, String what) {
        if (blank(value)) {
            return null;
        }
        try {
            return LocalDate.parse(value.trim());
        } catch (DateTimeParseException e) {
            throw ApiException.badRequest("The " + what + " must be a date like 2026-10-12");
        }
    }

    private static UUID id(String value, String what) {
        try {
            return UUID.fromString(value.trim());
        } catch (IllegalArgumentException e) {
            throw ApiException.badRequest("Not a valid " + what + " id");
        }
    }
}
