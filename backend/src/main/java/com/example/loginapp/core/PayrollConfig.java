package com.example.loginapp.core;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Payroll setup: the entity and its registrations, the component library, the Organization's own
 * tables, salary structures, pay groups and the statutory choices. Every change to a component or a
 * structure applies from a date and keeps the earlier versions, so a month already paid never changes.
 * Also builds the configuration a run freezes (config as of a date).
 */
public final class PayrollConfig {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    public record SettingsInput(String entityName, String pan, String tan, String address, String debitAccount, String debitIfsc,
                                String proration, Boolean absenceIsLop, Boolean pfRestrict, List<String> metroLocations,
                                BigDecimal variancePct, BigDecimal largeOneTime, Boolean releaseAuto) {}

    public record RegistrationInput(String statute, String state, String number) {}

    public record ComponentInput(String code, String name, String kind, String validFrom, String amountType, String formula, String wage,
                                 Boolean esiWage, Boolean taxable, Boolean prorate, String rounding, Boolean oneTime, Boolean showZero,
                                 String ledgerCode, Integer position) {}

    public record TableInput(String name, String keyLabel, List<Map<String, Object>> rows) {}

    public record StructureInput(String name, String basis, String validFrom, List<Map<String, Object>> lines, String balancing,
                                 Boolean active) {}

    public record GroupInput(String name, Integer cutoffDay, Integer payDay, String defaultStructureId) {}

    private final Db db;
    private final Clock clock;

    public PayrollConfig(Db db) {
        this(db, Clock.systemUTC());
    }

    public PayrollConfig(Db db, Clock clock) {
        this.db = db;
        this.clock = clock;
    }

    LocalDate today() {
        return LocalDate.now(clock.withZone(IST));
    }

    static void need(SessionCtx ctx, String permission) {
        if (!ctx.inOrganization()) {
            throw new ApiException(403, "tenant.required", "Choose an Organization first");
        }
        if (!ctx.reachesEveryone(permission)) {
            throw ApiException.denied(permission);
        }
    }

    static void needRead(SessionCtx ctx) {
        if (!(ctx.reachesEveryone("payroll.read") || ctx.reachesEveryone("payroll.prepare") || ctx.reachesEveryone("compensation.read"))) {
            throw ApiException.denied("payroll.read");
        }
    }

    // ── the whole setup, for the Setup screen ───────────────────────────

    public Map<String, Object> setup(SessionCtx ctx) {
        needRead(ctx);
        LocalDate today = today();
        return db.inTenant(ctx.tenantId(), c -> {
            PayrollDefaults.ensure(c, ctx.tenantId());
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("settings", Sql.one(c, "select entity_name, pan, tan, address, debit_account, debit_ifsc, proration, absence_is_lop, "
                    + "pf_restrict, metro_locations, variance_pct, large_one_time, release_auto, updated_at, "
                    + Names.of("updated_by") + " as updated_by_name from app.payroll_settings"));
            out.put("registrations", Sql.query(c, "select id, statute, state, number from app.payroll_registration order by statute, state"));
            out.put("components", components(c, today));
            out.put("tables", tables(c));
            out.put("structures", structures(c, today));
            out.put("groups", groups(c, today));
            out.put("rules", rulesInForce(c, today));
            out.put("locations", Sql.query(c, "select id, name, state_code from app.work_location where active order by lower(name)"));
            out.put("facts", PayrollEngine.FACTS.keySet().stream().sorted().toList());
            out.put("canEdit", ctx.reachesEveryone("payroll.prepare"));
            out.put("today", today.toString());
            return out;
        });
    }

    // ── settings and registrations ──────────────────────────────────────

