package com.example.loginapp.web;

import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.example.loginapp.core.ApiException;
import com.example.loginapp.core.AttendanceService;
import com.example.loginapp.core.AuthService;
import com.example.loginapp.core.SessionCtx;

import jakarta.servlet.http.HttpServletRequest;

/** Punching, day results and requests in the caller's Organization. */
@RestController
@RequestMapping("/api/org/attendance")
public class AttendanceController {

    public record PunchRequest(Double latitude, Double longitude, Integer accuracyM) {}

    public record NewRequest(String kind, String workDate, String inTime, String outTime, String reason) {}

    public record Decision(String note) {}

    public record ManualPunch(String at, String direction, String reason) {}

    private final AuthService auth;
    private final AttendanceService attendance;
    private final boolean trustForwardedFor;

    public AttendanceController(AuthService auth, AttendanceService attendance,
                                @Value("${app.trust-forwarded-for:false}") boolean trustForwardedFor) {
        this.auth = auth;
        this.attendance = attendance;
        this.trustForwardedFor = trustForwardedFor;
    }

    // ── the caller's own attendance ─────────────────────────────────────

    @GetMapping("/me/today")
    public Map<String, Object> today(@RequestHeader(value = "Authorization", required = false) String authorization) {
        return attendance.today(Requests.session(auth, authorization));
    }

    @PostMapping("/me/punch")
    public Map<String, Object> punch(@RequestHeader(value = "Authorization", required = false) String authorization,
                                     @RequestBody PunchRequest request, HttpServletRequest http) {
        return attendance.punch(Requests.session(auth, authorization), request.latitude(), request.longitude(),
                request.accuracyM(), clientIp(http));
    }

    @GetMapping("/me/days")
    public List<Map<String, Object>> myDays(@RequestHeader(value = "Authorization", required = false) String authorization,
                                            @RequestParam(value = "month", required = false) String month) {
        return attendance.myDays(Requests.session(auth, authorization), month);
    }

    @GetMapping("/me/requests")
    public List<Map<String, Object>> myRequests(@RequestHeader(value = "Authorization", required = false) String authorization) {
        return attendance.myRequests(Requests.session(auth, authorization));
    }

    @PostMapping("/me/requests")
    public Map<String, Object> createRequest(@RequestHeader(value = "Authorization", required = false) String authorization,
                                             @RequestBody NewRequest request) {
        return attendance.createRequest(Requests.session(auth, authorization), request.kind(), request.workDate(),
                request.inTime(), request.outTime(), request.reason());
    }

    @PostMapping("/me/requests/{id}/cancel")
    public ResponseEntity<Void> cancelRequest(@RequestHeader(value = "Authorization", required = false) String authorization,
                                              @PathVariable("id") String id) {
        attendance.cancelRequest(Requests.session(auth, authorization), Requests.id(id));
        return ResponseEntity.noContent().build();
    }

    // ── other people's attendance ───────────────────────────────────────

    @GetMapping("/days")
    public List<Map<String, Object>> board(@RequestHeader(value = "Authorization", required = false) String authorization,
                                           @RequestParam(value = "date", required = false) String date) {
        return attendance.board(Requests.session(auth, authorization), date);
    }

    @GetMapping("/users/{id}/days")
    public List<Map<String, Object>> userDays(@RequestHeader(value = "Authorization", required = false) String authorization,
                                              @PathVariable("id") String id,
                                              @RequestParam(value = "month", required = false) String month) {
        return attendance.userDays(Requests.session(auth, authorization), Requests.id(id), month);
    }

    @PostMapping("/users/{id}/punches")
    public ResponseEntity<Void> manualPunch(@RequestHeader(value = "Authorization", required = false) String authorization,
                                            @PathVariable("id") String id, @RequestBody ManualPunch request) {
        attendance.manualPunch(Requests.session(auth, authorization), Requests.id(id), request.at(), request.direction(), request.reason());
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/requests")
    public List<Map<String, Object>> requests(@RequestHeader(value = "Authorization", required = false) String authorization,
                                              @RequestParam(value = "status", required = false) String status) {
        return attendance.requests(Requests.session(auth, authorization), status);
    }

    /** action is approve or reject. */
    @PostMapping("/requests/{id}/{action}")
    public ResponseEntity<Void> decide(@RequestHeader(value = "Authorization", required = false) String authorization,
                                       @PathVariable("id") String id, @PathVariable("action") String action,
                                       @RequestBody Decision decision) {
        SessionCtx ctx = Requests.session(auth, authorization);
        if (!action.equals("approve") && !action.equals("reject")) {
            throw ApiException.badRequest("Unknown action: " + action);
        }
        attendance.decideRequest(ctx, Requests.id(id), action.equals("approve"), decision == null ? null : decision.note());
        return ResponseEntity.noContent().build();
    }

    /** The month's register as CSV text, for download. */
    @GetMapping("/register")
    public Map<String, Object> register(@RequestHeader(value = "Authorization", required = false) String authorization,
                                        @RequestParam(value = "month", required = false) String month) {
        return attendance.register(Requests.session(auth, authorization), month);
    }

    /**
     * The address the request came from. The X-Forwarded-For header is used only
     * when the application is configured as running behind a proxy that sets it;
     * otherwise anyone could claim to be on the office network.
     */
    private String clientIp(HttpServletRequest http) {
        if (trustForwardedFor) {
            String forwarded = http.getHeader("X-Forwarded-For");
            if (forwarded != null && !forwarded.isBlank()) {
                String[] hops = forwarded.split(",");
                return hops[hops.length - 1].trim();   // the entry added by the nearest proxy
            }
        }
        return http.getRemoteAddr();
    }
}
