package com.example.loginapp.web;

import java.util.List;
import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.example.loginapp.core.AuthService;
import com.example.loginapp.core.SettingsService;

/**
 * Settings: an Organization's under /api/org/settings, the platform's under /api/platform/settings.
 * Every change answers "applied" or "pending" (waiting for a second person). See docs/configuration/design.md.
 */
@RestController
@RequestMapping("/api")
public class SettingsController {

    private final AuthService auth;
    private final SettingsService settings;

    public SettingsController(AuthService auth, SettingsService settings) {
        this.auth = auth;
        this.settings = settings;
    }

    @GetMapping("/org/settings")
    public Map<String, Object> list(@RequestHeader(value = "Authorization", required = false) String authorization,
                                    @RequestParam(value = "group", required = false) String group) {
        return settings.list(Requests.session(auth, authorization), group);
    }

    @GetMapping("/org/settings/changes")
    public List<Map<String, Object>> changes(@RequestHeader(value = "Authorization", required = false) String authorization,
                                             @RequestParam(value = "key", required = false) String key) {
        return settings.changes(Requests.session(auth, authorization), key);
    }

    @GetMapping("/org/settings/{key}")
    public Map<String, Object> explain(@RequestHeader(value = "Authorization", required = false) String authorization,
                                       @PathVariable("key") String key, @RequestParam(value = "scope", required = false) String scope,
                                       @RequestParam(value = "scopeId", required = false) String scopeId,
                                       @RequestParam(value = "asOf", required = false) String asOf) {
        return settings.explain(Requests.session(auth, authorization), key, scope, scopeId, asOf);
    }

    @PutMapping("/org/settings/{key}")
    public Map<String, Object> change(@RequestHeader(value = "Authorization", required = false) String authorization,
                                      @PathVariable("key") String key, @RequestBody SettingsService.ChangeInput request) {
        return settings.change(Requests.session(auth, authorization), key, request);
    }

    @PostMapping("/org/settings/changes/{id}/approve")
    public Map<String, Object> approve(@RequestHeader(value = "Authorization", required = false) String authorization,
                                       @PathVariable("id") String id, @RequestBody(required = false) SettingsService.DecisionInput request) {
        return settings.approve(Requests.session(auth, authorization), Requests.id(id), request);
    }

    @PostMapping("/org/settings/changes/{id}/reject")
    public Map<String, Object> reject(@RequestHeader(value = "Authorization", required = false) String authorization,
                                      @PathVariable("id") String id, @RequestBody SettingsService.DecisionInput request) {
        return settings.reject(Requests.session(auth, authorization), Requests.id(id), request);
    }

    @PostMapping("/org/settings/changes/{id}/withdraw")
    public Map<String, Object> withdraw(@RequestHeader(value = "Authorization", required = false) String authorization,
                                        @PathVariable("id") String id) {
        return settings.withdraw(Requests.session(auth, authorization), Requests.id(id));
    }

    @PostMapping("/org/settings/changes/{id}/undo")
    public Map<String, Object> undo(@RequestHeader(value = "Authorization", required = false) String authorization,
                                    @PathVariable("id") String id, @RequestBody(required = false) SettingsService.DecisionInput request) {
        return settings.undo(Requests.session(auth, authorization), Requests.id(id), request);
    }

    // ── the platform ────────────────────────────────────────────────────

    @GetMapping("/platform/settings")
    public List<Map<String, Object>> platform(@RequestHeader(value = "Authorization", required = false) String authorization) {
        return settings.platformList(Requests.session(auth, authorization));
    }

    @PutMapping("/platform/settings/{key}")
    public Map<String, Object> platformValue(@RequestHeader(value = "Authorization", required = false) String authorization,
                                             @PathVariable("key") String key, @RequestBody SettingsService.PlatformInput request) {
        return settings.setPlatformValue(Requests.session(auth, authorization), key, request);
    }

    @PutMapping("/platform/settings/{key}/bound")
    public Map<String, Object> bound(@RequestHeader(value = "Authorization", required = false) String authorization,
                                     @PathVariable("key") String key, @RequestBody SettingsService.BoundInput request) {
        return settings.setBound(Requests.session(auth, authorization), key, request);
    }
}
