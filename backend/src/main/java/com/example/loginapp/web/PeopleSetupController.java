package com.example.loginapp.web;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.example.loginapp.core.AppliesTo;
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
    public Map<String, Object> settings(@RequestHeader(value = "Authorization", required = false) String authorization) {
        return config.settings(Requests.session(auth, authorization));
    }

    @PutMapping("/settings")
    public Map<String, Object> saveSettings(@RequestHeader(value = "Authorization", required = false) String authorization,
                                           @RequestBody SettingsInput request) {
        return config.saveSettings(Requests.session(auth, authorization), request);
    }

    @PostMapping("/notice")
    public Map<String, Object> notice(@RequestHeader(value = "Authorization", required = false) String authorization,
                                      @RequestBody Notice request) {
        return config.publishNotice(Requests.session(auth, authorization), request.body());
    }

    @GetMapping("/flows")
    public List<Map<String, Object>> flows(@RequestHeader(value = "Authorization", required = false) String authorization) {
        return config.flows(Requests.session(auth, authorization));
    }

    @PostMapping("/flows")
    public Map<String, Object> createFlow(@RequestHeader(value = "Authorization", required = false) String authorization,
                                          @RequestBody FlowInput request) {
        return config.saveFlow(Requests.session(auth, authorization), null, request);
    }

    @PutMapping("/flows/order")
    public List<Map<String, Object>> order(@RequestHeader(value = "Authorization", required = false) String authorization,
                                           @RequestBody Order request) {
        return config.orderFlows(Requests.session(auth, authorization), request.ids());
    }

    @PutMapping("/flows/{id}")
    public Map<String, Object> saveFlow(@RequestHeader(value = "Authorization", required = false) String authorization,
                                        @PathVariable("id") String id, @RequestBody FlowInput request) {
        return config.saveFlow(Requests.session(auth, authorization), Requests.id(id), request);
    }

    @GetMapping("/fields")
    public List<Map<String, Object>> fields(@RequestHeader(value = "Authorization", required = false) String authorization) {
        return config.fields(Requests.session(auth, authorization), null);
    }

    @PutMapping("/fields")
    public List<Map<String, Object>> saveFields(@RequestHeader(value = "Authorization", required = false) String authorization,
                                                @RequestBody Fields request) {
        return config.saveFields(Requests.session(auth, authorization), null, request.fields());
    }

    @GetMapping("/flows/{id}/fields")
    public List<Map<String, Object>> flowFields(@RequestHeader(value = "Authorization", required = false) String authorization,
                                                @PathVariable("id") String id) {
        return config.fields(Requests.session(auth, authorization), Requests.id(id));
    }

    @PutMapping("/flows/{id}/fields")
    public List<Map<String, Object>> saveFlowFields(@RequestHeader(value = "Authorization", required = false) String authorization,
                                                    @PathVariable("id") String id, @RequestBody Fields request) {
        return config.saveFields(Requests.session(auth, authorization), Requests.id(id), request.fields());
    }

    @GetMapping("/custom-fields")
    public List<Map<String, Object>> customFields(@RequestHeader(value = "Authorization", required = false) String authorization) {
        return config.customFields(Requests.session(auth, authorization));
    }

    @PostMapping("/custom-fields")
    public Map<String, Object> createCustomField(@RequestHeader(value = "Authorization", required = false) String authorization,
                                                 @RequestBody CustomFieldInput request) {
        return config.saveCustomField(Requests.session(auth, authorization), null, request);
    }

    @PutMapping("/custom-fields/{id}")
    public Map<String, Object> saveCustomField(@RequestHeader(value = "Authorization", required = false) String authorization,
                                               @PathVariable("id") String id, @RequestBody CustomFieldInput request) {
        return config.saveCustomField(Requests.session(auth, authorization), Requests.id(id), request);
    }

    /** The form a joiner would see: for a flow, or for the joiner described (the flow is then picked by the rules). */
    @GetMapping("/preview")
    public Map<String, Object> preview(@RequestHeader(value = "Authorization", required = false) String authorization,
                                       @RequestParam(value = "flow", required = false) String flow,
                                       @RequestParam(value = "employmentType", required = false) String employmentType,
                                       @RequestParam(value = "department", required = false) String department,
                                       @RequestParam(value = "workLocation", required = false) String workLocation,
                                       @RequestParam(value = "designation", required = false) String designation) {
        AppliesTo.Subject subject = new AppliesTo.Subject(optionalId(department), optionalId(workLocation),
                blank(employmentType) ? null : employmentType.trim(), blank(designation) ? null : designation.trim());
        return config.preview(Requests.session(auth, authorization), optionalId(flow), subject);
    }

    private static UUID optionalId(String value) {
        return blank(value) ? null : Requests.id(value.trim());
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    @GetMapping("/document-types")
    public List<Map<String, Object>> documentTypes(@RequestHeader(value = "Authorization", required = false) String authorization) {
        return config.documentTypes(Requests.session(auth, authorization));
    }

    @PostMapping("/document-types")
    public Map<String, Object> createDocumentType(@RequestHeader(value = "Authorization", required = false) String authorization,
                                                  @RequestBody DocumentTypeInput request) {
        return config.saveDocumentType(Requests.session(auth, authorization), null, request);
    }

    @PutMapping("/document-types/{id}")
    public Map<String, Object> saveDocumentType(@RequestHeader(value = "Authorization", required = false) String authorization,
                                                @PathVariable("id") String id, @RequestBody DocumentTypeInput request) {
        return config.saveDocumentType(Requests.session(auth, authorization), Requests.id(id), request);
    }

    @GetMapping("/checklists")
    public List<Map<String, Object>> checklists(@RequestHeader(value = "Authorization", required = false) String authorization) {
        return config.checklists(Requests.session(auth, authorization));
    }

    @PostMapping("/checklists")
    public Map<String, Object> createChecklist(@RequestHeader(value = "Authorization", required = false) String authorization,
                                               @RequestBody ChecklistInput request) {
        return config.saveChecklist(Requests.session(auth, authorization), null, request);
    }

    @PutMapping("/checklists/{id}")
    public Map<String, Object> saveChecklist(@RequestHeader(value = "Authorization", required = false) String authorization,
                                             @PathVariable("id") String id, @RequestBody ChecklistInput request) {
        return config.saveChecklist(Requests.session(auth, authorization), Requests.id(id), request);
    }
}
