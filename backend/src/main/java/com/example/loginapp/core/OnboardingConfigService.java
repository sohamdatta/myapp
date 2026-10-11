package com.example.loginapp.core;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.example.loginapp.core.FieldCatalog.CustomField;
import com.example.loginapp.core.FieldCatalog.FieldSetting;
import com.example.loginapp.core.FieldCatalog.SectionSetting;

/**
 * Each Organization's onboarding configuration: settings, the privacy notice,
 * flows with their rules, sections and verification levels, built-in field
 * settings for the Organization and per flow, and custom fields. Reading needs
 * employee.read across the Organization; changing needs onboarding.configure.
 * Every change is audited with what it was before and after.
 */
public final class OnboardingConfigService {

    public record SettingsInput(Boolean offerStage, String inviteWhen, Integer inviteDaysBefore, Integer linkValidDays,
                                String activation, Boolean allowActivationWithoutPan, List<String> rehireMatch,
                                Integer withdrawnRetentionDays, Integer exitedRetentionYears) {}

    public record SectionInput(String section, Boolean enabled, Integer position, String title, String intro, Boolean verify) {}

    public record StepInput(Integer level, String section, String verifier, String roleCode, String membershipId) {}

    public record FlowInput(String name, Map<String, Object> appliesTo, String welcomeMessage, String welcomeVideoUrl,
                            String dayOneNote, String hrContactId, Integer verificationLevels, Boolean active,
                            List<SectionInput> sections, List<StepInput> steps) {}

    public record FieldInput(String key, String visibility, String label, String help, String editableBy) {}

    public record CustomFieldInput(String key, String label, String help, String section, String dataType, List<String> options,
                                   String pattern, BigDecimal minValue, BigDecimal maxValue, Boolean sensitive,
                                   Boolean managerCanSee, Map<String, Object> appliesTo, String visibility, String editableBy,
                                   Integer position, Boolean active) {}

    public record DocumentTypeInput(String name, String required, Integer withinDays, String instructions,
                                    Map<String, Object> appliesTo, Integer position, Boolean active) {}

    /** A checklist template and its tasks. A task with an id changes that task; one without is added; one left out is removed. */
    public record ChecklistInput(String name, Map<String, Object> appliesTo, Boolean active, List<TaskInput> tasks) {}

    public record TaskInput(String id, String title, String instructions, String owner, String ownerRoleCode, String ownerMembershipId,
                            String triggerEvent, Integer offsetDays, Boolean required, Boolean needsAttachment, String approver,
                            String approverRoleCode, String approverMembershipId, Map<String, Object> appliesTo) {}

    private static final Set<String> INVITE_WHEN = Set.of("manual", "on_create", "on_offer_accepted", "days_before_joining");
    private static final Set<String> REHIRE = Set.of("pan", "personal_email", "mobile", "aadhaar_last4");

    private final Db db;

    public OnboardingConfigService(Db db) {
        this.db = db;
    }

    // ── settings and notice ─────────────────────────────────────────────

    public Map<String, Object> settings(SessionCtx ctx) {
        requireRead(ctx);
        return db.inTenant(ctx.tenantId(), c -> {
            OnboardingDefaults.ensure(c, ctx.tenantId());
            Map<String, Object> s = settingsRow(c);
            s.put("notice", Sql.one(c, "select id, version, body, published_at from app.privacy_notice order by version desc limit 1"));
            return s;
        });
    }

    public Map<String, Object> saveSettings(SessionCtx ctx, SettingsInput in) {
        requireConfigure(ctx);
        return db.inTenant(ctx.tenantId(), c -> {
            OnboardingDefaults.ensure(c, ctx.tenantId());
            Map<String, Object> before = settingsRow(c);
            boolean offer = in.offerStage() == null ? (Boolean) before.get("offerStage") : in.offerStage();
            String inviteWhen = in.inviteWhen() == null ? (String) before.get("inviteWhen") : in.inviteWhen().trim();
            if (!INVITE_WHEN.contains(inviteWhen)) {
                throw bad("Invite when must be manual, on_create, on_offer_accepted or days_before_joining");
            }
            if (inviteWhen.equals("on_offer_accepted") && !offer) {
                throw bad("Inviting when the offer is accepted needs the offer stage switched on");
            }
            Integer daysBefore = inviteWhen.equals("days_before_joining")
                    ? range(in.inviteDaysBefore() != null ? in.inviteDaysBefore() : number(before.get("inviteDaysBefore")), 1, 90,
                            "Days before joining")
                    : null;
            int linkDays = range(in.linkValidDays() != null ? in.linkValidDays() : number(before.get("linkValidDays")), 1, 120,
                    "Link validity in days");
            String activation = in.activation() == null ? (String) before.get("activation") : in.activation().trim();
            if (!Set.of("automatic", "manual").contains(activation)) {
                throw bad("Activation must be automatic or manual");
            }
            boolean withoutPan = in.allowActivationWithoutPan() == null
                    ? (Boolean) before.get("allowActivationWithoutPan") : in.allowActivationWithoutPan();
            List<String> rehire = in.rehireMatch() == null ? stringList(before.get("rehireMatch")) : in.rehireMatch();
            for (String r : rehire) {
                if (!REHIRE.contains(r)) {
                    throw bad("Rehire matching can use pan, personal_email, mobile and aadhaar_last4");
                }
            }
            int withdrawnDays = range(in.withdrawnRetentionDays() != null ? in.withdrawnRetentionDays()
                    : number(before.get("withdrawnRetentionDays")), 30, 3650, "Days to keep a withdrawn joiner's data");
            int exitedYears = range(in.exitedRetentionYears() != null ? in.exitedRetentionYears()
                    : number(before.get("exitedRetentionYears")), 5, 30, "Years to keep a former employee's records");
            Sql.update(c, "update app.onboarding_settings set offer_stage = ?, invite_when = ?, invite_days_before = ?, "
                            + "link_valid_days = ?, activation = ?, allow_activation_without_pan = ?, rehire_match = ?, "
                            + "withdrawn_retention_days = ?, exited_retention_years = ?, updated_by = ?, updated_at = now()",
                    offer, inviteWhen, daysBefore == null ? null : daysBefore.shortValue(), (short) linkDays, activation, withoutPan,
                    rehire.stream().distinct().toArray(String[]::new), (short) withdrawnDays, (short) exitedYears, ctx.membershipId());
            Map<String, Object> after = settingsRow(c);
            audit(c, ctx, "onboarding.settings_changed", "onboarding_settings", null, before, after);
            return after;
        });
    }

