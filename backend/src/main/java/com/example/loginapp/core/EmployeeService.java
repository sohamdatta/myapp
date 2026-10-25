package com.example.loginapp.core;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * The employee record: the person, their employment, dated job assignments
 * (designation, department, reporting manager), departments, statutory
 * identifiers and bank accounts. Full identifiers are encrypted before they
 * reach the database; responses carry the last four characters, and the full
 * value comes only from {@link #reveal}, which is audited.
 */
public final class EmployeeService {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final Set<String> TYPES = Set.of("permanent", "probation", "fixed_term", "intern", "consultant");
    private static final Set<String> GENDERS = Set.of("female", "male", "transgender", "undisclosed");
    /** Statutory identifiers whose full value is kept (encrypted) and can be revealed. */
    private static final Set<String> REVEALABLE = Set.of("pan", "uan", "pf_member", "esi_ip", "bank_account");
    private static final int MAX_CHAIN = 100;

    public record NewEmployee(
            String legalName, String preferredName, String dateOfBirth, String gender,
            String personalEmail, String mobile,
            String employmentType, String joiningDate, String probationEnd, String contractEnd, String employeeCode,
            String designation, String departmentId, String managerId, String membershipId,
            String pan, String panPendingReason, String aadhaar, String uan,
            String bankHolder, String bankAccount, String ifsc, String bankName, String branch) {}

    public record JobChange(String effectiveFrom, String designation, String departmentId, String managerId, String reason) {}

    private final Db db;
    private final Crypto crypto;
    private final Clock clock;

    public EmployeeService(Db db, Crypto crypto) {
        this(db, crypto, Clock.systemUTC());
    }

    public EmployeeService(Db db, Crypto crypto, Clock clock) {
        this.db = db;
        this.crypto = crypto;
        this.clock = clock;
    }

    private LocalDate today() {
        return LocalDate.now(clock.withZone(IST));
    }

    // ── reading ─────────────────────────────────────────────────────────

    /** Employees the caller can see. Status is active (the default), onboarding, exited or all. */
    public List<Map<String, Object>> list(SessionCtx ctx, String query, String status, String departmentId) {
        String scope = ctx.scopeOf("employee.read");
        if (scope == null) {
            throw ApiException.denied("employee.read");
        }
        LocalDate today = today();
        String wanted = status == null || status.isBlank() ? "active" : status;
        if (!Set.of("active", "onboarding", "exited", "all").contains(wanted)) {
            throw ApiException.badRequest("Status must be active, onboarding, exited or all");
        }
        UUID department = departmentId == null || departmentId.isBlank() ? null : id(departmentId, "department");
        String q = query == null || query.isBlank() ? null : "%" + query.trim().toLowerCase() + "%";
        return db.inTenant(ctx.tenantId(), c -> {
            applyDueExits(c, ctx.tenantId(), today);
            UUID[] reach = Team.reach(c, ctx, "employee.read", today);
            List<Object> params = new ArrayList<>(List.of(today));
            StringBuilder sql = new StringBuilder(EMPLOYEE_SELECT).append(" where true");
            if (!wanted.equals("all")) {
                sql.append(" and e.status = ?");
                params.add(wanted);
            }
            if (reach != null) {
                sql.append(" and (e.membership_id = any(?) or e.membership_id = ? or e.id = any(?))");
                params.add(reach);
                params.add(ctx.membershipId());
                params.add(Team.reportEmployments(c, ctx, "employee.read", today));
            }
            if (department != null) {
                sql.append(" and j.department_id = ?");
                params.add(department);
            }
            if (q != null) {
                sql.append(" and (lower(p.legal_name) like ? or lower(coalesce(p.preferred_name, '')) like ? "
                        + "or lower(coalesce(e.employee_code, '')) like ? or lower(coalesce(dir.email::text, '')) like ? "
                        + "or lower(coalesce(j.designation, '')) like ?)");
                for (int i = 0; i < 5; i++) {
                    params.add(q);
                }
            }
            sql.append(" order by p.legal_name, e.joining_date desc limit 500");
            return Sql.query(c, sql.toString(), params.toArray());
        });
    }

    /** One employee: the record, job history, and identifiers as last four characters only. */
    public Map<String, Object> get(SessionCtx ctx, UUID employmentId) {
        LocalDate today = today();
        return db.inTenant(ctx.tenantId(), c -> {
            applyDueExits(c, ctx.tenantId(), today);
            Map<String, Object> e = Sql.one(c,
                    EMPLOYEE_SELECT.replace("dir.email", "dir.email, p.date_of_birth, p.gender, p.marital_status, "
                            + "p.nationality, p.blood_group, p.alt_mobile, e.created_at")
                            + " where e.id = ?", today, employmentId);
            if (e == null || !canRead(c, ctx, e, today)) {
                throw ApiException.notFound("The employee");
            }
            UUID personId = AuthService.uuid(e.get("personId"));
            e.put("jobHistory", Sql.query(c,
                    "select j.id, j.effective_from, j.designation, j.department_id, d.name as department_name, "
                            + "j.manager_id, mp.legal_name as manager_name, j.reason, j.created_at, "
                            + "(select dir.email from app.member_directory() dir where dir.membership_id = j.created_by) as created_by_email "
                            + "from app.job_assignment j left join app.department d on d.id = j.department_id "
                            + "left join app.employment me on me.id = j.manager_id left join app.person mp on mp.id = me.person_id "
                            + "where j.employment_id = ? order by j.effective_from desc, j.created_at desc", employmentId));
            e.put("identifiers", Sql.query(c,
                    "select kind, last4, pending_reason, verified_at, created_at from app.statutory_id "
                            + "where person_id = ? and replaced_at is null order by kind", personId));
            e.put("bankAccount", Sql.one(c,
                    "select holder_name, account_last4, ifsc, bank_name, branch, effective_from, verified_at "
                            + "from app.bank_account where person_id = ? and effective_from <= greatest(?, ?::date) "
                            + "order by effective_from desc, created_at desc limit 1",
                    personId, today, e.get("joiningDate")));
            e.put("canReveal", canReveal(c, ctx, e, today));
            e.put("customFields", customFieldValues(c, ctx, e, today));
            e.put("canWrite", ctx.canAcrossOrganization("employee.write"));
            e.put("own", ctx.membershipId() != null && ctx.membershipId().toString().equals(e.get("membershipId")));
            return e;
        });
    }

    /**
     * The org chart: everyone working here today, with their designation, department, work email
     * and the employment they report to. Only directory details, so every employee may see it. A
     * manager who is no longer active, or a reporting line that would loop, makes the person a top
     * of the chart rather than losing them. Each person says whether the caller may open their record.
     */
    public Map<String, Object> orgChart(SessionCtx ctx) {
        if (!ctx.reachesEveryone("orgchart.read")) {
            throw ApiException.denied("orgchart.read");
        }
        LocalDate today = today();
        return db.inTenant(ctx.tenantId(), c -> {
            applyDueExits(c, ctx.tenantId(), today);
            List<Map<String, Object>> people = Sql.query(c,
                    "select e.id, coalesce(p.preferred_name, p.legal_name) as name, j.designation, d.name as department_name, "
                            + "case when boss.status = 'active' then j.manager_id end as manager_id, dir.email, e.membership_id "
                            + "from app.employment e join app.person p on p.id = e.person_id "
                            + "left join lateral (select * from app.job_assignment j where j.employment_id = e.id "
                            + "  and j.effective_from <= greatest(?::date, e.joining_date) "
                            + "  order by j.effective_from desc, j.created_at desc limit 1) j on true "
                            + "left join app.department d on d.id = j.department_id "
                            + "left join app.employment boss on boss.id = j.manager_id "
                            + "left join app.member_directory() dir on dir.membership_id = e.membership_id "
                            + "where e.status = 'active' order by lower(coalesce(p.preferred_name, p.legal_name)), e.id",
                    today);
            breakLoops(people);
            UUID[] reach = ctx.scopeOf("employee.read") == null ? new UUID[0] : Team.reach(c, ctx, "employee.read", today);
            Set<String> reachable = reach == null ? null : new HashSet<>();
            if (reach != null) {
                for (UUID m : reach) {
                    reachable.add(m.toString());
                }
            }
            String self = ctx.membershipId() == null ? null : ctx.membershipId().toString();
            String mine = null;
            for (Map<String, Object> person : people) {
                Object membership = person.remove("membershipId");
                boolean own = self != null && self.equals(membership);
                if (own) {
                    mine = (String) person.get("id");
                }
                person.put("canOpen", ctx.scopeOf("employee.read") != null
                        && (own || reachable == null || (membership != null && reachable.contains(membership))));
            }
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("people", people);
            out.put("me", mine);
            out.put("asOf", today.toString());
            return out;
        });
    }

    /** Clears the manager of anyone whose reporting line comes back to them, so the chart stays a tree. */
    static void breakLoops(List<Map<String, Object>> people) {
        Map<String, Map<String, Object>> byId = new HashMap<>();
        for (Map<String, Object> p : people) {
            byId.put((String) p.get("id"), p);
        }
        for (Map<String, Object> p : people) {
            if (p.get("managerId") != null && !byId.containsKey((String) p.get("managerId"))) {
                p.put("managerId", null);
            }
        }
        for (Map<String, Object> p : people) {
            Set<String> seen = new HashSet<>();
            Map<String, Object> at = p;
            while (at != null && at.get("managerId") != null) {
                if (!seen.add((String) at.get("id"))) {
                    at.put("managerId", null);
                    break;
                }
                at = byId.get((String) at.get("managerId"));
            }
        }
    }

    /** The full value of one identifier, for someone with employee.sensitive; every reveal is audited with its reason. */
    public Map<String, Object> reveal(SessionCtx ctx, UUID employmentId, String field, String reason) {
        boolean custom = field != null && field.startsWith("custom.");
        if (!custom && !REVEALABLE.contains(field == null ? "" : field)) {
            throw ApiException.badRequest("Only pan, uan, pf_member, esi_ip, bank_account and sensitive custom fields can be shown in full");
        }
        if (reason == null || reason.trim().length() < 5) {
            throw ApiException.badRequest("Say why you need the full value, in at least 5 characters");
        }
        LocalDate today = today();
        return db.inTenant(ctx.tenantId(), c -> {
            Map<String, Object> e = Sql.one(c, "select id, person_id, membership_id from app.employment where id = ?", employmentId);
            if (e == null || !canRead(c, ctx, e, today)) {
                throw ApiException.notFound("The employee");
            }
            if (!canReveal(c, ctx, e, today)) {
                throw ApiException.denied("employee.sensitive");
            }
            byte[] sealed = custom ? customBytes(c, field.substring("custom.".length()), AuthService.uuid(e.get("personId")))
                    : bytes(c, field, AuthService.uuid(e.get("personId")));
            if (sealed == null) {
                throw ApiException.notFound("That value");
            }
            String value = crypto.decryptText(ctx.tenantId(), sealed);
            UserService.audit(c, ctx, "employee.revealed", "employment", employmentId,
                    Map.of("field", field, "reason", reason.trim()));
            return Map.of("field", field, "value", custom ? String.valueOf(Json.parse(value)) : value);
        });
    }

    /** Users of the Organization with no employee record yet, to link when adding one. */
    public List<Map<String, Object>> unlinkedUsers(SessionCtx ctx) {
        ctx.require("employee.write");
        return db.inTenant(ctx.tenantId(), c -> Sql.query(c,
                "select m.id as membership_id, dir.email, m.employee_code from app.membership m "
                        + "join app.member_directory() dir on dir.membership_id = m.id "
                        + "where m.person_id is null and m.status = 'active' order by dir.email"));
    }

    // ── writing ─────────────────────────────────────────────────────────

    /** A new employee's details, checked and normalised, ready to be written. */
    record Checked(String legalName, String preferred, LocalDate dob, String gender, String email, String mobile, String type,
                   LocalDate joining, LocalDate probationEnd, LocalDate contractEnd, String code, String designation, UUID departmentId,
                   UUID managerId, UUID membershipId, String pan, String panPending, String aadhaarLast4, String uan, boolean bank,
                   String account, String ifsc, String holder, String bankName, String branch) {}

    /** Adds an employee who is already working here: HR entering existing staff, and the bulk import. */
    public Map<String, Object> add(SessionCtx ctx, NewEmployee in) {
        ctx.require("employee.write");
        Checked k = check(in);
        return db.inTenant(ctx.tenantId(), c -> insert(c, ctx, k));
    }

    /** Checks a new employee's details without touching the database; a problem is thrown as an ApiException. */
    static Checked check(NewEmployee in) {
        String legalName = required(in.legalName(), "the full name as on PAN", 120);
        String preferred = optional(in.preferredName(), 60);
        LocalDate dob = optionalDate(in.dateOfBirth(), "date of birth");
        String gender = optional(in.gender(), 20);
        if (gender != null && !GENDERS.contains(gender)) {
            throw ApiException.badRequest("Gender must be female, male, transgender or undisclosed");
        }
        String email = blank(in.personalEmail()) ? null : IdChecks.email(in.personalEmail());
        String mobile = blank(in.mobile()) ? null : IdChecks.mobile(in.mobile());
        String type = blank(in.employmentType()) ? "permanent" : in.employmentType().trim();
        if (!TYPES.contains(type)) {
            throw ApiException.badRequest("Employment type must be permanent, probation, fixed_term, intern or consultant");
        }
        LocalDate joining = requiredDate(in.joiningDate(), "joining date");
        LocalDate probationEnd = optionalDate(in.probationEnd(), "probation end");
        LocalDate contractEnd = optionalDate(in.contractEnd(), "contract end");
        if (type.equals("fixed_term") && contractEnd == null) {
            throw ApiException.badRequest("A fixed-term employment needs a contract end date");
        }
        if (dob != null && dob.plusYears(14).isAfter(joining)) {
            throw new ApiException(400, "employee.too_young", "The date of birth makes this person under 14 on the joining date");
        }
        String code = optional(in.employeeCode(), 30);
        String designation = required(in.designation(), "the designation", 80);
        UUID departmentId = blank(in.departmentId()) ? null : id(in.departmentId(), "department");
        UUID managerId = blank(in.managerId()) ? null : id(in.managerId(), "manager");
        UUID membershipId = blank(in.membershipId()) ? null : id(in.membershipId(), "user");
        String pan = blank(in.pan()) ? null : IdChecks.pan(in.pan());
        String panPending = optional(in.panPendingReason(), 200);
        String aadhaarLast4 = blank(in.aadhaar()) ? null : IdChecks.last4(IdChecks.aadhaar(in.aadhaar()));
        String uan = blank(in.uan()) ? null : IdChecks.uan(in.uan());
        boolean bank = !blank(in.bankAccount()) || !blank(in.ifsc());
        String account = bank ? IdChecks.bankAccount(in.bankAccount()) : null;
        String ifsc = bank ? IdChecks.ifsc(in.ifsc()) : null;
        String holder = bank ? (blank(in.bankHolder()) ? legalName : required(in.bankHolder(), "the account holder", 120)) : null;
        return new Checked(legalName, preferred, dob, gender, email, mobile, type, joining, probationEnd, contractEnd, code, designation,
                departmentId, managerId, membershipId, pan, panPending, aadhaarLast4, uan, bank, account, ifsc, holder,
                optional(in.bankName(), 80), optional(in.branch(), 80));
    }

    /** Writes a checked employee. */
    Map<String, Object> insert(Connection c, SessionCtx ctx, Checked k) throws SQLException {
        String legalName = k.legalName();
        String preferred = k.preferred();
        LocalDate dob = k.dob();
        String gender = k.gender();
        String email = k.email();
        String mobile = k.mobile();
        String type = k.type();
        LocalDate joining = k.joining();
        LocalDate probationEnd = k.probationEnd();
        LocalDate contractEnd = k.contractEnd();
        String code = k.code();
        String designation = k.designation();
        UUID departmentId = k.departmentId();
        UUID managerId = k.managerId();
        UUID membershipId = k.membershipId();
        String pan = k.pan();
        String panPending = k.panPending();
        String aadhaarLast4 = k.aadhaarLast4();
        String uan = k.uan();
        boolean bank = k.bank();
        String account = k.account();
        String ifsc = k.ifsc();
        String holder = k.holder();
        LocalDate today = today();

        String finalCode = code;
        if (membershipId != null) {
            Map<String, Object> m = Sql.one(c, "select id, person_id, status, employee_code from app.membership where id = ? for update",
                    membershipId);
            if (m == null) {
                throw ApiException.notFound("The user");
            }
            if (m.get("personId") != null) {
                throw new ApiException(409, "employee.user_linked", "That user already has an employee record");
            }
            if (finalCode == null && m.get("employeeCode") != null) {
                finalCode = (String) m.get("employeeCode");
            }
        }
        if (departmentId != null) {
            requireDepartment(c, departmentId);
        }
        if (managerId != null) {
            requireActiveManager(c, managerId);
        }
        UUID personId = AuthService.uuid(Sql.one(c,
                "insert into app.person (tenant_id, legal_name, preferred_name, date_of_birth, gender, personal_email, mobile) "
                        + "values (?, ?, ?, ?, ?, ?, ?) returning id",
                ctx.tenantId(), legalName, preferred, dob, gender, email, mobile).get("id"));
        UUID employmentId = AuthService.uuid(Sql.one(c,
                "insert into app.employment (tenant_id, person_id, membership_id, employee_code, employment_type, joining_date, "
                        + "probation_end, contract_end, status, activated_at, created_by) "
                        + "values (?, ?, ?, ?, ?, ?, ?, ?, 'active', now(), ?) returning id",
                ctx.tenantId(), personId, membershipId, finalCode, type, joining, probationEnd, contractEnd,
                ctx.membershipId()).get("id"));
        Sql.update(c, "insert into app.job_assignment (tenant_id, employment_id, effective_from, designation, department_id, "
                        + "manager_id, reason, created_by) values (?, ?, ?, ?, ?, ?, 'Joined', ?)",
                ctx.tenantId(), employmentId, joining, designation, departmentId, managerId, ctx.membershipId());
        if (membershipId != null) {
            Sql.update(c, "update app.membership set person_id = ?, employee_code = ? where id = ?",
                    personId, finalCode, membershipId);
        }
        if (pan != null) {
            insertIdentifier(c, ctx, personId, "pan", pan, null);
        } else if (panPending != null) {
            Sql.update(c, "insert into app.statutory_id (tenant_id, person_id, kind, value_enc, last4, pending_reason, created_by) "
                    + "values (?, ?, 'pan', ?, 'NONE', ?, ?)", ctx.tenantId(), personId,
                    crypto.encrypt(ctx.tenantId(), ""), panPending, ctx.membershipId());
        }
        if (aadhaarLast4 != null) {
            Sql.update(c, "insert into app.statutory_id (tenant_id, person_id, kind, last4, created_by) values (?, ?, 'aadhaar', ?, ?)",
                    ctx.tenantId(), personId, aadhaarLast4, ctx.membershipId());
        }
        if (uan != null) {
            insertIdentifier(c, ctx, personId, "uan", uan, null);
        }
        if (bank) {
            insertBank(c, ctx, personId, holder, account, ifsc, k.bankName(), k.branch(),
                    joining.isAfter(today) ? joining : today);
        }
        UserService.audit(c, ctx, "employee.added", "employment", employmentId,
                detail("name", legalName, "joiningDate", joining.toString(), "linkedUser", membershipId == null ? null : membershipId.toString()));
        return Map.of("id", employmentId.toString(), "personId", personId.toString());
    }

    /**
     * An employee who accepted a bulk invitation has a login for their person: the record is linked to
     * it, and the login takes the employee code. Their joining date is left alone, so leave is not
     * accrued again for years already worked. Runs on every request; does nothing once linked.
     */
    static void linkIfInvited(Connection c, UUID membershipId) throws SQLException {
        int linked = Sql.update(c, "update app.employment e set membership_id = m.id from app.membership m "
                + "where m.id = ? and m.person_id = e.person_id and e.membership_id is null and e.status = 'active'", membershipId);
        if (linked > 0) {
            Sql.update(c, "update app.membership m set employee_code = e.employee_code from app.employment e "
                    + "where m.id = ? and e.membership_id = m.id and e.status = 'active' and m.employee_code is null "
                    + "and e.employee_code is not null "
                    + "and not exists (select 1 from app.membership x where lower(x.employee_code) = lower(e.employee_code))", membershipId);
        }
    }

    /** Links an employee record to a user of the Organization, so their attendance and leave follow the record. */
    public void link(SessionCtx ctx, UUID employmentId, UUID membershipId) {
        ctx.require("employee.write");
        db.inTenant(ctx.tenantId(), c -> {
            Map<String, Object> e = lockEmployment(c, employmentId);
            if (e.get("membershipId") != null) {
                throw new ApiException(409, "employee.user_linked", "This employee is already linked to a user");
            }
            Map<String, Object> m = Sql.one(c, "select id, person_id, employee_code from app.membership where id = ? for update", membershipId);
            if (m == null) {
                throw ApiException.notFound("The user");
            }
            if (m.get("personId") != null) {
                throw new ApiException(409, "employee.user_linked", "That user already has an employee record");
            }
            String code = e.get("employeeCode") != null ? (String) e.get("employeeCode") : (String) m.get("employeeCode");
            Sql.update(c, "update app.employment set membership_id = ?, employee_code = ? where id = ?", membershipId, code, employmentId);
            Sql.update(c, "update app.membership set person_id = ?::uuid, employee_code = ? where id = ?", e.get("personId"), code, membershipId);
            UserService.audit(c, ctx, "employee.linked", "employment", employmentId, Map.of("membershipId", membershipId.toString()));
            return null;
        });
    }

    /** A dated change of designation, department or reporting manager. Earlier rows stay as history. */
    public void changeJob(SessionCtx ctx, UUID employmentId, JobChange in) {
        ctx.require("employee.write");
        LocalDate from = requiredDate(in.effectiveFrom(), "date the change takes effect");
        String designation = required(in.designation(), "the designation", 80);
        UUID departmentId = blank(in.departmentId()) ? null : id(in.departmentId(), "department");
        UUID managerId = blank(in.managerId()) ? null : id(in.managerId(), "manager");
        String reason = optional(in.reason(), 200);
        db.inTenant(ctx.tenantId(), c -> {
            changeJobIn(c, ctx, employmentId, from, designation, departmentId, managerId, reason);
            return null;
        });
    }

    /** A job change inside the caller's transaction; the caller has checked permission and input. */
    static UUID changeJobIn(Connection c, SessionCtx ctx, UUID employmentId, LocalDate from, String designation, UUID departmentId,
                            UUID managerId, String reason) throws SQLException {
            Map<String, Object> e = lockEmployment(c, employmentId);
            if (!"active".equals(e.get("status")) && !"onboarding".equals(e.get("status"))) {
                throw new ApiException(409, "employee.not_active", "This employee has left; their job can no longer change");
            }
            if (from.isBefore(LocalDate.parse((String) e.get("joiningDate")))) {
                throw ApiException.badRequest("A job change cannot take effect before the joining date");
            }
            if (departmentId != null) {
                requireDepartment(c, departmentId);
            }
            if (managerId != null) {
                if (managerId.equals(employmentId)) {
                    throw new ApiException(409, "employee.manager_loop", "Nobody can report to themselves");
                }
                requireActiveManager(c, managerId);
                checkReportingLoop(c, employmentId, managerId, from);
            }
            Map<String, Object> row = Sql.one(c, "insert into app.job_assignment (tenant_id, employment_id, effective_from, designation, department_id, "
                            + "manager_id, reason, created_by) values (?, ?, ?, ?, ?, ?, ?, ?) returning id",
                    ctx.tenantId(), employmentId, from, designation, departmentId, managerId, reason, ctx.membershipId());
            UserService.audit(c, ctx, "employee.job_changed", "employment", employmentId,
                    detail("effectiveFrom", from.toString(), "designation", designation,
                            "departmentId", departmentId == null ? null : departmentId.toString(),
                            "managerId", managerId == null ? null : managerId.toString(), "reason", reason));
            return AuthService.uuid(row.get("id"));
    }

    /**
     * Records that an employee leaves, as HR: an exit case accepted at once with this last working
     * day (see {@link ExitService}). Their access ends the day after it, when their roles are
     * replaced by Ex-employee, so they can still see their own records.
     */
    public void exit(SessionCtx ctx, UUID employmentId, String exitDate, String reason) {
        ctx.require("employee.write");
        String why = required(reason, "a reason", 200);
        requiredDate(exitDate, "last working day");
        new ExitService(db, crypto, clock).start(ctx, new ExitService.StartInput(employmentId.toString(), "resignation", "other", why,
                exitDate, null, null));
    }

    /** Sets or corrects a statutory identifier. A correction adds a row and marks the old one replaced. */
    public void setIdentifier(SessionCtx ctx, UUID employmentId, String kind, String value, String pendingReason) {
        ctx.require("employee.write");
        String k = kind == null ? "" : kind.trim();
        String clean = switch (k) {
            case "pan" -> blank(value) ? null : IdChecks.pan(value);
            case "aadhaar" -> blank(value) ? null : IdChecks.last4(IdChecks.aadhaar(value));
            case "uan" -> blank(value) ? null : IdChecks.uan(value);
            case "pf_member" -> blank(value) ? null : IdChecks.pfMember(value);
            case "esi_ip" -> blank(value) ? null : IdChecks.esiIp(value);
            default -> throw ApiException.badRequest("Kind must be pan, aadhaar, uan, pf_member or esi_ip");
        };
        String pending = optional(pendingReason, 200);
        if (clean == null && !(k.equals("pan") && pending != null)) {
            throw ApiException.badRequest(k.equals("pan") ? "Enter the PAN, or say why it is pending" : "Enter the value");
        }
        db.inTenant(ctx.tenantId(), c -> {
            Map<String, Object> e = lockEmployment(c, employmentId);
            UUID personId = AuthService.uuid(e.get("personId"));
            Sql.update(c, "update app.statutory_id set replaced_at = now() where person_id = ? and kind = ? and replaced_at is null",
                    personId, k);
            if (k.equals("aadhaar")) {
                Sql.update(c, "insert into app.statutory_id (tenant_id, person_id, kind, last4, created_by) values (?, ?, 'aadhaar', ?, ?)",
                        ctx.tenantId(), personId, clean, ctx.membershipId());
            } else if (clean == null) {
                Sql.update(c, "insert into app.statutory_id (tenant_id, person_id, kind, value_enc, last4, pending_reason, created_by) "
                        + "values (?, ?, 'pan', ?, 'NONE', ?, ?)", ctx.tenantId(), personId,
                        crypto.encrypt(ctx.tenantId(), ""), pending, ctx.membershipId());
            } else {
                insertIdentifier(c, ctx, personId, k, clean, null);
            }
            UserService.audit(c, ctx, "employee.identifier_set", "employment", employmentId,
                    detail("kind", k, "last4", clean == null ? null : IdChecks.last4(clean), "pendingReason", pending));
            return null;
        });
    }

    /** Records a new bank account from a date. The old one stays, so payroll knows which account a month was paid to. */
    public void setBank(SessionCtx ctx, UUID employmentId, String holder, String accountNumber, String ifscCode,
                        String bankName, String branch, String effectiveFrom) {
        ctx.require("employee.write");
        String account = IdChecks.bankAccount(accountNumber);
        String ifsc = IdChecks.ifsc(ifscCode);
        LocalDate from = blank(effectiveFrom) ? today() : requiredDate(effectiveFrom, "date it applies from");
        db.inTenant(ctx.tenantId(), c -> {
            Map<String, Object> e = lockEmployment(c, employmentId);
            String name = blank(holder) ? (String) Sql.one(c, "select legal_name from app.person where id = ?::uuid",
                    e.get("personId")).get("legalName") : required(holder, "the account holder", 120);
            insertBank(c, ctx, AuthService.uuid(e.get("personId")), name, account, ifsc, optional(bankName, 80), optional(branch, 80), from);
            UserService.audit(c, ctx, "employee.bank_set", "employment", employmentId,
                    Map.of("last4", IdChecks.last4(account), "ifsc", ifsc, "effectiveFrom", from.toString()));
            return null;
        });
    }

    // ── departments ─────────────────────────────────────────────────────

    public List<Map<String, Object>> departments(SessionCtx ctx) {
        if (ctx.scopeOf("employee.read") == null) {
            throw ApiException.denied("employee.read");
        }
        LocalDate today = today();
        return db.inTenant(ctx.tenantId(), c -> Sql.query(c,
                "select d.id, d.name, d.parent_id, p.name as parent_name, d.active, "
                        + "(select count(*) from app.employment e join lateral (select j.department_id from app.job_assignment j "
                        + "  where j.employment_id = e.id and j.effective_from <= greatest(?, e.joining_date) "
                        + "  order by j.effective_from desc, j.created_at desc limit 1) cur on true "
                        + "  where e.status = 'active' and cur.department_id = d.id) as headcount "
                        + "from app.department d left join app.department p on p.id = d.parent_id order by lower(d.name)", today));
    }

    public Map<String, Object> saveDepartment(SessionCtx ctx, UUID departmentId, String name, String parentId, Boolean active) {
        ctx.require("employee.write");
        String clean = required(name, "the department name", 80);
        UUID parent = blank(parentId) ? null : id(parentId, "parent department");
        return db.inTenant(ctx.tenantId(), c -> {
            if (parent != null) {
                requireDepartment(c, parent);
            }
            UUID id;
            if (departmentId == null) {
                id = AuthService.uuid(Sql.one(c, "insert into app.department (tenant_id, name, parent_id) values (?, ?, ?) returning id",
                        ctx.tenantId(), clean, parent).get("id"));
                UserService.audit(c, ctx, "department.created", "department", id, detail("name", clean));
            } else {
                id = departmentId;
                if (Sql.one(c, "select 1 from app.department where id = ? for update", id) == null) {
                    throw ApiException.notFound("The department");
                }
                if (parent != null) {
                    checkDepartmentLoop(c, id, parent);
                }
                Sql.update(c, "update app.department set name = ?, parent_id = ?, active = coalesce(?, active) where id = ?",
                        clean, parent, active, id);
                UserService.audit(c, ctx, "department.changed", "department", id,
                        detail("name", clean, "parentId", parent == null ? null : parent.toString(), "active", active));
            }
            return Map.of("id", id.toString());
        });
    }

    // ── exits that have fallen due ──────────────────────────────────────

    /**
     * Ends the membership of everyone whose last working day has passed. There is no background
     * job: this runs whenever employees are read, and for the caller on every request.
     */
    public static void applyDueExits(Connection c, UUID tenantId, LocalDate today) throws SQLException {
        ExitService.applyDue(c, tenantId, today);
        for (Map<String, Object> row : Sql.query(c,
                "select e.id, e.membership_id from app.employment e join app.membership m on m.id = e.membership_id "
                        + "where e.status = 'exited' and e.exit_date < ? and m.status <> 'ended'", today)) {
            endMembership(c, tenantId, AuthService.uuid(row.get("membershipId")), AuthService.uuid(row.get("id")));
        }
        ExitService.formerAccess(c, tenantId, null, today);
    }

    /** For the session check: true when this membership's last working day has passed, and ends it. */
    public static boolean endIfLeft(Connection c, UUID tenantId, UUID membershipId, LocalDate today) throws SQLException {
        ExitService.applyDueFor(c, tenantId, membershipId, today);
        Map<String, Object> row = Sql.one(c,
                "select e.id from app.employment e join app.membership m on m.id = e.membership_id "
                        + "where e.membership_id = ? and e.status = 'exited' and e.exit_date < ? and m.status <> 'ended'",
                membershipId, today);
        if (row != null) {
            endMembership(c, tenantId, membershipId, AuthService.uuid(row.get("id")));
        }
        ExitService.formerAccess(c, tenantId, membershipId, today);
        return row != null;
    }

    private static void endMembership(Connection c, UUID tenantId, UUID membershipId, UUID employmentId) throws SQLException {
        Sql.update(c, "delete from app.membership_role where membership_id = ?", membershipId);
        Sql.update(c, "insert into app.membership_role (tenant_id, membership_id, role_id) "
                + "select ?, ?, r.id from app.role r where r.code = 'ex_employee' and r.tenant_id is null", tenantId, membershipId);
        Sql.update(c, "update app.membership set status = 'ended', ended_at = now() where id = ?", membershipId);
        Sql.update(c, "insert into app.audit_event (tenant_id, actor_kind, actor_label, action, target_type, target_id, detail) "
                + "values (?, 'system', 'system', 'membership.ended', 'membership', ?, ?::jsonb)",
                tenantId, membershipId, Json.object(Map.of("reason", "last working day passed", "employmentId", employmentId.toString())));
    }

    // ── helpers ─────────────────────────────────────────────────────────

    private static final String EMPLOYEE_SELECT =
            "select e.id, e.person_id, e.membership_id, e.employee_code, e.employment_type, e.joining_date, e.probation_end, "
                    + "e.contract_end, e.status, e.exit_date, e.exit_reason, p.legal_name, p.preferred_name, p.personal_email, "
                    + "p.mobile, j.designation, j.department_id, d.name as department_name, j.manager_id, "
                    + "mp.legal_name as manager_name, j.effective_from as job_from, dir.email, "
                    + "(select x.last_working_day from app.exit_case x where x.employment_id = e.id and x.status = 'accepted' "
                    + "  and x.ended_at is null) as leaving_on, "
                    + "(select x.id from app.exit_case x where x.employment_id = e.id "
                    + "  and x.status in ('proposed','submitted','recommended','accepted','closed') order by x.started_at desc limit 1) as exit_id "
                    + "from app.employment e join app.person p on p.id = e.person_id "
                    + "left join lateral (select * from app.job_assignment j where j.employment_id = e.id "
                    + "  and j.effective_from <= greatest(?::date, e.joining_date) "
                    + "  order by j.effective_from desc, j.created_at desc limit 1) j on true "
                    + "left join app.department d on d.id = j.department_id "
                    + "left join app.employment me on me.id = j.manager_id left join app.person mp on mp.id = me.person_id "
                    + "left join app.member_directory() dir on dir.membership_id = e.membership_id";

    private static boolean canRead(Connection c, SessionCtx ctx, Map<String, Object> e, LocalDate today) throws SQLException {
        if (ctx.scopeOf("employee.read") == null) {
            return false;
        }
        Object membership = e.get("membershipId");
        if (membership != null && ctx.membershipId() != null && ctx.membershipId().toString().equals(membership)) {
            return true;
        }
        if (membership == null) {
            // Someone without a login: everyone-scope, or their manager.
            return ctx.reachesEveryone("employee.read") || java.util.Arrays.asList(Team.reportEmployments(c, ctx, "employee.read", today))
                    .contains(AuthService.uuid(e.get("id")));
        }
        return Team.reaches(c, ctx, "employee.read", AuthService.uuid(membership), today);
    }

    private static boolean canReveal(Connection c, SessionCtx ctx, Map<String, Object> e, LocalDate today) throws SQLException {
        if (ctx.reachesEveryone("employee.sensitive")) {
            return true;
        }
        Object membership = e.get("membershipId");
        return "self".equals(ctx.scopeOf("employee.sensitive")) && membership != null && ctx.membershipId() != null
                && ctx.membershipId().toString().equals(membership);
    }

    private static byte[] customBytes(Connection c, String key, UUID personId) throws SQLException {
        try (var ps = c.prepareStatement("select v.value_enc from app.custom_field_value v join app.custom_field f on f.id = v.field_id "
                + "where v.person_id = ? and f.field_key = ? and f.sensitive")) {
            ps.setObject(1, personId);
            ps.setString(2, key);
            try (var rs = ps.executeQuery()) {
                return rs.next() ? rs.getBytes(1) : null;
            }
        }
    }

    /** What the rules know about an employee: department, work location, type and designation, today. */
    static AppliesTo.Subject subjectOf(Connection c, UUID employmentId, LocalDate today) throws SQLException {
        Map<String, Object> s = Sql.one(c, "select e.employment_type, j.department_id, j.designation, "
                + "(select a.work_location_id from app.attendance_assignment a where a.membership_id = e.membership_id "
                + "  and a.effective_from <= ? order by a.effective_from desc, a.created_at desc limit 1) as work_location_id "
                + "from app.employment e left join lateral (select * from app.job_assignment j where j.employment_id = e.id "
                + "  and j.effective_from <= greatest(?::date, e.joining_date) order by j.effective_from desc, j.created_at desc limit 1) j on true "
                + "where e.id = ?", today, today, employmentId);
        return s == null ? AppliesTo.Subject.UNKNOWN : new AppliesTo.Subject(AuthService.uuid(s.get("departmentId")),
                AuthService.uuid(s.get("workLocationId")), (String) s.get("employmentType"), (String) s.get("designation"));
    }

    /**
     * The Organization's own fields that apply to this employee, with their values. Sensitive
     * values are never in the response; a manager reading their team sees only fields marked for managers.
     */
    private List<Map<String, Object>> customFieldValues(Connection c, SessionCtx ctx, Map<String, Object> e, LocalDate today)
            throws SQLException {
        UUID employmentId = AuthService.uuid(e.get("id"));
        UUID personId = AuthService.uuid(e.get("personId"));
        boolean full = ctx.reachesEveryone("employee.read") || Boolean.TRUE.equals(isOwn(ctx, e));
        AppliesTo.Subject subject = subjectOf(c, employmentId, today);
        Map<String, Map<String, Object>> values = new java.util.HashMap<>();
        for (Map<String, Object> v : Sql.query(c, "select field_id, value, (value_enc is not null) as has_secret, updated_at "
                + "from app.custom_field_value where person_id = ?", personId)) {
            values.put((String) v.get("fieldId"), v);
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (FieldCatalog.CustomField f : OnboardingConfigService.customFieldDefs(c)) {
            if (!f.active() || !f.appliesTo().matches(subject) || (!full && !f.managerCanSee())) {
                continue;
            }
            Map<String, Object> row = FieldCatalog.customField(f);
            row.put("section", f.section());
            Map<String, Object> v = values.get(f.id().toString());
            row.put("hasValue", v != null && (v.get("value") != null || Boolean.TRUE.equals(v.get("hasSecret"))));
            row.put("value", f.sensitive() || v == null || v.get("value") == null ? null : Json.parse((String) v.get("value")));
            out.add(row);
        }
        return out;
    }

    private static Object isOwn(SessionCtx ctx, Map<String, Object> e) {
        return ctx.membershipId() != null && ctx.membershipId().toString().equals(e.get("membershipId"));
    }

    /**
     * Sets values of the Organization's own fields on an employee, by key. A null or empty value clears
     * it. Each value is checked against its field; sensitive ones are stored encrypted.
     */
    public void saveCustomValues(SessionCtx ctx, UUID employmentId, Map<String, Object> values) {
        ctx.require("employee.write");
        if (values == null || values.isEmpty()) {
            throw ApiException.badRequest("Give at least one value");
        }
        LocalDate today = today();
        db.inTenant(ctx.tenantId(), c -> {
            Map<String, Object> e = lockEmployment(c, employmentId);
            UUID personId = AuthService.uuid(e.get("personId"));
            AppliesTo.Subject subject = subjectOf(c, employmentId, today);
            Map<String, FieldCatalog.CustomField> byKey = new java.util.HashMap<>();
            for (FieldCatalog.CustomField f : OnboardingConfigService.customFieldDefs(c)) {
                byKey.put(f.key(), f);
            }
            List<String> changed = new ArrayList<>();
            for (Map.Entry<String, Object> entry : values.entrySet()) {
                String key = entry.getKey().startsWith("custom.") ? entry.getKey().substring(7) : entry.getKey();
                FieldCatalog.CustomField f = byKey.get(key);
                if (f == null || !f.active()) {
                    throw new ApiException(400, "field.unknown", "Unknown field: " + entry.getKey());
                }
                if (!f.appliesTo().matches(subject)) {
                    throw new ApiException(400, "field.not_applicable", f.label() + " does not apply to this employee");
                }
                Object clean = CustomValues.normalize(f, entry.getValue());
                String plain = clean == null || f.sensitive() ? null : Json.write(clean);
                byte[] secret = clean == null || !f.sensitive() ? null : crypto.encrypt(ctx.tenantId(), Json.write(clean));
                Sql.update(c, "insert into app.custom_field_value (tenant_id, person_id, field_id, value, value_enc, updated_by) "
                                + "values (?, ?, ?, ?::jsonb, ?, ?) on conflict (person_id, field_id) do update set value = excluded.value, "
                                + "value_enc = excluded.value_enc, updated_by = excluded.updated_by, updated_at = now()",
                        ctx.tenantId(), personId, f.id(), plain, secret, ctx.membershipId());
                changed.add(f.key());
            }
            UserService.audit(c, ctx, "employee.custom_fields_set", "employment", employmentId, Map.of("fields", String.join(",", changed)));
            return null;
        });
    }

    private byte[] bytes(Connection c, String field, UUID personId) throws SQLException {
        String sql = field.equals("bank_account")
                ? "select account_enc from app.bank_account where person_id = ? order by effective_from desc, created_at desc limit 1"
                : "select value_enc from app.statutory_id where person_id = ? and kind = ? and replaced_at is null and pending_reason is null";
        try (var ps = c.prepareStatement(sql)) {
            ps.setObject(1, personId);
            if (!field.equals("bank_account")) {
                ps.setString(2, field);
            }
            try (var rs = ps.executeQuery()) {
                return rs.next() ? rs.getBytes(1) : null;
            }
        }
    }

    private void insertIdentifier(Connection c, SessionCtx ctx, UUID personId, String kind, String value, String pending)
            throws SQLException {
        Sql.update(c, "insert into app.statutory_id (tenant_id, person_id, kind, value_enc, value_hash, last4, pending_reason, created_by) "
                        + "values (?, ?, ?, ?, ?, ?, ?, ?)",
                ctx.tenantId(), personId, kind, crypto.encrypt(ctx.tenantId(), value),
                crypto.fingerprint(ctx.tenantId(), kind, value), IdChecks.last4(value), pending, ctx.membershipId());
    }

    private void insertBank(Connection c, SessionCtx ctx, UUID personId, String holder, String account, String ifsc,
                            String bankName, String branch, LocalDate from) throws SQLException {
        Sql.update(c, "insert into app.bank_account (tenant_id, person_id, holder_name, account_enc, account_last4, ifsc, "
                        + "bank_name, branch, effective_from, created_by) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                ctx.tenantId(), personId, holder, crypto.encrypt(ctx.tenantId(), account), IdChecks.last4(account), ifsc,
                bankName, branch, from, ctx.membershipId());
    }

    private static Map<String, Object> lockEmployment(Connection c, UUID employmentId) throws SQLException {
        Map<String, Object> e = Sql.one(c, "select id, person_id, membership_id, status, joining_date, employee_code "
                + "from app.employment where id = ? for update", employmentId);
        if (e == null) {
            throw ApiException.notFound("The employee");
        }
        return e;
    }

    private static void requireDepartment(Connection c, UUID departmentId) throws SQLException {
        Map<String, Object> d = Sql.one(c, "select active from app.department where id = ?", departmentId);
        if (d == null) {
            throw ApiException.notFound("The department");
        }
        if (!Boolean.TRUE.equals(d.get("active"))) {
            throw new ApiException(409, "request.refused", "That department is no longer in use");
        }
    }

    private static void requireActiveManager(Connection c, UUID managerEmploymentId) throws SQLException {
        Map<String, Object> m = Sql.one(c, "select status from app.employment where id = ?", managerEmploymentId);
        if (m == null) {
            throw ApiException.notFound("The manager");
        }
        if (!"active".equals(m.get("status"))) {
            throw new ApiException(409, "employee.manager_inactive", "The reporting manager must be an active employee");
        }
    }

    /** Refuses a manager who already reports, directly or through others, to this employee. */
    private static void checkReportingLoop(Connection c, UUID employmentId, UUID managerId, LocalDate on) throws SQLException {
        Set<UUID> seen = new HashSet<>();
        UUID current = managerId;
        for (int i = 0; i < MAX_CHAIN && current != null; i++) {
            if (current.equals(employmentId)) {
                throw new ApiException(409, "employee.manager_loop",
                        "That would make a loop: the new manager already reports to this employee");
            }
            if (!seen.add(current)) {
                return;   // an older loop elsewhere; not this change's doing
            }
            Map<String, Object> up = Sql.one(c, "select manager_id from app.job_assignment where employment_id = ? "
                    + "and effective_from <= greatest(?::date, (select joining_date from app.employment where id = ?)) "
                    + "order by effective_from desc, created_at desc limit 1", current, on, current);
            current = up == null ? null : AuthService.uuid(up.get("managerId"));
        }
    }

    private static void checkDepartmentLoop(Connection c, UUID departmentId, UUID parentId) throws SQLException {
        Set<UUID> seen = new HashSet<>();
        UUID current = parentId;
        for (int i = 0; i < MAX_CHAIN && current != null; i++) {
            if (current.equals(departmentId)) {
                throw new ApiException(409, "department.loop", "A department cannot sit inside itself or one of its own sub-departments");
            }
            if (!seen.add(current)) {
                return;
            }
            Map<String, Object> up = Sql.one(c, "select parent_id from app.department where id = ?", current);
            current = up == null ? null : AuthService.uuid(up.get("parentId"));
        }
    }

    static boolean isOnlyOrgAdmin(Connection c, UUID membershipId) throws SQLException {
        return Sql.one(c, "select 1 from app.membership_role mr join app.role r on r.id = mr.role_id "
                + "where mr.membership_id = ? and r.code = 'owner' "
                + "and not exists (select 1 from app.membership m2 join app.membership_role mr2 on mr2.membership_id = m2.id "
                + "  join app.role r2 on r2.id = mr2.role_id where r2.code = 'owner' and m2.status = 'active' and m2.id <> ?)",
                membershipId, membershipId) != null;
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

    private static String optional(String value, int max) {
        if (blank(value)) {
            return null;
        }
        String v = value.trim();
        if (v.length() > max) {
            throw ApiException.badRequest("A value is longer than " + max + " characters");
        }
        return v;
    }

    private static LocalDate requiredDate(String value, String what) {
        if (blank(value)) {
            throw ApiException.badRequest("Enter the " + what);
        }
        return optionalDate(value, what);
    }

    private static LocalDate optionalDate(String value, String what) {
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