    public Map<String, Object> saveSettings(SessionCtx ctx, SettingsInput in) {
        need(ctx, "payroll.prepare");
        return db.inTenant(ctx.tenantId(), c -> {
            PayrollDefaults.ensure(c, ctx.tenantId());
            String proration = in.proration() == null ? "calendar" : in.proration();
            if (!Set.of("calendar", "working", "fixed_30").contains(proration)) {
                throw ApiException.badRequest("Proration is by calendar days, working days or a fixed 30");
            }
            String pan = upper(in.pan());
            if (pan != null && !pan.matches("^[A-Z]{5}[0-9]{4}[A-Z]$")) {
                throw ApiException.badRequest("The PAN should look like ABCDE1234F");
            }
            String tan = upper(in.tan());
            if (tan != null && !tan.matches("^[A-Z]{4}[0-9]{5}[A-Z]$")) {
                throw ApiException.badRequest("The TAN should look like ABCD12345E");
            }
            String ifsc = upper(in.debitIfsc());
            if (ifsc != null && !ifsc.matches("^[A-Z]{4}0[A-Z0-9]{6}$")) {
                throw ApiException.badRequest("The IFSC should be 11 characters, like HDFC0001234");
            }
            String account = blank(in.debitAccount());
            if (account != null && !account.matches("^[0-9]{6,20}$")) {
                throw ApiException.badRequest("The debit account should be 6 to 20 digits");
            }
            List<UUID> metro = new ArrayList<>();
            for (String id : in.metroLocations() == null ? List.<String>of() : in.metroLocations()) {
                metro.add(id(id, "work location"));
            }
            Sql.update(c, "update app.payroll_settings set entity_name = ?, pan = ?, tan = ?, address = ?, debit_account = ?, debit_ifsc = ?, "
                            + "proration = ?, absence_is_lop = ?, pf_restrict = ?, metro_locations = ?, variance_pct = ?, large_one_time = ?, "
                            + "release_auto = ?, updated_by = ?, updated_at = now()",
                    blank(in.entityName()), pan, tan, blank(in.address()), account, ifsc, proration,
                    Boolean.TRUE.equals(in.absenceIsLop()), in.pfRestrict() == null || in.pfRestrict(), metro.toArray(UUID[]::new),
                    in.variancePct() == null ? BigDecimal.TEN : in.variancePct(),
                    in.largeOneTime() == null ? BigDecimal.valueOf(25000) : in.largeOneTime(), Boolean.TRUE.equals(in.releaseAuto()),
                    ctx.membershipId());
            UserService.audit(c, ctx, "payroll.settings_changed", "payroll", null, Map.of("proration", proration));
            return Map.of("saved", true);
        });
    }

    public Map<String, Object> addRegistration(SessionCtx ctx, RegistrationInput in) {
        need(ctx, "payroll.prepare");
        String statute = in.statute() == null ? "" : in.statute();
        if (!Set.of("pf", "esi", "pt", "lwf").contains(statute)) {
            throw ApiException.badRequest("Choose PF, ESI, professional tax or labour welfare fund");
        }
        String state = upper(in.state());
        if (Set.of("pt", "lwf").contains(statute) && (state == null || !IdChecks.STATES.containsKey(state))) {
            throw ApiException.badRequest("Choose the state this registration is for");
        }
        if (!Set.of("pt", "lwf").contains(statute)) {
            state = null;
        }
        String number = blank(in.number());
        if (number == null || number.length() < 3 || number.length() > 40) {
            throw ApiException.badRequest("Type the registration number");
        }
        String s = state;
        return db.inTenant(ctx.tenantId(), c -> {
            if (Sql.one(c, "select 1 from app.payroll_registration where statute = ? and coalesce(state, '') = coalesce(?, '')", statute, s) != null) {
                throw new ApiException(409, "request.refused", "That registration is already recorded; remove it first to change it");
            }
            Map<String, Object> r = Sql.one(c, "insert into app.payroll_registration (tenant_id, statute, state, number) values (?, ?, ?, ?) "
                    + "returning id", ctx.tenantId(), statute, s, number);
            UserService.audit(c, ctx, "payroll.registration_added", "payroll", null, Map.of("statute", statute, "state", s == null ? "" : s));
            return r;
        });
    }

    public void removeRegistration(SessionCtx ctx, UUID id) {
        need(ctx, "payroll.prepare");
        db.inTenant(ctx.tenantId(), c -> {
            Map<String, Object> r = Sql.one(c, "delete from app.payroll_registration where id = ? returning statute, state", id);
            if (r == null) {
                throw ApiException.notFound("The registration");
            }
            UserService.audit(c, ctx, "payroll.registration_removed", "payroll", null,
                    Map.of("statute", r.get("statute"), "state", r.get("state") == null ? "" : r.get("state")));
            return null;
        });
    }

    // ── components ──────────────────────────────────────────────────────

    static List<Map<String, Object>> components(Connection c, LocalDate asOf) throws SQLException {
        List<Map<String, Object>> list = Sql.query(c, "select pc.id, pc.code, pc.name, pc.kind, pc.statutory, pc.position, pc.retired_at, "
                + "v.valid_from, v.amount_type, v.formula, v.wage, v.esi_wage, v.taxable, v.prorate, v.rounding, v.one_time, v.show_zero, "
                + "v.ledger_code, (select count(*) from app.pay_component_version x where x.component_id = pc.id) as versions, "
                + "(select min(x.valid_from) from app.pay_component_version x where x.component_id = pc.id and x.valid_from > ?) as next_from "
                + "from app.pay_component pc join lateral (select * from app.pay_component_version v where v.component_id = pc.id "
                + "  and v.valid_from <= ? order by v.valid_from desc limit 1) v on true "
                + "order by pc.position, pc.code", asOf, asOf);
        return list;
    }

