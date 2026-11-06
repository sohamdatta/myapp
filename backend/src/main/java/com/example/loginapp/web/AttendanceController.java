package com.example.loginapp.web;

import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.example.loginapp.core.ApiException;
import com.example.loginapp.core.AttendanceService;
import com.example.loginapp.core.AuthService;
import com.example.loginapp.core.Modules;
import com.example.loginapp.core.SessionCtx;

import jakarta.servlet.http.HttpServletRequest;

/** Punching, day results and requests in the caller's Organization. */
@RestController
@RequestMapping("/api/org/attendance")
public class AttendanceController {

    public record PunchRequest(Double latitude, Double longitude, Integer accuracyM) {}

    /** workDate is a single day; fromDate and toDate give a range of days on duty or at home. */
    public record NewRequest(String kind, String workDate, String fromDate, String toDate, String half,
                             String inTime, String outTime, String reason) {}

    public record Decision(String note) {}

    public record ManualPunch(String at, String direction, String reason) {}

    public record MarkRequest(String date, String status, String reason) {}

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
    public Map<String, Object> today(@RequiresModule(Modules.ATTENDANCE) SessionCtx ctx) {
        return attendance.today(ctx);
    }

    @PostMapping("/me/punch")
    public Map<String, Object> punch(@RequiresModule(Modules.ATTENDANCE) SessionCtx ctx,
                                     @RequestBody PunchRequest request, HttpServletRequest http) {
        return attendance.punch(ctx, request.latitude(), request.longitude(),
                request.accuracyM(), clientIp(http));
    }

    @GetMapping("/me/days")
    public List<Map<String, Object>> myDays(@RequiresModule(Modules.ATTENDANCE) SessionCtx ctx,
                                            @RequestParam(value = "month", required = false) String month) {
        return attendance.myDays(ctx, month);
    }

    @GetMapping("/me/wfh")
    public Map<String, Object> homeWorking(@RequiresModule(Modules.ATTENDANCE) SessionCtx ctx) {
        return attendance.homeWorking(ctx);
    }

    @GetMapping("/me/requests")
    public List<Map<String, Object>> myRequests(@RequiresModule(Modules.ATTENDANCE) SessionCtx ctx) {
        return attendance.myRequests(ctx);
    }

    @PostMapping("/me/requests")
    public Map<String, Object> createRequest(@RequiresModule(Modules.ATTENDANCE) SessionCtx ctx,
                                             @RequestBody NewRequest request) {
        String from = request.fromDate() != null ? request.fromDate() : request.workDate();
        return attendance.createRequests(ctx, request.kind(), from,
                request.toDate() != null ? request.toDate() : from, request.half(), request.inTime(), request.outTime(), request.reason());
    }

    @PostMapping("/me/requests/{id}/cancel")
    public ResponseEntity<Void> cancelRequest(@RequiresModule(Modules.ATTENDANCE) SessionCtx ctx,
                                              @PathVariable("id") String id) {
        attendance.cancelRequest(ctx, Requests.id(id));
        return ResponseEntity.noContent().build();
    }

    // ── other people's attendance ───────────────────────────────────────

    @GetMapping("/days")
    public List<Map<String, Object>> board(@RequiresModule(Modules.ATTENDANCE) SessionCtx ctx,
                                           @RequestParam(value = "date", required = false) String date) {
        return attendance.board(ctx, date);
    }

    @GetMapping("/users/{id}/days")
    public List<Map<String, Object>> userDays(@RequiresModule(Modules.ATTENDANCE) SessionCtx ctx,
                                              @PathVariable("id") String id,
                                              @RequestParam(value = "month", required = false) String month) {
        return attendance.userDays(ctx, Requests.id(id), month);
    }

    @PostMapping("/users/{id}/punches")
    public ResponseEntity<Void> manualPunch(@RequiresModule(Modules.ATTENDANCE) SessionCtx ctx,
                                            @PathVariable("id") String id, @RequestBody ManualPunch request) {
        attendance.manualPunch(ctx, Requests.id(id), request.at(), request.direction(), request.reason());
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/requests")
    public List<Map<String, Object>> requests(@RequiresModule(Modules.ATTENDANCE) SessionCtx ctx,
                                              @RequestParam(value = "status", required = false) String status) {
        return attendance.requests(ctx, status);
    }

    /** action is approve or reject. */
    @PostMapping("/requests/{id}/{action}")
    public ResponseEntity<Void> decide(@RequiresModule(Modules.ATTENDANCE) SessionCtx ctx,
                                       @PathVariable("id") String id, @PathVariable("action") String action,
                                       @RequestBody Decision decision) {
        if (!action.equals("approve") && !action.equals("reject")) {
            throw ApiException.badRequest("Unknown action: " + action);
        }
        attendance.decideRequest(ctx, Requests.id(id), action.equals("approve"), decision == null ? null : decision.note());
        return ResponseEntity.noContent().build();
    }

    /** The month's register as CSV text, for download. */
    @GetMapping("/register")
    public Map<String, Object> register(@RequiresModule(Modules.ATTENDANCE) SessionCtx ctx,
                                        @RequestParam(value = "month", required = false) String month) {
        return attendance.register(ctx, month);
    }

    /**
     * The address the request came from. The X-Forwarded-For header is used only
     * when the application is configured as running behind a proxy that sets it;
     * otherwise anyone could claim to be on the office network.
     */
    /** Marks an exception on a present-by-default day: absent, half_day, or present to take a mark back. */
    @PostMapping("/users/{id}/marks")
    public Map<String, Object> mark(@RequiresModule(Modules.ATTENDANCE) SessionCtx ctx,
                                    @PathVariable("id") String id, @RequestBody MarkRequest request) {
        return attendance.mark(ctx, Requests.id(id), request.date(), request.status(), request.reason());
    }

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
