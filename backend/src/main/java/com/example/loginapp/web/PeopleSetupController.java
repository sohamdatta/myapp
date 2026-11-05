package com.example.loginapp.web;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.example.loginapp.core.AppliesTo;
import com.example.loginapp.core.SessionCtx;
import com.example.loginapp.core.AuthService;
import com.example.loginapp.core.OnboardingConfigService;
import com.example.loginapp.core.OnboardingConfigService.ChecklistInput;
import com.example.loginapp.core.OnboardingConfigService.CustomFieldInput;
import com.example.loginapp.core.OnboardingConfigService.DocumentTypeInput;
import com.example.loginapp.core.OnboardingConfigService.FieldInput;
import com.example.loginapp.core.OnboardingConfigService.FlowInput;
import com.example.loginapp.core.OnboardingConfigService.SettingsInput;

/** The Organization's onboarding configuration. Reading needs employee.read; changing needs onboarding.configure. */
@RestController
@RequestMapping("/api/org/people/setup")
public class PeopleSetupController {

    public record Notice(String body) {}

    public record Order(List<String> ids) {}

    public record Fields(List<FieldInput> fields) {}

    private final AuthService auth;
    private final OnboardingConfigService config;

    public PeopleSetupController(AuthService auth, OnboardingConfigService config) {
        this.auth = auth;
        this.config = config;
    }

    @GetMapping("/settings")
    public Map<String, Object> settings(SessionCtx ctx) {
        return config.settings(ctx);
    }

    @PutMapping("/settings")
    public Map<String, Object> saveSettings(SessionCtx ctx,
                                           @RequestBody SettingsInput request) {
        return config.saveSettings(ctx, request);
    }

    @PostMapping("/notice")
    public Map<String, Object> notice(SessionCtx ctx,
                                      @RequestBody Notice request) {
        return config.publishNotice(ctx, request.body());
    }

    @GetMapping("/flows")
    public List<Map<String, Object>> flows(SessionCtx ctx) {
        return config.flows(ctx);
    }

    @PostMapping("/flows")
    public Map<String, Object> createFlow(SessionCtx ctx,
                                          @RequestBody FlowInput request) {
        return config.saveFlow(ctx, null, request);
    }

    @PutMapping("/flows/order")
    public List<Map<String, Object>> order(SessionCtx ctx,
                                           @RequestBody Order request) {
        return config.orderFlows(ctx, request.ids());
    }

    @PutMapping("/flows/{id}")
    public Map<String, Object> saveFlow(SessionCtx ctx,
                                        @PathVariable("id") String id, @RequestBody FlowInput request) {
        return config.saveFlow(ctx, Requests.id(id), request);
    }

    @GetMapping("/fields")
    public List<Map<String, Object>> fields(SessionCtx ctx) {
        return config.fields(ctx, null);
    }

    @PutMapping("/fields")
    public List<Map<String, Object>> saveFields(SessionCtx ctx,
                                                @RequestBody Fields request) {
        return config.saveFields(ctx, null, request.fields());
    }

    @GetMapping("/flows/{id}/fields")
    public List<Map<String, Object>> flowFields(SessionCtx ctx,
                                                @PathVariable("id") String id) {
        return config.fields(ctx, Requests.id(id));
    }

    @PutMapping("/flows/{id}/fields")
    public List<Map<String, Object>> saveFlowFields(SessionCtx ctx,
                                                    @PathVariable("id") String id, @RequestBody Fields request) {
        return config.saveFields(ctx, Requests.id(id), request.fields());
    }

    @GetMapping("/custom-fields")
    public List<Map<String, Object>> customFields(SessionCtx ctx) {
        return config.customFields(ctx);
    }

    @PostMapping("/custom-fields")
    public Map<String, Object> createCustomField(SessionCtx ctx,
                                                 @RequestBody CustomFieldInput request) {
        return config.saveCustomField(ctx, null, request);
    }

    @PutMapping("/custom-fields/{id}")
    public Map<String, Object> saveCustomField(SessionCtx ctx,
                                               @PathVariable("id") String id, @RequestBody CustomFieldInput request) {
        return config.saveCustomField(ctx, Requests.id(id), request);
    }

    /** The form a joiner would see: for a flow, or for the joiner described (the flow is then picked by the rules). */
    @GetMapping("/preview")
    public Map<String, Object> preview(SessionCtx ctx,
                                       @RequestParam(value = "flow", required = false) String flow,
                                       @RequestParam(value = "employmentType", required = false) String employmentType,
                                       @RequestParam(value = "department", required = false) String department,
                                       @RequestParam(value = "workLocation", required = false) String workLocation,
                                       @RequestParam(value = "designation", required = false) String designation) {
        AppliesTo.Subject subject = new AppliesTo.Subject(optionalId(department), optionalId(workLocation),
                blank(employmentType) ? null : employmentType.trim(), blank(designation) ? null : designation.trim());
        return config.preview(ctx, optionalId(flow), subject);
    }

    private static UUID optionalId(String value) {
        return blank(value) ? null : Requests.id(value.trim());
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    @GetMapping("/document-types")
    public List<Map<String, Object>> documentTypes(SessionCtx ctx) {
        return config.documentTypes(ctx);
    }

    @PostMapping("/document-types")
    public Map<String, Object> createDocumentType(SessionCtx ctx,
                                                  @RequestBody DocumentTypeInput request) {
        return config.saveDocumentType(ctx, null, request);
    }

    @PutMapping("/document-types/{id}")
    public Map<String, Object> saveDocumentType(SessionCtx ctx,
                                                @PathVariable("id") String id, @RequestBody DocumentTypeInput request) {
        return config.saveDocumentType(ctx, Requests.id(id), request);
    }

    @GetMapping("/checklists")
    public List<Map<String, Object>> checklists(SessionCtx ctx) {
        return config.checklists(ctx);
    }

    @PostMapping("/checklists")
    public Map<String, Object> createChecklist(SessionCtx ctx,
                                               @RequestBody ChecklistInput request) {
        return config.saveChecklist(ctx, null, request);
    }

    @PutMapping("/checklists/{id}")
    public Map<String, Object> saveChecklist(SessionCtx ctx,
                                             @PathVariable("id") String id, @RequestBody ChecklistInput request) {
        return config.saveChecklist(ctx, Requests.id(id), request);
    }

    @GetMapping("/changes")
    public Map<String, Object> changes(SessionCtx ctx) {
        return config.lastChanges(ctx);
    }
}