    public List<Map<String, Object>> componentHistory(SessionCtx ctx, UUID id) {
        needRead(ctx);
        return db.inTenant(ctx.tenantId(), c -> Sql.query(c, "select v.valid_from, v.amount_type, v.formula, v.wage, v.esi_wage, v.taxable, "
                + "v.prorate, v.rounding, v.one_time, v.created_at, " + Names.of("v.created_by") + " as created_by_name "
                + "from app.pay_component_version v where v.component_id = ? order by v.valid_from desc", id));
    }

    /** Adds a component, or a new version of one from a date. Statutory components keep their working; only names and ledgers change. */
    public Map<String, Object> saveComponent(SessionCtx ctx, UUID id, ComponentInput in) {
        need(ctx, "payroll.prepare");
        LocalDate today = today();
        LocalDate from = in.validFrom() == null || in.validFrom().isBlank() ? today.withDayOfMonth(1) : date(in.validFrom(), "valid from");
        if (from.getDayOfMonth() != 1) {
            throw ApiException.badRequest("A change applies from the first of a month");
        }
        return db.inTenant(ctx.tenantId(), c -> {
            PayrollDefaults.ensure(c, ctx.tenantId());
            refuseIntoApproved(c, from);
            UUID componentId = id;
            Map<String, Object> existing = id == null ? null : Sql.one(c, "select * from app.pay_component where id = ?", id);
            if (id != null && existing == null) {
                throw ApiException.notFound("The component");
            }
            boolean statutory = existing != null && Boolean.TRUE.equals(existing.get("statutory"));
            String name = blank(in.name());
            if (name == null || name.length() > 60) {
                throw ApiException.badRequest("Give the component a name of up to 60 characters");
            }
            String kind = existing != null ? (String) existing.get("kind") : in.kind();
            if (!Set.of("earning", "deduction", "employer", "info").contains(String.valueOf(kind))) {
                throw ApiException.badRequest("Choose whether it is an earning, a deduction, an employer contribution or for information");
            }
            String amountType = statutory ? "statutory" : in.amountType();
            if (!statutory && !Set.of("fixed", "formula", "input").contains(String.valueOf(amountType))) {
                throw ApiException.badRequest("Choose a fixed amount, a formula, or an amount entered each month");
            }
            String formula = statutory || "input".equals(amountType) ? null : blank(in.formula());
            if (!statutory && !"input".equals(amountType)) {
                if (formula == null) {
                    throw ApiException.badRequest("fixed".equals(amountType) ? "Type the monthly amount" : "Type the formula");
                }
                if ("fixed".equals(amountType)) {
                    try {
                        if (new BigDecimal(formula.replace(",", "")).signum() < 0) {
                            throw ApiException.badRequest("The amount cannot be negative");
                        }
                        formula = new BigDecimal(formula.replace(",", "")).toPlainString();
                    } catch (NumberFormatException e) {
                        throw ApiException.badRequest("Type the monthly amount as a number");
                    }
                }
                Set<String> codes = new LinkedHashSet<>(PayrollEngine.STARTING);
                for (Map<String, Object> other : components(c, from)) {
                    codes.add((String) other.get("code"));
                }
                String code = existing != null ? (String) existing.get("code") : upper(in.code());
                if (code != null) {
                    codes.add(code);
                }
                checkFormula(formula, codes);
            }
            String wage = in.wage() == null ? "exclude" : in.wage();
            if (!Set.of("include", "exclude", "conditional").contains(wage)) {
                throw ApiException.badRequest("Choose whether it counts as wages: always, never, or only to bring wages up to half");
            }
            String rounding = in.rounding() == null ? "nearest" : in.rounding();
            if (!Set.of("nearest", "up", "down", "none").contains(rounding)) {
                throw ApiException.badRequest("Choose how it is rounded");
            }
            if (componentId == null) {
                String code = upper(in.code());
                if (code == null || !code.matches("^[A-Z][A-Z0-9_]{1,29}$") || PayrollEngine.STARTING.contains(code)) {
                    throw ApiException.badRequest("The code is 2 to 30 capital letters, digits or underscores, starting with a letter (MEAL_CARD)");
                }
                componentId = AuthService.uuid(Sql.one(c, "insert into app.pay_component (tenant_id, code, name, kind, position) values (?, ?, ?, ?, ?) "
                        + "returning id", ctx.tenantId(), code, name, kind, (short) (in.position() == null ? 60 : in.position())).get("id"));
            } else {
                Sql.update(c, "update app.pay_component set name = ?, position = coalesce(?, position) where id = ?", name,
                        in.position() == null ? null : in.position().shortValue(), componentId);
            }
            Map<String, Object> current = Sql.one(c, "select * from app.pay_component_version where component_id = ? and valid_from <= ? "
                    + "order by valid_from desc limit 1", componentId, from);
            boolean esi = statutory ? Boolean.TRUE.equals(current.get("esiWage")) : in.esiWage() == null || in.esiWage();
            boolean taxable = statutory ? Boolean.TRUE.equals(current.get("taxable")) : in.taxable() == null || in.taxable();
            boolean prorate = statutory ? false : in.prorate() == null || in.prorate();
            String w = statutory ? "exclude" : wage;
            String r = statutory ? (String) current.get("rounding") : rounding;
            boolean oneTime = statutory ? false : Boolean.TRUE.equals(in.oneTime()) || "input".equals(amountType) && in.oneTime() == null;
            Sql.update(c, "insert into app.pay_component_version (tenant_id, component_id, valid_from, amount_type, formula, wage, esi_wage, "
                            + "taxable, prorate, rounding, one_time, show_zero, ledger_code, created_by) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) "
                            + "on conflict (component_id, valid_from) do update set amount_type = excluded.amount_type, formula = excluded.formula, "
                            + "wage = excluded.wage, esi_wage = excluded.esi_wage, taxable = excluded.taxable, prorate = excluded.prorate, "
                            + "rounding = excluded.rounding, one_time = excluded.one_time, show_zero = excluded.show_zero, "
                            + "ledger_code = excluded.ledger_code, created_by = excluded.created_by, created_at = now()",
                    ctx.tenantId(), componentId, from, amountType, formula, w, esi, taxable, prorate, r, oneTime, Boolean.TRUE.equals(in.showZero()),
                    blank(in.ledgerCode()), ctx.membershipId());
            UserService.audit(c, ctx, id == null ? "payroll.component_added" : "payroll.component_changed", "payroll", componentId,
                    Map.of("name", name, "from", from.toString()));
            return Map.of("id", componentId.toString());
        });
    }

