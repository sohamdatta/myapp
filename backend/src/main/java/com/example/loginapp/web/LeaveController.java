package com.example.loginapp.web;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.example.loginapp.core.ApiException;
import com.example.loginapp.core.AuthService;
import com.example.loginapp.core.Modules;
import com.example.loginapp.core.LeaveAdminService;
import com.example.loginapp.core.LeaveService;
import com.example.loginapp.core.SessionCtx;

/** Leave in the caller's Organization: balances, requests, the calendar, and what HR does. */
@RestController
@RequestMapping("/api/org/leave")
public class LeaveController {

    public record LeaveRequest(String leaveTypeId, String fromDate, String toDate, String half, String reason) {}

    public record Decision(String note) {}

    public record Adjustment(String leaveTypeId, BigDecimal days, String onDate, String reason) {}

    public record CompOff(String leaveTypeId, String workDate, BigDecimal days) {}

    public record YearEnd(Integer year) {}

    private final AuthService auth;
    private final LeaveService leave;
    private final LeaveAdminService admin;

    public LeaveController(AuthService auth, LeaveService leave, LeaveAdminService admin) {
        this.auth = auth;
        this.leave = leave;
        this.admin = admin;
    }

    // ── the caller's own leave ──────────────────────────────────────────

    /** Readable without the Attendance module: exit settings name the leave types a settlement encashes. */
    @GetMapping("/types")
    public List<Map<String, Object>> types(@RequestHeader(value = "Authorization", required = false) String authorization) {
        return leave.types(Requests.session(auth, authorization));
    }

    @GetMapping("/me/balances")
    public List<Map<String, Object>> balances(@RequestHeader(value = "Authorization", required = false) String authorization) {
        return leave.balances(Requests.session(auth, authorization, Modules.ATTENDANCE));
    }

    @GetMapping("/me/ledger")
    public List<Map<String, Object>> ledger(@RequestHeader(value = "Authorization", required = false) String authorization,
                                            @RequestParam(value = "type", required = false) String type) {
        return leave.ledger(Requests.session(auth, authorization, Modules.ATTENDANCE), null, typeId(type));
    }

    @GetMapping("/me/requests")
    public List<Map<String, Object>> myRequests(@RequestHeader(value = "Authorization", required = false) String authorization) {
        return leave.myRequests(Requests.session(auth, authorization, Modules.ATTENDANCE));
    }

    @PostMapping("/me/requests/preview")
    public Map<String, Object> preview(@RequestHeader(value = "Authorization", required = false) String authorization,
                                       @RequestBody LeaveRequest request) {
        return leave.preview(Requests.session(auth, authorization, Modules.ATTENDANCE), typeId(request.leaveTypeId()), request.fromDate(),
                request.toDate(), request.half());
    }

    @PostMapping("/me/requests")
    public Map<String, Object> apply(@RequestHeader(value = "Authorization", required = false) String authorization,
                                     @RequestBody LeaveRequest request) {
        return leave.apply(Requests.session(auth, authorization, Modules.ATTENDANCE), typeId(request.leaveTypeId()), request.fromDate(),
                request.toDate(), request.half(), request.reason());
    }

    @PostMapping("/me/requests/{id}/cancel")
    public ResponseEntity<Void> cancel(@RequestHeader(value = "Authorization", required = false) String authorization,
                                       @PathVariable("id") String id) {
        leave.cancel(Requests.session(auth, authorization, Modules.ATTENDANCE), Requests.id(id));
        return ResponseEntity.noContent().build();
    }

    // ── other people's leave ────────────────────────────────────────────

    @GetMapping("/requests")
    public List<Map<String, Object>> requests(@RequestHeader(value = "Authorization", required = false) String authorization,
                                              @RequestParam(value = "status", required = false) String status) {
        return leave.requests(Requests.session(auth, authorization, Modules.ATTENDANCE), status);
    }