    /** Publishes a new version of the privacy notice. Versions already accepted stay as they were. */
    public Map<String, Object> publishNotice(SessionCtx ctx, String body) {
        requireConfigure(ctx);
        if (body == null || body.trim().length() < 50) {
            throw bad("The notice needs at least 50 characters");
        }
        if (body.length() > 20000) {
            throw bad("Keep the notice to 20,000 characters");
        }
        return db.inTenant(ctx.tenantId(), c -> {
            OnboardingDefaults.ensure(c, ctx.tenantId());
            Map<String, Object> row = Sql.one(c, "insert into app.privacy_notice (tenant_id, version, body, published_by) "
                    + "select ?, coalesce(max(version), 0) + 1, ?, ? from app.privacy_notice returning id, version, published_at",
                    ctx.tenantId(), body.trim(), ctx.membershipId());
            audit(c, ctx, "onboarding.notice_published", "privacy_notice", AuthService.uuid(row.get("id")),
                    null, Map.of("version", row.get("version")));
            return row;
        });
    }

    // ── flows ───────────────────────────────────────────────────────────

    public List<Map<String, Object>> flows(SessionCtx ctx) {
        requireRead(ctx);
        return db.inTenant(ctx.tenantId(), c -> {
            OnboardingDefaults.ensure(c, ctx.tenantId());
            Map<UUID, String> names = names(c);
            List<Map<String, Object>> out = new ArrayList<>();
            for (Map<String, Object> f : Sql.query(c, "select id, name, applies_to, position, welcome_message, welcome_video_url, "
                    + "day_one_note, hr_contact_id, verification_levels, active, updated_at from app.onboarding_flow "
                    + "order by position, lower(name)")) {
                out.add(flowDetail(c, f, names));
            }
            return out;
        });
    }

    public Map<String, Object> saveFlow(SessionCtx ctx, UUID flowId, FlowInput in) {
        requireConfigure(ctx);
        return db.inTenant(ctx.tenantId(), c -> {
            OnboardingDefaults.ensure(c, ctx.tenantId());
            Map<UUID, String> names = names(c);
            Map<String, Object> before = null;
            Map<String, Object> current = null;
            if (flowId != null) {
                current = Sql.one(c, "select * from app.onboarding_flow where id = ? for update", flowId);
                if (current == null) {
                    throw ApiException.notFound("The flow");
                }
                before = flowDetail(c, current, names);
            }
            String name = in.name() != null ? text(in.name(), "the flow's name", 80, true)
                    : current == null ? text(null, "the flow's name", 80, true) : (String) current.get("name");
            AppliesTo rules = in.appliesTo() != null ? checkedRules(c, AppliesTo.from(in.appliesTo()))
                    : current == null ? AppliesTo.EVERYONE : AppliesTo.parse((String) current.get("appliesTo"));
            String video = in.welcomeVideoUrl() != null ? text(in.welcomeVideoUrl(), "the video link", 500, false)
                    : current == null ? null : (String) current.get("welcomeVideoUrl");
            if (video != null && !video.startsWith("https://")) {
                throw bad("The video link must start with https://");
            }
            String message = in.welcomeMessage() != null ? text(in.welcomeMessage(), "the welcome message", 4000, false)
                    : current == null ? null : (String) current.get("welcomeMessage");
            String dayOne = in.dayOneNote() != null ? text(in.dayOneNote(), "the day-one note", 2000, false)
                    : current == null ? null : (String) current.get("dayOneNote");
            UUID hrContact = in.hrContactId() != null
                    ? (in.hrContactId().isBlank() ? null : activeMember(c, in.hrContactId(), "HR contact"))
                    : current == null ? null : AuthService.uuid(current.get("hrContactId"));
            int levels = in.verificationLevels() != null ? range(in.verificationLevels(), 0, 3, "Verification levels")
                    : current == null ? 1 : number(current.get("verificationLevels"));
            boolean active = in.active() != null ? in.active() : current == null || (Boolean) current.get("active");

            UUID id = flowId;
            if (id == null) {
                // A flow with rules goes just before the first flow for everyone, so it is tried before the catch-all;
                // a flow for everyone goes last.
                int position;
                Map<String, Object> catchAll = Sql.one(c, "select min(position) as p from app.onboarding_flow where applies_to = '{}'::jsonb");
                if (rules.appliesToEveryone() || catchAll.get("p") == null) {
                    position = number(Sql.one(c, "select coalesce(max(position), 0) + 1 as p from app.onboarding_flow").get("p"));
                } else {
                    position = number(catchAll.get("p"));
                    Sql.update(c, "update app.onboarding_flow set position = position + 1 where position >= ?", (short) position);
                }
                id = AuthService.uuid(Sql.one(c, "insert into app.onboarding_flow (tenant_id, name, applies_to, position, welcome_message, "
                                + "welcome_video_url, day_one_note, hr_contact_id, verification_levels, active) "
                                + "values (?, ?, ?::jsonb, ?, ?, ?, ?, ?, ?, ?) returning id",
                        ctx.tenantId(), name, rules.toJson(), (short) position, message, video, dayOne, hrContact, (short) levels, active).get("id"));
                OnboardingDefaults.insertSections(c, ctx.tenantId(), id);
                if (in.steps() == null && levels > 0) {
                    for (int level = 1; level <= levels; level++) {
                        Sql.update(c, "insert into app.verification_step (tenant_id, flow_id, level, verifier, role_id) "
                                + "select ?, ?, ?, 'role', r.id from app.role r where r.code = 'hr_admin' and r.tenant_id is null",
                                ctx.tenantId(), id, (short) level);
                    }
                }
            } else {
                Sql.update(c, "update app.onboarding_flow set name = ?, applies_to = ?::jsonb, welcome_message = ?, welcome_video_url = ?, "
                                + "day_one_note = ?, hr_contact_id = ?, verification_levels = ?, active = ?, updated_at = now() where id = ?",
                        name, rules.toJson(), message, video, dayOne, hrContact, (short) levels, active, id);
            }
            if (in.sections() != null) {
                saveSections(c, id, in.sections());
            }
            if (in.steps() != null) {
                saveSteps(c, ctx, id, levels, in.steps());
            } else if (flowId != null) {
                // Fewer levels than before: drop the steps for levels that no longer exist.
                Sql.update(c, "delete from app.verification_step where flow_id = ? and level > ?", id, (short) levels);
            }
            checkStepsCover(c, id, levels);
            requireCatchAll(c);
            Map<String, Object> after = flowDetail(c, Sql.one(c, "select * from app.onboarding_flow where id = ?", id), names);
            audit(c, ctx, flowId == null ? "onboarding.flow_created" : "onboarding.flow_changed", "onboarding_flow", id, before, after);
            return after;
        });
    }