    public void retireComponent(SessionCtx ctx, UUID id) {
        need(ctx, "payroll.prepare");
        db.inTenant(ctx.tenantId(), c -> {
            Map<String, Object> pc = Sql.one(c, "select code, name, statutory from app.pay_component where id = ?", id);
            if (pc == null) {
                throw ApiException.notFound("The component");
            }
            if (Boolean.TRUE.equals(pc.get("statutory"))) {
                throw new ApiException(409, "request.refused", "Statutory components cannot be retired");
            }
            for (Map<String, Object> s : structures(c, today())) {
                for (Map<String, Object> l : PayrollEngine.list(s, "lines")) {
                    if (pc.get("code").equals(l.get("code"))) {
                        throw new ApiException(409, "request.refused", pc.get("name") + " is in the " + s.get("name") + " structure; take it out first");
                    }
                }
            }
            Sql.update(c, "update app.pay_component set retired_at = now() where id = ?", id);
            UserService.audit(c, ctx, "payroll.component_retired", "payroll", id, Map.of("name", pc.get("name")));
            return null;
        });
    }

    /** Checks a formula against the components that exist, for the editor's Check button. */
    public Map<String, Object> checkFormula(SessionCtx ctx, String formula) {
        needRead(ctx);
        return db.inTenant(ctx.tenantId(), c -> {
            Set<String> codes = new LinkedHashSet<>(PayrollEngine.STARTING);
            for (Map<String, Object> other : components(c, today())) {
                codes.add((String) other.get("code"));
            }
            try {
                Formula f = Formula.parse(formula);
                f.check(codes, PayrollEngine.FACTS);
                return Map.<String, Object>of("ok", true, "components", List.copyOf(f.components()), "facts", List.copyOf(f.facts()));
            } catch (Formula.Error e) {
                Map<String, Object> out = new LinkedHashMap<>();
                out.put("ok", false);
                out.put("message", e.getMessage());
                out.put("position", e.position());
                return out;
            }
        });
    }

    static void checkFormula(String formula, Set<String> codes) {
        try {
            Formula.parse(formula).check(codes, PayrollEngine.FACTS);
        } catch (Formula.Error e) {
            throw new ApiException(400, "payroll.formula", e.getMessage() + (e.position() >= 0 ? " (at character " + (e.position() + 1) + ")" : ""));
        }
    }

    // ── tables ──────────────────────────────────────────────────────────

    static List<Map<String, Object>> tables(Connection c) throws SQLException {
        List<Map<String, Object>> out = Sql.query(c, "select id, name, key_label from app.value_table order by name");
        for (Map<String, Object> t : out) {
            t.put("rows", Sql.query(c, "select key, value from app.value_table_row where table_id = ?::uuid order by lower(key)", t.get("id")));
        }
        return out;
    }