    /** action is approve, reject or cancel. */
    @PostMapping("/requests/{id}/{action}")
    public ResponseEntity<Void> decide(@RequestHeader(value = "Authorization", required = false) String authorization,
                                       @PathVariable("id") String id, @PathVariable("action") String action,
                                       @RequestBody Decision decision) {
        SessionCtx ctx = Requests.session(auth, authorization, Modules.ATTENDANCE);
        switch (action) {
            case "approve" -> leave.decide(ctx, Requests.id(id), true, decision == null ? null : decision.note());
            case "reject" -> leave.decide(ctx, Requests.id(id), false, decision == null ? null : decision.note());
            case "cancel" -> leave.cancelAny(ctx, Requests.id(id));
            default -> throw ApiException.badRequest("Unknown action: " + action);
        }
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/calendar")
    public List<Map<String, Object>> calendar(@RequestHeader(value = "Authorization", required = false) String authorization,
                                              @RequestParam(value = "from", required = false) String from,
                                              @RequestParam(value = "to", required = false) String to) {
        return leave.calendar(Requests.session(auth, authorization, Modules.ATTENDANCE), from, to);
    }

    @GetMapping("/balances")
    public List<Map<String, Object>> allBalances(@RequestHeader(value = "Authorization", required = false) String authorization) {
        return admin.allBalances(Requests.session(auth, authorization, Modules.ATTENDANCE));
    }

    @GetMapping("/users/{id}/balances")
    public List<Map<String, Object>> userBalances(@RequestHeader(value = "Authorization", required = false) String authorization,
                                                  @PathVariable("id") String id) {
        return leave.userBalances(Requests.session(auth, authorization, Modules.ATTENDANCE), Requests.id(id));
    }

    @GetMapping("/users/{id}/ledger")
    public List<Map<String, Object>> userLedger(@RequestHeader(value = "Authorization", required = false) String authorization,
                                                @PathVariable("id") String id,
                                                @RequestParam(value = "type", required = false) String type) {
        return leave.ledger(Requests.session(auth, authorization, Modules.ATTENDANCE), Requests.id(id), typeId(type));
    }

    // ── what HR does ────────────────────────────────────────────────────

    @PostMapping("/users/{id}/requests")
    public Map<String, Object> applyOnBehalf(@RequestHeader(value = "Authorization", required = false) String authorization,
                                             @PathVariable("id") String id, @RequestBody LeaveRequest request) {
        return admin.applyOnBehalf(Requests.session(auth, authorization, Modules.ATTENDANCE), Requests.id(id), typeId(request.leaveTypeId()),
                request.fromDate(), request.toDate(), request.half(), request.reason());
    }

    @PostMapping("/users/{id}/adjustments")
    public ResponseEntity<Void> adjust(@RequestHeader(value = "Authorization", required = false) String authorization,
                                       @PathVariable("id") String id, @RequestBody Adjustment request) {
        admin.adjust(Requests.session(auth, authorization, Modules.ATTENDANCE), Requests.id(id), typeId(request.leaveTypeId()), request.days(),
                request.onDate(), request.reason());
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/users/{id}/comp-off")
    public ResponseEntity<Void> grantCompOff(@RequestHeader(value = "Authorization", required = false) String authorization,
                                             @PathVariable("id") String id, @RequestBody CompOff request) {
        admin.grantCompOff(Requests.session(auth, authorization, Modules.ATTENDANCE), Requests.id(id),
                request.leaveTypeId() == null || request.leaveTypeId().isBlank() ? null : Requests.id(request.leaveTypeId()),
                request.workDate(), request.days());
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/comp-off/candidates")
    public List<Map<String, Object>> compOffCandidates(@RequestHeader(value = "Authorization", required = false) String authorization) {
        return admin.compOffCandidates(Requests.session(auth, authorization, Modules.ATTENDANCE));
    }

    @PostMapping("/types")
    public Map<String, Object> createType(@RequestHeader(value = "Authorization", required = false) String authorization,
                                          @RequestBody LeaveAdminService.TypeInput request) {
        return admin.saveType(Requests.session(auth, authorization, Modules.ATTENDANCE), null, request);
    }

    @PutMapping("/types/{id}")
    public Map<String, Object> changeType(@RequestHeader(value = "Authorization", required = false) String authorization,
                                          @PathVariable("id") String id, @RequestBody LeaveAdminService.TypeInput request) {
        return admin.saveType(Requests.session(auth, authorization, Modules.ATTENDANCE), Requests.id(id), request);
    }

    @GetMapping("/year-end/preview")
    public Map<String, Object> yearEndPreview(@RequestHeader(value = "Authorization", required = false) String authorization,
                                              @RequestParam(value = "year", required = false) String year) {
        return admin.yearEndPreview(Requests.session(auth, authorization, Modules.ATTENDANCE), year(year));
    }

    @PostMapping("/year-end")
    public Map<String, Object> yearEndRun(@RequestHeader(value = "Authorization", required = false) String authorization,
                                          @RequestBody YearEnd request) {
        if (request.year() == null) {
            throw ApiException.badRequest("Say which leave year to close");
        }
        return admin.yearEndRun(Requests.session(auth, authorization, Modules.ATTENDANCE), request.year());
    }

    private static java.util.UUID typeId(String value) {
        if (value == null || value.isBlank()) {
            throw ApiException.badRequest("Choose a leave type");
        }
        return Requests.id(value);
    }

    private static int year(String value) {
        try {
            int year = Integer.parseInt(value == null ? "" : value.trim());
            if (year < 2000 || year > 2100) {
                throw new NumberFormatException();
            }
            return year;
        } catch (NumberFormatException e) {
            throw ApiException.badRequest("The year must look like 2026");
        }
    }
}
