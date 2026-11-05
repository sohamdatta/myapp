package com.example.loginapp.web;

import java.util.List;
import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.example.loginapp.core.AuthService;
import com.example.loginapp.core.SessionCtx;
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
    public Map<String, Object> list(SessionCtx ctx,
                                    @RequestParam(value = "group", required = false) String group) {
        return settings.list(ctx, group);
    }

    @GetMapping("/org/settings/changes")
    public List<Map<String, Object>> changes(SessionCtx ctx,
                                             @RequestParam(value = "key", required = false) String key) {
        return settings.changes(ctx, key);
    }

    @GetMapping("/org/settings/{key}")
    public Map<String, Object> explain(SessionCtx ctx,
                                       @PathVariable("key") String key, @RequestParam(value = "scope", required = false) String scope,
                                       @RequestParam(value = "scopeId", required = false) String scopeId,
                                       @RequestParam(value = "asOf", required = false) String asOf) {
        return settings.explain(ctx, key, scope, scopeId, asOf);
    }

    @PutMapping("/org/settings/{key}")
    public Map<String, Object> change(SessionCtx ctx,
                                      @PathVariable("key") String key, @RequestBody SettingsService.ChangeInput request) {
        return settings.change(ctx, key, request);
    }

    @PostMapping("/org/settings/changes/{id}/approve")
    public Map<String, Object> approve(SessionCtx ctx,
                                       @PathVariable("id") String id, @RequestBody(required = false) SettingsService.DecisionInput request) {
        return settings.approve(ctx, Requests.id(id), request);
    }

    @PostMapping("/org/settings/changes/{id}/reject")
    public Map<String, Object> reject(SessionCtx ctx,
                                      @PathVariable("id") String id, @RequestBody SettingsService.DecisionInput request) {
        return settings.reject(ctx, Requests.id(id), request);
    }

    @PostMapping("/org/settings/changes/{id}/withdraw")
    public Map<String, Object> withdraw(SessionCtx ctx,
                                        @PathVariable("id") String id) {
        return settings.withdraw(ctx, Requests.id(id));
    }

    @PostMapping("/org/settings/changes/{id}/undo")
    public Map<String, Object> undo(SessionCtx ctx,
                                    @PathVariable("id") String id, @RequestBody(required = false) SettingsService.DecisionInput request) {
        return settings.undo(ctx, Requests.id(id), request);
    }

    // ── the platform ────────────────────────────────────────────────────

    @GetMapping("/platform/settings")
    public List<Map<String, Object>> platform(SessionCtx ctx) {
        return settings.platformList(ctx);
    }

    @PutMapping("/platform/settings/{key}")
    public Map<String, Object> platformValue(SessionCtx ctx,
                                             @PathVariable("key") String key, @RequestBody SettingsService.PlatformInput request) {
        return settings.setPlatformValue(ctx, key, request);
    }

    @PutMapping("/platform/settings/{key}/bound")
    public Map<String, Object> bound(SessionCtx ctx,
                                     @PathVariable("key") String key, @RequestBody SettingsService.BoundInput request) {
        return settings.setBound(ctx, key, request);
    }
}