    /** Creates a table or replaces its rows. Changing a table changes the months not yet frozen. */
    public Map<String, Object> saveTable(SessionCtx ctx, UUID id, TableInput in) {
        need(ctx, "payroll.prepare");
        String name = in.name() == null ? "" : in.name().trim().toLowerCase();
        if (!name.matches("^[a-z][a-z0-9_]{1,39}$")) {
            throw ApiException.badRequest("The table name is lower-case letters, digits and underscores (meal_card)");
        }
        List<Map<String, Object>> rows = in.rows() == null ? List.of() : in.rows();
        Set<String> keys = new LinkedHashSet<>();
        for (Map<String, Object> r : rows) {
            String key = blank(String.valueOf(r.getOrDefault("key", "")));
            if (key == null) {
                throw ApiException.badRequest("Every row needs a key");
            }
            if (!keys.add(key.toLowerCase())) {
                throw ApiException.badRequest("The key " + key + " is there twice");
            }
            try {
                new BigDecimal(String.valueOf(r.get("value")));
            } catch (NumberFormatException e) {
                throw ApiException.badRequest("The value for " + key + " is not a number");
            }
        }
        return db.inTenant(ctx.tenantId(), c -> {
            UUID tableId = id;
            if (tableId == null) {
                if (Sql.one(c, "select 1 from app.value_table where name = ?", name) != null) {
                    throw new ApiException(409, "request.refused", "A table called " + name + " already exists");
                }
                tableId = AuthService.uuid(Sql.one(c, "insert into app.value_table (tenant_id, name, key_label) values (?, ?, ?) returning id",
                        ctx.tenantId(), name, blank(in.keyLabel()) == null ? "Key" : in.keyLabel().trim()).get("id"));
            } else {
                Sql.update(c, "update app.value_table set key_label = coalesce(?, key_label) where id = ?", blank(in.keyLabel()), tableId);
                Sql.update(c, "delete from app.value_table_row where table_id = ?", tableId);
            }
            for (Map<String, Object> r : rows) {
                Sql.update(c, "insert into app.value_table_row (tenant_id, table_id, key, value) values (?, ?, ?, ?)", ctx.tenantId(), tableId,
                        String.valueOf(r.get("key")).trim(), new BigDecimal(String.valueOf(r.get("value"))));
            }
            UserService.audit(c, ctx, "payroll.table_saved", "payroll", tableId, Map.of("name", name, "rows", rows.size()));
            return Map.of("id", tableId.toString());
        });
    }

    // ── structures ──────────────────────────────────────────────────────

    static List<Map<String, Object>> structures(Connection c, LocalDate asOf) throws SQLException {
        List<Map<String, Object>> out = Sql.query(c, "select s.id, s.name, s.basis, s.active, v.valid_from, v.lines, v.balancing, "
                + Names.of("v.created_by") + " as changed_by_name, v.created_at as changed_at, "
                + "(select count(distinct x.employment_id) from app.compensation x where x.structure_id = s.id and x.superseded_at is null) as people, "
                + "(select min(x.valid_from) from app.salary_structure_version x where x.structure_id = s.id and x.valid_from > ?) as next_from "
                + "from app.salary_structure s join lateral (select * from app.salary_structure_version v where v.structure_id = s.id "
                + "  and v.valid_from <= ? order by v.valid_from desc limit 1) v on true order by lower(s.name)", asOf, asOf);
        for (Map<String, Object> s : out) {
            s.put("lines", Json.parse((String) s.get("lines")));
        }
        return out;
    }

    public Map<String, Object> saveStructure(SessionCtx ctx, UUID id, StructureInput in) {
        need(ctx, "payroll.prepare");
        LocalDate from = in.validFrom() == null || in.validFrom().isBlank() ? today().withDayOfMonth(1) : date(in.validFrom(), "valid from");
        if (from.getDayOfMonth() != 1) {
            throw ApiException.badRequest("A change applies from the first of a month");
        }
        String name = blank(in.name());
        if (name == null || name.length() > 60) {
            throw ApiException.badRequest("Give the structure a name of up to 60 characters");
        }
        String basis = in.basis() == null ? "ctc" : in.basis();
        if (!Set.of("ctc", "gross").contains(basis)) {
            throw ApiException.badRequest("A structure starts from CTC or from gross pay");
        }
        return db.inTenant(ctx.tenantId(), c -> {
            PayrollDefaults.ensure(c, ctx.tenantId());
            refuseIntoApproved(c, from);
            List<Map<String, Object>> lines = validateLines(c, in.lines(), in.balancing(), basis, from);
            UUID structureId = id;
            if (structureId == null) {
                structureId = AuthService.uuid(Sql.one(c, "insert into app.salary_structure (tenant_id, name, basis) values (?, ?, ?) returning id",
                        ctx.tenantId(), name, basis).get("id"));
            } else {
                if (Sql.update(c, "update app.salary_structure set name = ?, basis = ?, active = coalesce(?, active) where id = ?", name, basis,
                        in.active(), structureId) == 0) {
                    throw ApiException.notFound("The structure");
                }
            }
            Sql.update(c, "insert into app.salary_structure_version (tenant_id, structure_id, valid_from, lines, balancing, created_by) "
                            + "values (?, ?, ?, ?::jsonb, ?, ?) on conflict (structure_id, valid_from) do update set lines = excluded.lines, "
                            + "balancing = excluded.balancing, created_by = excluded.created_by, created_at = now()",
                    ctx.tenantId(), structureId, from, Json.write(lines), blank(in.balancing()), ctx.membershipId());
            UserService.audit(c, ctx, id == null ? "payroll.structure_added" : "payroll.structure_changed", "payroll", structureId,
                    Map.of("name", name, "from", from.toString()));
            return Map.of("id", structureId.toString());
        });
    }

