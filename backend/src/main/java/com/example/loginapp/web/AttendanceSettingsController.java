package com.example.loginapp.web;

import java.util.List;
import java.util.Map;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.example.loginapp.core.ApiException;
import com.example.loginapp.core.SessionCtx;
import com.example.loginapp.core.AttendanceImportService;
import com.example.loginapp.core.AttendanceSettingsService;
import com.example.loginapp.core.AuthService;
import com.example.loginapp.core.Modules;

/** Work locations, shifts, holidays, assignments and device imports of the caller's Organization. */
@RestController
@RequestMapping("/api/org/attendance")
public class AttendanceSettingsController {

    public record HolidayRequest(String date, String name, String workLocationId) {}

    public record AssignRequest(List<String> membershipIds, String workLocationId, String shiftId,
                                List<Integer> weeklyOffs, List<Integer> remoteDays, String effectiveFrom) {}

    public record AllowedRequest(Boolean allowed) {}

    public record CodeRequest(String code) {}

    public record ModeRequest(String mode, String effectiveFrom) {}

    public record ImportRequest(String deviceLabel, String fileName, String content, AttendanceImportService.Mapping mapping) {}

    private final AuthService auth;
    private final AttendanceSettingsService settings;
    private final AttendanceImportService imports;

    public AttendanceSettingsController(AuthService auth, AttendanceSettingsService settings, AttendanceImportService imports) {
        this.auth = auth;
        this.settings = settings;
        this.imports = imports;
    }

    @GetMapping("/locations")
    public List<Map<String, Object>> locations(SessionCtx ctx) {
        return settings.locations(ctx);
    }

    @PostMapping("/locations")
    public Map<String, Object> createLocation(SessionCtx ctx,
                                              @RequestBody AttendanceSettingsService.LocationInput request) {
        return settings.saveLocation(ctx, null, request);
    }

    @PutMapping("/locations/{id}")
    public Map<String, Object> changeLocation(SessionCtx ctx,
                                              @PathVariable("id") String id,
                                              @RequestBody AttendanceSettingsService.LocationInput request) {
        return settings.saveLocation(ctx, Requests.id(id), request);
    }

    /** How the location records attendance (app, device or present), from a date. */
    @PostMapping("/locations/{id}/mode")
    public Map<String, Object> setMode(@RequiresModule(Modules.ATTENDANCE) SessionCtx ctx,
                                       @PathVariable("id") String id, @RequestBody ModeRequest request) {
        return settings.setMode(ctx, Requests.id(id), request.mode(), request.effectiveFrom());
    }

    @GetMapping("/shifts")
    public List<Map<String, Object>> shifts(SessionCtx ctx) {
        return settings.shifts(ctx);
    }

    @PostMapping("/shifts")
    public Map<String, Object> createShift(SessionCtx ctx,
                                           @RequestBody AttendanceSettingsService.ShiftInput request) {
        return settings.saveShift(ctx, null, request);
    }

    @PutMapping("/shifts/{id}")
    public Map<String, Object> changeShift(SessionCtx ctx,
                                           @PathVariable("id") String id,
                                           @RequestBody AttendanceSettingsService.ShiftInput request) {
        return settings.saveShift(ctx, Requests.id(id), request);
    }

    @GetMapping("/holidays")
    public List<Map<String, Object>> holidays(SessionCtx ctx,
                                              @RequestParam(value = "year", required = false) String year) {
        Integer y = null;
        if (year != null && !year.isBlank()) {
            try {
                y = Integer.valueOf(year.trim());
            } catch (NumberFormatException e) {
                throw ApiException.badRequest("The year must look like 2026");
            }
            if (y < 2000 || y > 2100) {
                throw ApiException.badRequest("The year must look like 2026");
            }
        }
        return settings.holidays(ctx, y);
    }

    @PostMapping("/holidays")
    public Map<String, Object> addHoliday(SessionCtx ctx,
                                          @RequestBody HolidayRequest request) {
        return settings.addHoliday(ctx, request.date(), request.name(),
                request.workLocationId() == null || request.workLocationId().isBlank() ? null : Requests.id(request.workLocationId()));
    }

    @PostMapping("/holidays/{id}/delete")
    public ResponseEntity<Void> removeHoliday(SessionCtx ctx,
                                              @PathVariable("id") String id) {
        settings.removeHoliday(ctx, Requests.id(id));
        return ResponseEntity.noContent().build();
    }

    /** Every user with their employee code and the assignment in force today. */
    @GetMapping("/assignments")
    public List<Map<String, Object>> people(SessionCtx ctx) {
        return settings.people(ctx);
    }

    @PostMapping("/assignments")
    public ResponseEntity<Void> assign(SessionCtx ctx,
                                       @RequestBody AssignRequest request) {
        settings.assign(ctx, request.membershipIds(),
                request.workLocationId() == null ? null : Requests.id(request.workLocationId()),
                request.shiftId() == null ? null : Requests.id(request.shiftId()),
                request.weeklyOffs(), request.remoteDays(), request.effectiveFrom());
        return ResponseEntity.noContent().build();
    }

    @PutMapping("/users/{id}/employee-code")
    public ResponseEntity<Void> employeeCode(SessionCtx ctx,
                                             @PathVariable("id") String id, @RequestBody CodeRequest request) {
        settings.setEmployeeCode(ctx, Requests.id(id), request.code());
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/settings")
    public Map<String, Object> settings(SessionCtx ctx) {
        return settings.settings(ctx);
    }

    @PutMapping("/settings")
    public Map<String, Object> saveSettings(@RequiresModule(Modules.ATTENDANCE) SessionCtx ctx,
                                            @RequestBody AttendanceSettingsService.SettingsInput request) {
        return settings.saveSettings(ctx, request);
    }

    @PutMapping("/users/{id}/wfh")
    public ResponseEntity<Void> wfhAllowed(@RequiresModule(Modules.ATTENDANCE) SessionCtx ctx,
                                           @PathVariable("id") String id, @RequestBody AllowedRequest request) {
        settings.setWfhAllowed(ctx, Requests.id(id), Boolean.TRUE.equals(request.allowed()));
        return ResponseEntity.noContent().build();
    }

    // ── device imports ──────────────────────────────────────────────────

    @GetMapping("/imports")
    public Map<String, Object> importHealth(@RequiresModule(Modules.ATTENDANCE) SessionCtx ctx) {
        return imports.health(ctx);
    }

    @PostMapping("/imports/preview")
    public Map<String, Object> preview(@RequiresModule(Modules.ATTENDANCE) SessionCtx ctx,
                                       @RequestBody ImportRequest request) {
        return imports.preview(ctx, request.deviceLabel(), request.content());
    }

    @PostMapping("/imports")
    public Map<String, Object> importFile(@RequiresModule(Modules.ATTENDANCE) SessionCtx ctx,
                                          @RequestBody ImportRequest request) {
        return imports.importFile(ctx, request.deviceLabel(), request.fileName(),
                request.content(), request.mapping());
    }
}
