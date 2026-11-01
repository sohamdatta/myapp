package com.example.loginapp.web;

import java.nio.charset.StandardCharsets;
import java.util.List;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.example.loginapp.core.AuthService;
import com.example.loginapp.core.SessionCtx;
import com.example.loginapp.core.UsageStats;

/**
 * The platform console's Usage page: how each Organization uses each feature, as counts only. A grid of Organizations by module, a
 * 12-week trend for one Organization, one metric across Organizations, and a CSV of each. The platform team sees numbers, never
 * anything inside an Organization.
 */
@RestController
@RequestMapping("/api/platform/usage")
public class UsageController {

    private final AuthService auth;
    private final UsageStats usage;

    public UsageController(AuthService auth, UsageStats usage) {
        this.auth = auth;
        this.usage = usage;
    }

    private SessionCtx session(String authorization) {
        return Requests.session(auth, authorization);
    }

    @GetMapping
    public UsageStats.Grid grid(@RequestHeader(value = "Authorization", required = false) String authorization) {
        return usage.grid(session(authorization));
    }

    @GetMapping("/metrics")
    public List<UsageStats.MetricInfo> metrics(@RequestHeader(value = "Authorization", required = false) String authorization) {
        return usage.metrics(session(authorization));
    }

    @GetMapping("/organizations/{id}")
    public UsageStats.Trend trend(@RequestHeader(value = "Authorization", required = false) String authorization, @PathVariable("id") String id) {
        return usage.trend(session(authorization), Requests.id(id));
    }

    @GetMapping("/metrics/{code}")
    public UsageStats.MetricView metric(@RequestHeader(value = "Authorization", required = false) String authorization, @PathVariable("code") String code) {
        return usage.metric(session(authorization), code);
    }

    @GetMapping("/grid.csv")
    public ResponseEntity<byte[]> gridCsv(@RequestHeader(value = "Authorization", required = false) String authorization) {
        return csv("usage-organizations.csv", usage.gridCsv(session(authorization)));
    }

    @GetMapping("/organizations/{id}/csv")
    public ResponseEntity<byte[]> trendCsv(@RequestHeader(value = "Authorization", required = false) String authorization, @PathVariable("id") String id) {
        return csv("usage-organization.csv", usage.trendCsv(session(authorization), Requests.id(id)));
    }

    @GetMapping("/metrics/{code}/csv")
    public ResponseEntity<byte[]> metricCsv(@RequestHeader(value = "Authorization", required = false) String authorization, @PathVariable("code") String code) {
        return csv("usage-" + code.replaceAll("[^a-z0-9_]", "") + ".csv", usage.metricCsv(session(authorization), code));
    }

    /** Counts the days not counted yet, and today so far, now rather than at 01:00 (Super Admin only). */
    @PostMapping("/refresh")
    public UsageStats.RunResult refresh(@RequestHeader(value = "Authorization", required = false) String authorization) {
        return usage.refresh(session(authorization));
    }

    private static ResponseEntity<byte[]> csv(String name, String body) {
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.CONTENT_TYPE, "text/csv; charset=utf-8");
        headers.set(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + name + "\"");
        headers.set("X-Content-Type-Options", "nosniff");
        headers.set(HttpHeaders.CACHE_CONTROL, "no-store");
        return new ResponseEntity<>(body.getBytes(StandardCharsets.UTF_8), headers, HttpStatus.OK);
    }
}