    private List<Map<String, Object>> validateLines(Connection c, List<Map<String, Object>> in, String balancing, String basis, LocalDate from)
            throws SQLException {
        if (in == null || in.isEmpty()) {
            throw ApiException.badRequest("A structure needs at least one component");
        }
        Map<String, Map<String, Object>> byCode = new HashMap<>();
        Set<String> codes = new LinkedHashSet<>(PayrollEngine.STARTING);
        for (Map<String, Object> pc : components(c, from)) {
            if (pc.get("retiredAt") == null) {
                byCode.put((String) pc.get("code"), pc);
                codes.add((String) pc.get("code"));
            }
        }
        List<Map<String, Object>> lines = new ArrayList<>();
        Map<String, String> formulas = new LinkedHashMap<>();
        Map<String, Object> components = new HashMap<>();
        for (Map<String, Object> l : in) {
            String code = upper(String.valueOf(l.get("code")));
            Map<String, Object> pc = byCode.get(code);
            if (pc == null) {
                throw ApiException.badRequest("No component called " + code);
            }
            if (formulas.containsKey(code)) {
                throw ApiException.badRequest(pc.get("name") + " is in the structure twice");
            }
            boolean statutory = Boolean.TRUE.equals(pc.get("statutory"));
            if (statutory && !"PF_ER".equals(code)) {
                throw ApiException.badRequest(pc.get("name") + " is worked out every month by its rule pack; only Employer PF can be part of a structure");
            }
            String formula = statutory ? null : blank(l.get("formula") == null ? null : String.valueOf(l.get("formula")));
            String effective = formula != null ? formula : (String) pc.get("formula");
            if (!statutory && !"input".equals(pc.get("amountType"))) {
                if (effective == null) {
                    throw ApiException.badRequest(pc.get("name") + " needs a formula");
                }
                checkFormula(effective, codes);
                String other = basis.equals("ctc") ? "GROSS_" : "CTC_";
                if (effective.contains(other)) {
                    throw ApiException.badRequest(pc.get("name") + " uses " + other + "MONTHLY, but this structure starts from "
                            + (basis.equals("ctc") ? "CTC" : "gross pay"));
                }
            }
            Map<String, Object> line = new LinkedHashMap<>();
            line.put("code", code);
            if (formula != null) {
                line.put("formula", formula);
            }
            lines.add(line);
            formulas.put(code, effective);
            Map<String, Object> asEngine = new HashMap<>(pc);
            components.put(code, asEngine);
        }
        if (blank(balancing) != null && !formulas.containsKey(balancing)) {
            throw ApiException.badRequest("The balancing component must be one of the structure's components");
        }
        try {
            PayrollEngine.order(formulas, components);
        } catch (Formula.Error e) {
            throw new ApiException(400, "payroll.formula", e.getMessage());
        }
        return lines;
    }

    // ── pay groups ──────────────────────────────────────────────────────

    static List<Map<String, Object>> groups(Connection c, LocalDate asOf) throws SQLException {
        return Sql.query(c, "select g.id, g.name, g.cutoff_day, g.pay_day, g.default_structure_id, s.name as default_structure_name, "
                + "(select count(*) from app.pay_group_member m join app.employment e on e.id = m.employment_id "
                + "  where m.group_id = g.id and e.status = 'active' and m.valid_from = (select max(m2.valid_from) from app.pay_group_member m2 "
                + "  where m2.employment_id = m.employment_id and m2.valid_from <= ?)) as members "
                + "from app.pay_group g left join app.salary_structure s on s.id = g.default_structure_id order by lower(g.name)", asOf);
    }

    public Map<String, Object> saveGroup(SessionCtx ctx, UUID id, GroupInput in) {
        need(ctx, "payroll.prepare");
        String name = blank(in.name());
        if (name == null || name.length() > 60) {
            throw ApiException.badRequest("Give the pay group a name of up to 60 characters");
        }
        int cutoff = in.cutoffDay() == null ? 25 : in.cutoffDay();
        int pay = in.payDay() == null ? 30 : in.payDay();
        if (cutoff < 1 || cutoff > 28 || pay < 1 || pay > 31) {
            throw ApiException.badRequest("The cut-off is a day from 1 to 28, the pay day from 1 to 31");
        }
        UUID structure = blank(in.defaultStructureId()) == null ? null : id(in.defaultStructureId(), "structure");
        return db.inTenant(ctx.tenantId(), c -> {
            UUID groupId = id;
            if (groupId == null) {
                groupId = AuthService.uuid(Sql.one(c, "insert into app.pay_group (tenant_id, name, cutoff_day, pay_day, default_structure_id) "
                        + "values (?, ?, ?, ?, ?) returning id", ctx.tenantId(), name, (short) cutoff, (short) pay, structure).get("id"));
            } else if (Sql.update(c, "update app.pay_group set name = ?, cutoff_day = ?, pay_day = ?, default_structure_id = ? where id = ?",
                    name, (short) cutoff, (short) pay, structure, groupId) == 0) {
                throw ApiException.notFound("The pay group");
            }
            UserService.audit(c, ctx, id == null ? "payroll.group_added" : "payroll.group_changed", "payroll", groupId, Map.of("name", name));
            return Map.of("id", groupId.toString());
        });
    }