    /** The order flows are tried in. Every flow must be listed once. */
    public List<Map<String, Object>> orderFlows(SessionCtx ctx, List<String> ids) {
        requireConfigure(ctx);
        db.inTenant(ctx.tenantId(), c -> {
            OnboardingDefaults.ensure(c, ctx.tenantId());
            List<String> existing = Sql.query(c, "select id from app.onboarding_flow order by position").stream()
                    .map(r -> (String) r.get("id")).toList();
            if (ids == null || ids.size() != existing.size() || !new HashSet<>(ids).equals(new HashSet<>(existing))) {
                throw bad("List every flow exactly once");
            }
            for (int i = 0; i < ids.size(); i++) {
                Sql.update(c, "update app.onboarding_flow set position = ?, updated_at = now() where id = ?::uuid", (short) (i + 1), ids.get(i));
            }
            audit(c, ctx, "onboarding.flows_ordered", "onboarding_flow", null, Map.of("order", existing), Map.of("order", ids));
            return null;
        });
        return flows(ctx);
    }

    // ── built-in fields ─────────────────────────────────────────────────

    /** Every built-in field with the Organization's setting, the flow's override (when a flow is given) and the result. */
    public List<Map<String, Object>> fields(SessionCtx ctx, UUID flowId) {
        requireRead(ctx);
        return db.inTenant(ctx.tenantId(), c -> {
            OnboardingDefaults.ensure(c, ctx.tenantId());
            if (flowId != null) {
                requireFlow(c, flowId);
            }
            Map<String, FieldSetting> org = fieldSettings(c, null);
            Map<String, FieldSetting> flow = flowId == null ? Map.of() : fieldSettings(c, flowId);
            List<Map<String, Object>> out = new ArrayList<>();
            for (FieldCatalog.Field f : FieldCatalog.fields()) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("key", f.key());
                row.put("section", f.section());
                row.put("builtIn", Map.of("label", f.label(), "visibility", f.visibility(), "editableBy", f.editableBy(),
                        "type", f.type()));
                row.put("fixed", f.fixed());
                row.put("organization", setting(org.get(f.key())));
                if (flowId != null) {
                    row.put("flow", setting(flow.get(f.key())));
                }
                row.put("effective", FieldCatalog.effective(f, org.get(f.key()), flow.get(f.key())));
                out.add(row);
            }
            return out;
        });
    }

    /**
     * Saves settings for some built-in fields, for the Organization (flowId null) or one flow.
     * A field given with nothing set (no visibility, label, help or editor) goes back to the layer below.
     */
    public List<Map<String, Object>> saveFields(SessionCtx ctx, UUID flowId, List<FieldInput> fields) {
        requireConfigure(ctx);
        if (fields == null || fields.isEmpty()) {
            throw bad("Give at least one field");
        }
        db.inTenant(ctx.tenantId(), c -> {
            OnboardingDefaults.ensure(c, ctx.tenantId());
            if (flowId != null) {
                requireFlow(c, flowId);
            }
            Map<String, FieldSetting> before = fieldSettings(c, flowId);
            Map<String, Object> was = new LinkedHashMap<>();
            Map<String, Object> now = new LinkedHashMap<>();
            for (FieldInput in : fields) {
                FieldSetting s = FieldCatalog.check(new FieldSetting(in.key(), in.visibility(), in.label(), in.help(), in.editableBy()));
                was.put(s.key(), setting(before.get(s.key())));
                String flowMatch = flowId == null ? "flow_id is null" : "flow_id = ?";
                Object[] keyParams = flowId == null ? new Object[] {s.key()} : new Object[] {s.key(), flowId};
                Sql.update(c, "delete from app.field_setting where field_key = ? and " + flowMatch, keyParams);
                if (s.visibility() != null || s.label() != null || s.help() != null || s.editableBy() != null) {
                    String visibility = s.visibility() != null ? s.visibility()
                            : flowId == null ? FieldCatalog.field(s.key()).visibility()
                            : (String) FieldCatalog.effective(FieldCatalog.field(s.key()), fieldSettings(c, null).get(s.key()), null).get("visibility");
                    Sql.update(c, "insert into app.field_setting (tenant_id, flow_id, field_key, visibility, label, help, editable_by) "
                                    + "values (?, ?, ?, ?, ?, ?, coalesce(?, 'joiner'))",
                            ctx.tenantId(), flowId, s.key(), visibility, s.label(), s.help(),
                            s.editableBy() != null ? s.editableBy() : FieldCatalog.field(s.key()).editableBy());
                }
                now.put(s.key(), setting(fieldSettings(c, flowId).get(s.key())));
            }
            audit(c, ctx, "onboarding.fields_changed", flowId == null ? "organization" : "onboarding_flow", flowId, was, now);
            return null;
        });
        return fields(ctx, flowId);
    }

    // ── custom fields ───────────────────────────────────────────────────

    public List<Map<String, Object>> customFields(SessionCtx ctx) {
        requireRead(ctx);
        return db.inTenant(ctx.tenantId(), c -> {
            OnboardingDefaults.ensure(c, ctx.tenantId());
            Map<UUID, String> names = names(c);
            List<Map<String, Object>> out = new ArrayList<>();
            for (Map<String, Object> row : Sql.query(c, "select * from app.custom_field order by section, position, lower(label)")) {
                row.put("rules", AppliesTo.parse((String) row.get("appliesTo")).describe(names::get));
                row.put("appliesTo", AppliesTo.parse((String) row.get("appliesTo")).rules());
                out.add(row);
            }
            return out;
        });
    }

    public Map<String, Object> saveCustomField(SessionCtx ctx, UUID id, CustomFieldInput in) {
        requireConfigure(ctx);
        return db.inTenant(ctx.tenantId(), c -> {
            OnboardingDefaults.ensure(c, ctx.tenantId());
            Map<String, Object> current = null;
            if (id != null) {
                current = Sql.one(c, "select * from app.custom_field where id = ? for update", id);
                if (current == null) {
                    throw ApiException.notFound("The field");
                }
            }
            String key = id == null ? in.key() == null ? null : in.key().trim() : (String) current.get("fieldKey");
            if (key == null || !key.matches("^[a-z][a-z0-9_]{1,40}$")) {
                throw bad("The key must be 2 to 41 lower-case letters, digits or underscores, starting with a letter");
            }
            if (id != null && in.key() != null && !in.key().trim().equals(key)) {
                throw bad("The key of a field cannot change once values may have been saved under it");
            }
            String dataType = id == null ? in.dataType() : (String) current.get("dataType");
            if (id != null && in.dataType() != null && !in.dataType().equals(dataType)) {
                throw bad("The type of a field cannot change; add a new field instead");
            }
            boolean sensitive = id == null ? Boolean.TRUE.equals(in.sensitive()) : (Boolean) current.get("sensitive");
            if (id != null && in.sensitive() != null && in.sensitive() != sensitive) {
                throw bad("Whether a field is sensitive cannot change once values may have been saved");
            }
            String label = in.label() != null ? text(in.label(), "the label", 80, true) : current == null
                    ? text(null, "the label", 80, true) : (String) current.get("label");
            String help = in.help() != null ? text(in.help(), "the help text", 300, false) : current == null ? null : (String) current.get("help");
            String section = in.section() != null ? in.section().trim() : current == null ? null : (String) current.get("section");
            if (section == null || !FieldCatalog.FIELD_SECTIONS.contains(section)) {
                throw bad("Choose a section: " + String.join(", ", FieldCatalog.FIELD_SECTIONS));
            }
            List<String> options = in.options() != null ? in.options().stream().map(String::trim).toList()
                    : current == null ? null : stringList(current.get("options"));
            if (options != null && options.isEmpty()) {
                options = null;
            }
            String pattern = in.pattern() != null ? (in.pattern().isBlank() ? null : in.pattern()) : current == null ? null : (String) current.get("pattern");
            BigDecimal min = in.minValue() != null ? in.minValue() : current == null ? null : decimal(current.get("minValue"));
            BigDecimal max = in.maxValue() != null ? in.maxValue() : current == null ? null : decimal(current.get("maxValue"));
            CustomValues.checkDefinition(dataType, options, pattern, min, max);
            boolean managerCanSee = in.managerCanSee() != null ? in.managerCanSee() : current != null && (Boolean) current.get("managerCanSee");
            if (sensitive && managerCanSee) {
                throw bad("A sensitive field cannot be shown to managers");
            }
            AppliesTo rules = in.appliesTo() != null ? checkedRules(c, AppliesTo.from(in.appliesTo()))
                    : current == null ? AppliesTo.EVERYONE : AppliesTo.parse((String) current.get("appliesTo"));
            String visibility = in.visibility() != null ? in.visibility().trim() : current == null ? "optional" : (String) current.get("visibility");
            if (!Set.of("optional", "required").contains(visibility)) {
                throw bad("A custom field is optional or required; to hide it, stop using it");
            }
            String editableBy = in.editableBy() != null ? in.editableBy().trim() : current == null ? "joiner" : (String) current.get("editableBy");
            if (!Set.of("joiner", "hr").contains(editableBy)) {
                throw bad("Editable by must be joiner or hr");
            }
            if (section.equals("employment") && editableBy.equals("joiner")) {
                editableBy = "hr";   // employment details are always HR's
            }
            int position = in.position() != null ? range(in.position(), 0, 999, "Position") : current == null ? 0 : number(current.get("position"));
            boolean active = in.active() != null ? in.active() : current == null || (Boolean) current.get("active");
            Map<String, Object> before = current;
            UUID saved;
            if (id == null) {
                if (Sql.one(c, "select 1 from app.custom_field where field_key = ?", key) != null) {
                    throw new ApiException(409, "field.key_taken", "A field with the key " + key + " already exists");
                }
                saved = AuthService.uuid(Sql.one(c, "insert into app.custom_field (tenant_id, field_key, label, help, section, data_type, "
                                + "options, pattern, min_value, max_value, sensitive, manager_can_see, applies_to, visibility, editable_by, "
                                + "position, active) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?, ?) returning id",
                        ctx.tenantId(), key, label, help, section, dataType, options == null ? null : options.toArray(String[]::new),
                        pattern, min, max, sensitive, managerCanSee, rules.toJson(), visibility, editableBy, (short) position, active).get("id"));
            } else {
                saved = id;
                Sql.update(c, "update app.custom_field set label = ?, help = ?, section = ?, options = ?, pattern = ?, min_value = ?, "
                                + "max_value = ?, manager_can_see = ?, applies_to = ?::jsonb, visibility = ?, editable_by = ?, position = ?, "
                                + "active = ? where id = ?",
                        label, help, section, options == null ? null : options.toArray(String[]::new), pattern, min, max, managerCanSee,
                        rules.toJson(), visibility, editableBy, (short) position, active, id);
            }
            Map<String, Object> after = Sql.one(c, "select * from app.custom_field where id = ?", saved);
            audit(c, ctx, id == null ? "onboarding.custom_field_created" : "onboarding.custom_field_changed", "custom_field", saved,
                    before, after);
            after.put("appliesTo", rules.rules());
            return after;
        });
    }

    // ── last changes ────────────────────────────────────────────────────

    private static final Map<String, String> AREAS = Map.ofEntries(
            Map.entry("settings", "'onboarding.settings_changed','onboarding.notice_published'"),
            Map.entry("flows", "'onboarding.flow_created','onboarding.flow_changed','onboarding.flows_ordered'"),
            Map.entry("form", "'onboarding.fields_changed','onboarding.custom_field_created','onboarding.custom_field_changed'"),
            Map.entry("documents", "'onboarding.document_type_created','onboarding.document_type_changed'"),
            Map.entry("checklists", "'onboarding.checklist_created','onboarding.checklist_changed'"),
            Map.entry("letters", "'onboarding.letter_created','onboarding.letter_changed'"),
            Map.entry("policies", "'onboarding.policy_created','onboarding.policy_changed'"),
            Map.entry("messages", "'onboarding.message_changed'"),
            Map.entry("codes", "'onboarding.series_created','onboarding.series_changed'"));

    /** For each area of setup, who last changed it and when, for the line under each screen's title. */
    public Map<String, Object> lastChanges(SessionCtx ctx) {
        requireRead(ctx);
        return db.inTenant(ctx.tenantId(), c -> {
            Map<String, Object> out = new LinkedHashMap<>();
            for (Map.Entry<String, String> a : AREAS.entrySet()) {
                out.put(a.getKey(), Sql.one(c, "select at, actor_label from app.audit_event where action in (" + a.getValue() + ") "
                        + "order by at desc, id desc limit 1"));
            }
            return out;
        });
    }

    // ── document types and checklists ───────────────────────────────────

    public List<Map<String, Object>> documentTypes(SessionCtx ctx) {
        requireRead(ctx);
        return db.inTenant(ctx.tenantId(), c -> {
            OnboardingDefaults.ensure(c, ctx.tenantId());
            Map<UUID, String> names = names(c);
            List<Map<String, Object>> out = new ArrayList<>();
            for (Map<String, Object> row : Sql.query(c, "select * from app.document_type order by position, lower(name)")) {
                AppliesTo rules = AppliesTo.parse((String) row.get("appliesTo"));
                row.put("rules", rules.describe(names::get));
                row.put("appliesTo", rules.rules());
                out.add(row);
            }
            return out;
        });
    }

    public Map<String, Object> saveDocumentType(SessionCtx ctx, UUID id, DocumentTypeInput in) {
        requireConfigure(ctx);
        return db.inTenant(ctx.tenantId(), c -> {
            OnboardingDefaults.ensure(c, ctx.tenantId());
            Map<String, Object> current = null;
            if (id != null) {
                current = Sql.one(c, "select * from app.document_type where id = ? for update", id);
                if (current == null) {
                    throw ApiException.notFound("The document type");
                }
            }
            String name = in.name() != null ? text(in.name(), "the name", 80, true)
                    : current == null ? text(null, "the name", 80, true) : (String) current.get("name");
            String required = in.required() != null ? in.required().trim() : current == null ? "optional" : (String) current.get("required");
            if (!Set.of("before_activation", "within_days", "optional").contains(required)) {
                throw bad("Required must be before_activation, within_days or optional");
            }
            Integer within = null;
            if (required.equals("within_days")) {
                Integer given = in.withinDays() != null ? in.withinDays()
                        : current == null || current.get("withinDays") == null ? null : number(current.get("withinDays"));
                within = range(given, 1, 365, "Within days");
            }
            String instructions = in.instructions() != null ? text(in.instructions(), "the instructions", 500, false)
                    : current == null ? null : (String) current.get("instructions");
            AppliesTo rules = in.appliesTo() != null ? checkedRules(c, AppliesTo.from(in.appliesTo()))
                    : current == null ? AppliesTo.EVERYONE : AppliesTo.parse((String) current.get("appliesTo"));
            int position = in.position() != null ? range(in.position(), 0, 999, "Position") : current == null ? 0 : number(current.get("position"));
            boolean active = in.active() != null ? in.active() : current == null || (Boolean) current.get("active");
            UUID saved;
            if (id == null) {
                saved = AuthService.uuid(Sql.one(c, "insert into app.document_type (tenant_id, name, required, within_days, instructions, "
                        + "applies_to, position, active) values (?, ?, ?, ?, ?, ?::jsonb, ?, ?) returning id", ctx.tenantId(), name, required,
                        within == null ? null : within.shortValue(), instructions, rules.toJson(), (short) position, active).get("id"));
            } else {
                saved = id;
                Sql.update(c, "update app.document_type set name = ?, required = ?, within_days = ?, instructions = ?, applies_to = ?::jsonb, "
                        + "position = ?, active = ? where id = ?", name, required, within == null ? null : within.shortValue(), instructions,
                        rules.toJson(), (short) position, active, id);
            }
            Map<String, Object> after = Sql.one(c, "select * from app.document_type where id = ?", saved);
            audit(c, ctx, id == null ? "onboarding.document_type_created" : "onboarding.document_type_changed", "document_type", saved,
                    current, after);
            after.put("appliesTo", rules.rules());
            return after;
        });
    }

    public List<Map<String, Object>> checklists(SessionCtx ctx) {
        requireRead(ctx);
        return db.inTenant(ctx.tenantId(), c -> {
            OnboardingDefaults.ensure(c, ctx.tenantId());
            Map<UUID, String> names = names(c);
            List<Map<String, Object>> out = new ArrayList<>();
            for (Map<String, Object> row : Sql.query(c, "select * from app.checklist_template order by lower(name)")) {
                out.add(checklistDetail(c, row, names));
            }
            return out;
        });
    }

    private static final Set<String> OWNERS = Set.of("hr", "it", "manager", "buddy", "joiner", "role", "user");
    private static final Set<String> TRIGGERS = Set.of("added", "offer_accepted", "invited", "joining_date", "activated");
    private static final Set<String> APPROVERS = Set.of("hr", "manager", "role", "user");

    public Map<String, Object> saveChecklist(SessionCtx ctx, UUID id, ChecklistInput in) {
        requireConfigure(ctx);
        return db.inTenant(ctx.tenantId(), c -> {
            OnboardingDefaults.ensure(c, ctx.tenantId());
            Map<String, Object> current = null;
            if (id != null) {
                current = Sql.one(c, "select * from app.checklist_template where id = ? for update", id);
                if (current == null) {
                    throw ApiException.notFound("The checklist");
                }
            }
            Map<UUID, String> names = names(c);
            Map<String, Object> before = current == null ? null : checklistDetail(c, current, names);
            String name = in.name() != null ? text(in.name(), "the name", 80, true)
                    : current == null ? text(null, "the name", 80, true) : (String) current.get("name");
            AppliesTo rules = in.appliesTo() != null ? checkedRules(c, AppliesTo.from(in.appliesTo()))
                    : current == null ? AppliesTo.EVERYONE : AppliesTo.parse((String) current.get("appliesTo"));
            boolean active = in.active() != null ? in.active() : current == null || (Boolean) current.get("active");
            UUID saved;
            if (id == null) {
                saved = AuthService.uuid(Sql.one(c, "insert into app.checklist_template (tenant_id, name, applies_to, active) "
                        + "values (?, ?, ?::jsonb, ?) returning id", ctx.tenantId(), name, rules.toJson(), active).get("id"));
            } else {
                saved = id;
                Sql.update(c, "update app.checklist_template set name = ?, applies_to = ?::jsonb, active = ? where id = ?",
                        name, rules.toJson(), active, id);
            }
            if (in.tasks() != null) {
                if (in.tasks().size() > 50) {
                    throw bad("A checklist has at most 50 tasks");
                }
                java.util.Set<UUID> kept = new java.util.HashSet<>();
                int position = 1;
                for (TaskInput t : in.tasks()) {
                    kept.add(saveTemplateTask(c, ctx, saved, t, position++));
                }
                // Removed tasks: joiners who already have them keep them, no longer linked to the template.
                for (Map<String, Object> r : Sql.query(c, "select id from app.checklist_template_task where template_id = ?", saved)) {
                    UUID taskId = AuthService.uuid(r.get("id"));
                    if (!kept.contains(taskId)) {
                        Sql.update(c, "update app.onboarding_task set template_task_id = null where template_task_id = ?", taskId);
                        Sql.update(c, "delete from app.checklist_template_task where id = ?", taskId);
                    }
                }
            }
            Map<String, Object> after = checklistDetail(c, Sql.one(c, "select * from app.checklist_template where id = ?", saved), names);
            audit(c, ctx, id == null ? "onboarding.checklist_created" : "onboarding.checklist_changed", "checklist_template", saved,
                    before, after);
            return after;
        });
    }

    private static UUID saveTemplateTask(Connection c, SessionCtx ctx, UUID templateId, TaskInput t, int position) throws SQLException {
        String title = text(t.title(), "every task's title", 120, true);
        String owner = t.owner() == null ? "hr" : t.owner().trim();
        if (!OWNERS.contains(owner)) {
            throw bad(title + ": the owner must be hr, it, manager, buddy, joiner, role or user");
        }
        UUID ownerRole = owner.equals("role") ? roleId(c, t.ownerRoleCode(), title) : null;
        UUID ownerUser = owner.equals("user") ? memberId(c, t.ownerMembershipId(), title) : null;
        String trigger = t.triggerEvent() == null ? "joining_date" : t.triggerEvent().trim();
        if (!TRIGGERS.contains(trigger)) {
            throw bad(title + ": the trigger must be added, offer_accepted, invited, joining_date or activated");
        }
        int offset = t.offsetDays() == null ? 0 : range(t.offsetDays(), -90, 180, title + ": the offset in days");
        String approver = t.approver() == null || t.approver().isBlank() ? null : t.approver().trim();
        if (approver != null && !APPROVERS.contains(approver)) {
            throw bad(title + ": the approver must be hr, manager, role or user");
        }
        UUID approverRole = "role".equals(approver) ? roleId(c, t.approverRoleCode(), title) : null;
        UUID approverUser = "user".equals(approver) ? memberId(c, t.approverMembershipId(), title) : null;
        AppliesTo rules = t.appliesTo() == null ? AppliesTo.EVERYONE : checkedRules(c, AppliesTo.from(t.appliesTo()));
        Object[] values = {title, t.instructions() == null || t.instructions().isBlank() ? null : t.instructions().trim(), owner, ownerRole,
            ownerUser, trigger, (short) offset, !Boolean.FALSE.equals(t.required()), Boolean.TRUE.equals(t.needsAttachment()), approver,
            approverRole, approverUser, rules.toJson(), (short) position};
        UUID id = null;
        if (t.id() != null && !t.id().isBlank()) {
            try {
                id = UUID.fromString(t.id().trim());
            } catch (IllegalArgumentException e) {
                throw bad(title + ": not a valid task id");
            }
            if (Sql.one(c, "select 1 from app.checklist_template_task where id = ? and template_id = ?", id, templateId) == null) {
                throw bad(title + ": that task is not in this checklist");
            }
            Object[] params = java.util.Arrays.copyOf(values, values.length + 1);
            params[values.length] = id;
            Sql.update(c, "update app.checklist_template_task set title = ?, instructions = ?, owner = ?, owner_role_id = ?, "
                    + "owner_membership_id = ?, trigger_event = ?, offset_days = ?, required = ?, needs_attachment = ?, approver = ?, "
                    + "approver_role_id = ?, approver_membership_id = ?, applies_to = ?::jsonb, position = ? where id = ?", params);
            return id;
        }
        Object[] params = new Object[values.length + 2];
        params[0] = ctx.tenantId();
        params[1] = templateId;
        System.arraycopy(values, 0, params, 2, values.length);
        return AuthService.uuid(Sql.one(c, "insert into app.checklist_template_task (tenant_id, template_id, title, instructions, owner, "
                + "owner_role_id, owner_membership_id, trigger_event, offset_days, required, needs_attachment, approver, approver_role_id, "
                + "approver_membership_id, applies_to, position) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?) returning id",
                params).get("id"));
    }

    private static UUID roleId(Connection c, String code, String title) throws SQLException {
        Map<String, Object> r = code == null ? null : Sql.one(c, "select id from app.role where code = ? and code not like 'support%'", code.trim());
        if (r == null) {
            throw bad(title + ": choose a role");
        }
        return AuthService.uuid(r.get("id"));
    }

    private static UUID memberId(Connection c, String id, String title) throws SQLException {
        try {
            UUID m = UUID.fromString(id == null ? "" : id.trim());
            if (Sql.one(c, "select 1 from app.membership where id = ? and status = 'active'", m) != null) {
                return m;
            }
        } catch (IllegalArgumentException e) {
            // fall through
        }
        throw bad(title + ": choose a user of the Organization");
    }

    private static Map<String, Object> checklistDetail(Connection c, Map<String, Object> row, Map<UUID, String> names) throws SQLException {
        Map<String, Object> out = new LinkedHashMap<>(row);
        AppliesTo rules = AppliesTo.parse((String) row.get("appliesTo"));
        out.put("rules", rules.describe(names::get));
        out.put("appliesTo", rules.rules());
        List<Map<String, Object>> tasks = new ArrayList<>();
        for (Map<String, Object> t : Sql.query(c, "select t.*, r.code as owner_role_code, ar.code as approver_role_code "
                + "from app.checklist_template_task t left join app.role r on r.id = t.owner_role_id "
                + "left join app.role ar on ar.id = t.approver_role_id where t.template_id = ?::uuid order by t.position", row.get("id"))) {
            t.put("appliesTo", AppliesTo.parse((String) t.get("appliesTo")).rules());
            tasks.add(t);
        }
        out.put("tasks", tasks);
        return out;
    }

    // ── the form ────────────────────────────────────────────────────────

    /**
     * The form a joiner would see. With a flow id, that flow's form; without one, the flow the
     * rules pick for the joiner described. Either way custom fields follow the joiner's attributes.
     */
    public Map<String, Object> preview(SessionCtx ctx, UUID flowId, AppliesTo.Subject subject) {
        requireRead(ctx);
        return db.inTenant(ctx.tenantId(), c -> {
            OnboardingDefaults.ensure(c, ctx.tenantId());
            UUID flow = flowId != null ? requireFlow(c, flowId) : pickFlow(c, subject);
            Map<String, Object> f = Sql.one(c, "select id, name, welcome_message, welcome_video_url, day_one_note, verification_levels "
                    + "from app.onboarding_flow where id = ?", flow);
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("flow", f);
            out.put("sections", form(c, flow, subject));
            return out;
        });
    }

    /** The first active flow, by position, whose rules match. A catch-all flow always exists, so there is always one. */
    public static UUID pickFlow(Connection c, AppliesTo.Subject subject) throws SQLException {
        List<Map<String, Object>> flows = Sql.query(c,
                "select id, applies_to from app.onboarding_flow where active order by position, lower(name)");
        Map<String, Object> chosen = AppliesTo.firstMatch(flows, f -> AppliesTo.parse((String) f.get("appliesTo")), subject);
        if (chosen == null) {
            throw new ApiException(409, "onboarding.no_flow", "No onboarding flow applies to this joiner. Add a flow for everyone");
        }
        return AuthService.uuid(chosen.get("id"));
    }

    /** The effective form for a flow and a joiner, built from the catalogue and this Organization's settings. */
    public static List<Map<String, Object>> form(Connection c, UUID flowId, AppliesTo.Subject subject) throws SQLException {
        List<SectionSetting> sections = Sql.query(c, "select section, enabled, position, title, intro, verify from app.section_setting "
                + "where flow_id = ?", flowId).stream()
                .map(r -> new SectionSetting((String) r.get("section"), (Boolean) r.get("enabled"), number(r.get("position")),
                        (String) r.get("title"), (String) r.get("intro"), (Boolean) r.get("verify")))
                .toList();
        return FieldCatalog.form(sections, fieldSettings(c, null), fieldSettings(c, flowId), customFieldDefs(c), subject);
    }

    public static List<CustomField> customFieldDefs(Connection c) throws SQLException {
        List<CustomField> out = new ArrayList<>();
        for (Map<String, Object> r : Sql.query(c, "select * from app.custom_field")) {
            out.add(new CustomField(AuthService.uuid(r.get("id")), (String) r.get("fieldKey"), (String) r.get("label"),
                    (String) r.get("help"), (String) r.get("section"), (String) r.get("dataType"), stringList(r.get("options")),
                    (String) r.get("pattern"), decimal(r.get("minValue")), decimal(r.get("maxValue")), (Boolean) r.get("sensitive"),
                    (Boolean) r.get("managerCanSee"), AppliesTo.parse((String) r.get("appliesTo")), (String) r.get("visibility"),
                    (String) r.get("editableBy"), number(r.get("position")), (Boolean) r.get("active")));
        }
        return out;
    }

    // ── helpers ─────────────────────────────────────────────────────────

    private static Map<String, Object> settingsRow(Connection c) throws SQLException {
        return Sql.one(c, "select offer_stage, invite_when, invite_days_before, link_valid_days, activation, "
                + "allow_activation_without_pan, rehire_match, withdrawn_retention_days, exited_retention_years, updated_at "
                + "from app.onboarding_settings");
    }

    private Map<String, Object> flowDetail(Connection c, Map<String, Object> f, Map<UUID, String> names) throws SQLException {
        UUID id = AuthService.uuid(f.get("id"));
        AppliesTo rules = AppliesTo.parse((String) f.get("appliesTo"));
        Map<String, Object> out = new LinkedHashMap<>();
        for (String k : List.of("id", "name", "position", "welcomeMessage", "welcomeVideoUrl", "dayOneNote", "hrContactId",
                "verificationLevels", "active", "updatedAt")) {
            out.put(k, f.get(k));
        }
        out.put("appliesTo", rules.rules());
        out.put("rules", rules.describe(names::get));
        out.put("sections", Sql.query(c, "select section, enabled, position, title, intro, verify from app.section_setting "
                + "where flow_id = ? order by position, section", id));
        out.put("steps", Sql.query(c, "select s.level, s.section, s.verifier, r.code as role_code, r.name as role_name, "
                + "s.membership_id, d.email from app.verification_step s left join app.role r on r.id = s.role_id "
                + "left join app.member_directory() d on d.membership_id = s.membership_id "
                + "where s.flow_id = ? order by s.level, s.section nulls first", id));
        return out;
    }

    private static void saveSections(Connection c, UUID flowId, List<SectionInput> sections) throws SQLException {
        Set<String> seen = new HashSet<>();
        for (SectionInput s : sections) {
            String section = s.section() == null ? "" : s.section().trim();
            if (!FieldCatalog.SECTIONS.contains(section)) {
                throw bad("Unknown section: " + section);
            }
            if (!seen.add(section)) {
                throw bad("The section " + section + " is listed twice");
            }
            if (Boolean.FALSE.equals(s.enabled()) && FieldCatalog.ALWAYS_ON.contains(section)) {
                throw new ApiException(409, "section.fixed", FieldCatalog.SECTION_TITLES.get(section) + " cannot be switched off");
            }
            Sql.update(c, "update app.section_setting set enabled = coalesce(?, enabled), position = coalesce(?, position), "
                            + "title = ?, intro = ?, verify = coalesce(?, verify) where flow_id = ? and section = ?",
                    s.enabled(), s.position() == null ? null : (short) range(s.position(), 0, 99, "Section position"),
                    text(s.title(), "the section title", 80, false), text(s.intro(), "the section introduction", 2000, false),
                    s.verify(), flowId, section);
        }
    }

    private static void saveSteps(Connection c, SessionCtx ctx, UUID flowId, int levels, List<StepInput> steps) throws SQLException {
        Sql.update(c, "delete from app.verification_step where flow_id = ?", flowId);
        Set<String> seen = new HashSet<>();
        for (StepInput s : steps) {
            int level = range(s.level(), 1, 3, "Verification level");
            if (level > levels) {
                throw bad("This flow has " + levels + " verification level" + (levels == 1 ? "" : "s") + "; there is no level " + level);
            }
            String section = s.section() == null || s.section().isBlank() ? null : s.section().trim();
            if (section != null && !FieldCatalog.SECTIONS.contains(section)) {
                throw bad("Unknown section: " + section);
            }
            String verifier = s.verifier() == null ? "" : s.verifier().trim();
            UUID role = null;
            UUID member = null;
            String who;
            switch (verifier) {
                case "role" -> {
                    Map<String, Object> r = Sql.one(c, "select id from app.role where code = ? and code not like 'support%' "
                            + "and code not in ('joiner','ex_employee')", s.roleCode() == null ? "" : s.roleCode().trim());
                    if (r == null) {
                        throw bad("Unknown role for verification: " + s.roleCode());
                    }
                    role = AuthService.uuid(r.get("id"));
                    who = "role " + s.roleCode().trim();
                }
                case "user" -> {
                    member = activeMember(c, s.membershipId(), "verifier");
                    who = "user " + member;
                }
                case "manager" -> who = "manager";
                default -> throw bad("A verifier is a role, a user or the manager");
            }
            if (!seen.add(level + "|" + section + "|" + who)) {
                throw bad("The same verifier is listed twice for level " + level);
            }
            Sql.update(c, "insert into app.verification_step (tenant_id, flow_id, level, section, verifier, role_id, membership_id) "
                    + "values (?, ?, ?, ?, ?, ?, ?)", ctx.tenantId(), flowId, (short) level, section, verifier, role, member);
        }
    }

    /** Every level needs a verifier for every section, so nothing can wait for nobody. */
    private static void checkStepsCover(Connection c, UUID flowId, int levels) throws SQLException {
        for (int level = 1; level <= levels; level++) {
            if (Sql.one(c, "select 1 from app.verification_step where flow_id = ? and level = ? and section is null",
                    flowId, (short) level) == null) {
                throw bad("Level " + level + " needs a verifier for every section (one step with no section given)");
            }
        }
    }

    /** At least one active flow must apply to everyone, so every joiner has a flow. */
    private static void requireCatchAll(Connection c) throws SQLException {
        if (Sql.one(c, "select 1 from app.onboarding_flow where active and applies_to = '{}'::jsonb") == null) {
            throw new ApiException(409, "onboarding.no_catch_all",
                    "Keep one active flow that applies to everyone, so every joiner has a flow");
        }
    }

    /** Checks that the departments and work locations a rule names belong to this Organization. */
    private static AppliesTo checkedRules(Connection c, AppliesTo rules) throws SQLException {
        for (String[] attr : new String[][] {{"department", "app.department"}, {"work_location", "app.work_location"}}) {
            List<UUID> ids = rules.ids(attr[0]);
            if (ids.isEmpty()) {
                continue;
            }
            Number found = (Number) Sql.one(c, "select count(*) as n from " + attr[1] + " where id = any(?)",
                    (Object) ids.toArray(UUID[]::new)).get("n");
            if (found.intValue() != ids.size()) {
                throw new ApiException(400, "rules.invalid", "A rule names a " + attr[0].replace('_', ' ') + " that does not exist");
            }
        }
        return rules;
    }

    private static UUID requireFlow(Connection c, UUID flowId) throws SQLException {
        if (Sql.one(c, "select 1 from app.onboarding_flow where id = ?", flowId) == null) {
            throw ApiException.notFound("The flow");
        }
        return flowId;
    }

    private static UUID activeMember(Connection c, String id, String what) throws SQLException {
        UUID m;
        try {
            m = UUID.fromString(id == null ? "" : id.trim());
        } catch (IllegalArgumentException e) {
            throw bad("Choose the " + what);
        }
        if (Sql.one(c, "select 1 from app.membership where id = ? and status = 'active'", m) == null) {
            throw bad("The " + what + " must be an active user of this Organization");
        }
        return m;
    }

    static Map<String, FieldSetting> fieldSettings(Connection c, UUID flowId) throws SQLException {
        Map<String, FieldSetting> out = new HashMap<>();
        for (Map<String, Object> r : Sql.query(c, "select field_key, visibility, label, help, editable_by from app.field_setting where "
                + (flowId == null ? "flow_id is null" : "flow_id = ?"), flowId == null ? new Object[0] : new Object[] {flowId})) {
            out.put((String) r.get("fieldKey"), new FieldSetting((String) r.get("fieldKey"), (String) r.get("visibility"),
                    (String) r.get("label"), (String) r.get("help"), (String) r.get("editableBy")));
        }
        return out;
    }

    private static Map<String, Object> setting(FieldSetting s) {
        if (s == null) {
            return null;
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("visibility", s.visibility());
        out.put("label", s.label());
        out.put("help", s.help());
        out.put("editableBy", s.editableBy());
        return out;
    }

    /** Names of departments and work locations, for describing rules in words. */
    private static Map<UUID, String> names(Connection c) throws SQLException {
        Map<UUID, String> out = new HashMap<>();
        for (Map<String, Object> r : Sql.query(c, "select id, name from app.department union all select id, name from app.work_location")) {
            out.put(AuthService.uuid(r.get("id")), (String) r.get("name"));
        }
        return out;
    }

    private static void audit(Connection c, SessionCtx ctx, String action, String targetType, UUID targetId,
                              Object before, Object after) throws SQLException {
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("before", before);
        detail.put("after", after);
        Sql.update(c, "insert into app.audit_event (tenant_id, actor_kind, actor_membership_id, actor_label, "
                        + "impersonation_grant_id, action, target_type, target_id, detail) values (?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb)",
                ctx.tenantId(), ctx.actorKind(), ctx.membershipId(), ctx.email(), ctx.grantId(), action, targetType, targetId,
                Json.write(detail));
    }

    private static void requireRead(SessionCtx ctx) {
        if (!ctx.reachesEveryone("employee.read")) {
            throw ApiException.denied("employee.read");
        }
    }

    private static void requireConfigure(SessionCtx ctx) {
        ctx.require("onboarding.configure");
    }

    private static String text(String value, String what, int max, boolean required) {
        if (value == null || value.isBlank()) {
            if (required) {
                throw bad("Enter " + what);
            }
            return null;
        }
        String v = value.trim();
        if (v.length() > max) {
            throw bad("Keep " + what + " to " + max + " characters");
        }
        return v;
    }

    private static int range(Integer value, int min, int max, String what) {
        if (value == null || value < min || value > max) {
            throw bad(what + " must be from " + min + " to " + max);
        }
        return value;
    }

    static int number(Object value) {
        return value == null ? 0 : ((Number) value).intValue();
    }

    private static BigDecimal decimal(Object value) {
        return value == null ? null : new BigDecimal(value.toString());
    }

    @SuppressWarnings("unchecked")
    static List<String> stringList(Object value) {
        return value == null ? null : (List<String>) value;
    }

    private static ApiException bad(String message) {
        return new ApiException(400, "request.invalid", message);
    }
}