    /** Who is in a pay group today, and who is in no group at all. */
    public Map<String, Object> members(SessionCtx ctx, UUID groupId) {
        needRead(ctx);
        LocalDate today = today();
        return db.inTenant(ctx.tenantId(), c -> Map.of(
                "members", Sql.query(c, "select e.id, coalesce(p.preferred_name, p.legal_name) as name, e.employee_code, m.valid_from "
                        + "from app.pay_group_member m join app.employment e on e.id = m.employment_id join app.person p on p.id = e.person_id "
                        + "where m.group_id = ? and e.status = 'active' and m.valid_from = (select max(m2.valid_from) from app.pay_group_member m2 "
                        + "where m2.employment_id = m.employment_id and m2.valid_from <= ?) order by lower(coalesce(p.preferred_name, p.legal_name))",
                        groupId, today),
                "unassigned", unassigned(c, today)));
    }

    static List<Map<String, Object>> unassigned(Connection c, LocalDate today) throws SQLException {
        return Sql.query(c, "select e.id, coalesce(p.preferred_name, p.legal_name) as name, e.employee_code from app.employment e "
                + "join app.person p on p.id = e.person_id where e.status = 'active' and not exists (select 1 from app.pay_group_member m "
                + "where m.employment_id = e.id and m.valid_from <= greatest(?::date, e.joining_date)) "
                + "order by lower(coalesce(p.preferred_name, p.legal_name))", today);
    }

    /** Puts employees in a group from a date; with no list, everyone active who is in no group yet. */
    public Map<String, Object> addMembers(SessionCtx ctx, UUID groupId, List<String> employmentIds, String validFrom) {
        need(ctx, "payroll.prepare");
        LocalDate today = today();
        return db.inTenant(ctx.tenantId(), c -> {
            if (Sql.one(c, "select 1 from app.pay_group where id = ?", groupId) == null) {
                throw ApiException.notFound("The pay group");
            }
            List<UUID> ids = new ArrayList<>();
            if (employmentIds == null || employmentIds.isEmpty()) {
                for (Map<String, Object> u : unassigned(c, today)) {
                    ids.add(AuthService.uuid(u.get("id")));
                }
            } else {
                for (String s : employmentIds) {
                    ids.add(id(s, "employee"));
                }
            }
            for (UUID e : ids) {
                Map<String, Object> emp = Sql.one(c, "select joining_date from app.employment where id = ? and status in ('active','onboarding')", e);
                if (emp == null) {
                    throw ApiException.notFound("The employee");
                }
                LocalDate joined = LocalDate.parse((String) emp.get("joiningDate"));
                LocalDate from = blank(validFrom) == null ? joined : date(validFrom, "valid from");
                if (from.isBefore(joined)) {
                    from = joined;
                }
                Sql.update(c, "insert into app.pay_group_member (tenant_id, employment_id, group_id, valid_from, created_by) values (?, ?, ?, ?, ?) "
                        + "on conflict (employment_id, valid_from) do update set group_id = excluded.group_id", ctx.tenantId(), e, groupId, from,
                        ctx.membershipId());
            }
            UserService.audit(c, ctx, "payroll.group_members_added", "payroll", groupId, Map.of("count", ids.size()));
            return Map.of("added", ids.size());
        });
    }

    // ── statutory rules ─────────────────────────────────────────────────

    static List<Map<String, Object>> rulesInForce(Connection c, LocalDate asOf) throws SQLException {
        return Sql.query(c, "select id, statute, jurisdiction, valid_from, valid_to, data, status, source, signed_off_by, signed_off_on "
                + "from app.payroll_rule where valid_from <= ? and (valid_to is null or valid_to > ?) order by statute, jurisdiction", asOf, asOf);
    }

    // ── the configuration a run freezes ─────────────────────────────────

    /** Everything a calculation needs about the Organization's setup, as in force on the date. */
    static Map<String, Object> load(Connection c, LocalDate asOf) throws SQLException {
        Map<String, Object> config = new LinkedHashMap<>();
        Map<String, Object> s = Sql.one(c, "select proration, absence_is_lop, pf_restrict, metro_locations, variance_pct, large_one_time "
                + "from app.payroll_settings");
        config.put("settings", s);
        Map<String, Object> components = new LinkedHashMap<>();
        for (Map<String, Object> pc : components(c, asOf)) {
            if (pc.get("retiredAt") != null) {
                continue;
            }
            Map<String, Object> m = new LinkedHashMap<>();
            for (String k : List.of("code", "name", "kind", "statutory", "position", "amountType", "formula", "wage", "esiWage", "taxable",
                    "prorate", "rounding", "oneTime", "showZero", "ledgerCode", "validFrom")) {
                m.put(k, pc.get(k));
            }
            components.put((String) pc.get("code"), m);
        }
        config.put("components", components);
        Map<String, Object> structures = new LinkedHashMap<>();
        for (Map<String, Object> st : structures(c, asOf)) {
            Map<String, Object> m = new LinkedHashMap<>();
            for (String k : List.of("id", "name", "basis", "validFrom", "lines", "balancing")) {
                m.put(k, st.get(k));
            }
            structures.put((String) st.get("id"), m);
        }
        config.put("structures", structures);
        Map<String, Object> tables = new LinkedHashMap<>();
        for (Map<String, Object> t : tables(c)) {
            Map<String, Object> rows = new LinkedHashMap<>();
            for (Map<String, Object> r : PayrollEngine.list(t, "rows")) {
                rows.put((String) r.get("key"), r.get("value"));
            }
            tables.put((String) t.get("name"), rows);
        }
        config.put("tables", tables);
        Map<String, Object> rules = new LinkedHashMap<>();
        Map<String, Object> pt = new LinkedHashMap<>();
        Map<String, Object> lwf = new LinkedHashMap<>();
        Map<String, Object> ids = new LinkedHashMap<>();
        Map<String, Object> sources = new LinkedHashMap<>();
        Map<String, Object> drafts = new LinkedHashMap<>();
        for (Map<String, Object> r : rulesInForce(c, asOf)) {
            String statute = (String) r.get("statute");
            String j = (String) r.get("jurisdiction");
            Object data = Json.parse((String) r.get("data"));
            switch (statute) {
                case "pt" -> pt.put(j, data);
                case "lwf" -> lwf.put(j, data);
                case "income_tax" -> rules.put("tax", data);
                default -> rules.put(statute, data);
            }
            ids.put(statute + ":" + j, r.get("id"));
            sources.put(statute + ":" + j, r.get("source"));
            drafts.put(statute + ":" + j, "draft".equals(r.get("status")));
        }
        rules.put("pt", pt);
        rules.put("lwf", lwf);
        rules.put("ruleIds", ids);
        rules.put("sources", sources);
        rules.put("drafts", drafts);
        config.put("rules", rules);
        List<String> regs = new ArrayList<>();
        for (Map<String, Object> r : Sql.query(c, "select statute, state from app.payroll_registration")) {
            regs.add(r.get("state") == null ? (String) r.get("statute") : r.get("statute") + ":" + r.get("state"));
        }
        config.put("registrations", regs);
        // Round-trip through JSON so a live preview sees exactly what a frozen snapshot would.
        @SuppressWarnings("unchecked")
        Map<String, Object> normal = (Map<String, Object>) Json.parse(Json.write(config));
        return normal;
    }

    // ── helpers ─────────────────────────────────────────────────────────

    /** A change dated into a month already approved would rewrite paid pay; that needs arrears, which are not built yet. */
    static void refuseIntoApproved(Connection c, LocalDate from) throws SQLException {
        Map<String, Object> r = Sql.one(c, "select max(period) as last from app.payroll_run where state in ('approved','paid','released')");
        if (r != null && r.get("last") != null && !from.isAfter(LocalDate.parse((String) r.get("last")).withDayOfMonth(1).plusMonths(1).minusDays(1))) {
            LocalDate next = LocalDate.parse((String) r.get("last")).plusMonths(1);
            throw new ApiException(409, "payroll.after_approved", "Payroll is approved up to " + PayrollRuns.monthWords(LocalDate.parse((String) r.get("last")))
                    + ". A change can apply from " + PayrollRuns.monthWords(next) + " onwards; changes to approved months need arrears, which come in the next release");
        }
    }

    static LocalDate date(String value, String what) {
        try {
            return LocalDate.parse(value.trim());
        } catch (DateTimeParseException | NullPointerException e) {
            throw ApiException.badRequest("The " + what + " date is not a valid date");
        }
    }

    static UUID id(String value, String what) {
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException | NullPointerException e) {
            throw ApiException.badRequest("Not a valid " + what);
        }
    }

    static String blank(String v) {
        return v == null || v.isBlank() ? null : v.trim();
    }

    static String upper(String v) {
        String b = blank(v);
        return b == null ? null : b.toUpperCase();
    }
}
