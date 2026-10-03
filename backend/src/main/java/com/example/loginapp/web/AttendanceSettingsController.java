package com.example.loginapp.web;

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
import com.example.loginapp.core.AttendanceImportService;
import com.example.loginapp.core.AttendanceSettingsService;
import com.example.loginapp.core.AuthService;

/** Work locations, shifts, holidays, assignments and device imports of the caller's Organization. */
@RestController
@RequestMapping("/api/org/attendance")
public class AttendanceSettingsController {

    public record HolidayRequest(String date, String name, String workLocationId) {}

    public record AssignRequest(List<String> membershipIds, String workLocationId, String shiftId,
                                List<Integer> weeklyOffs, String effectiveFrom) {}

    public record CodeRequest(String code) {}

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
    public List<Map<String, Object>> locations(@RequestHeader(value = "Authorization", required = false) String authorization) {
        return settings.locations(Requests.session(auth, authorization));
    }

    @PostMapping("/locations")
    public Map<String, Object> createLocation(@RequestHeader(value = "Authorization", required = false) String authorization,
                                              @RequestBody AttendanceSettingsService.LocationInput request) {
        return settings.saveLocation(Requests.session(auth, authorization), null, request);
    }

    @PutMapping("/locations/{id}")
    public Map<String, Object> changeLocation(@RequestHeader(value = "Authorization", required = false) String authorization,
                                              @PathVariable("id") String id,
                                              @RequestBody AttendanceSettingsService.LocationInput request) {
        return settings.saveLocation(Requests.session(auth, authorization), Requests.id(id), request);
    }

    @GetMapping("/shifts")
    public List<Map<String, Object>> shifts(@RequestHeader(value = "Authorization", required = false) String authorization) {
        return settings.shifts(Requests.session(auth, authorization));
    }

    @PostMapping("/shifts")
    public Map<String, Object> createShift(@RequestHeader(value = "Authorization", required = false) String authorization,
                                           @RequestBody AttendanceSettingsService.ShiftInput request) {
        return settings.saveShift(Requests.session(auth, authorization), null, request);
    }

    @PutMapping("/shifts/{id}")
    public Map<String, Object> changeShift(@RequestHeader(value = "Authorization", required = false) String authorization,
                                           @PathVariable("id") String id,
                                           @RequestBody AttendanceSettingsService.ShiftInput request) {
        return settings.saveShift(Requests.session(auth, authorization), Requests.id(id), request);
    }

    @GetMapping("/holidays")
    public List<Map<String, Object>> holidays(@RequestHeader(value = "Authorization", required = false) String authorization,
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
        return settings.holidays(Requests.session(auth, authorization), y);
    }

    @PostMapping("/holidays")
    public Map<String, Object> addHoliday(@RequestHeader(value = "Authorization", required = false) String authorization,
                                          @RequestBody HolidayRequest request) {
        return settings.addHoliday(Requests.session(auth, authorization), request.date(), request.name(),
                request.workLocationId() == null || request.workLocationId().isBlank() ? null : Requests.id(request.workLocationId()));
    }

    @PostMapping("/holidays/{id}/delete")
    public ResponseEntity<Void> removeHoliday(@RequestHeader(value = "Authorization", required = false) String authorization,
                                              @PathVariable("id") String id) {
        settings.removeHoliday(Requests.session(auth, authorization), Requests.id(id));
        return ResponseEntity.noContent().build();
    }

    /** Every user with their employee code and the assignment in force today. */
    @GetMapping("/assignments")
    public List<Map<String, Object>> people(@RequestHeader(value = "Authorization", required = false) String authorization) {
        return settings.people(Requests.session(auth, authorization));
    }

    @PostMapping("/assignments")
    public ResponseEntity<Void> assign(@RequestHeader(value = "Authorization", required = false) String authorization,
                                       @RequestBody AssignRequest request) {
        settings.assign(Requests.session(auth, authorization), request.membershipIds(),
                request.workLocationId() == null ? null : Requests.id(request.workLocationId()),
                request.shiftId() == null ? null : Requests.id(request.shiftId()),
                request.weeklyOffs(), request.effectiveFrom());
        return ResponseEntity.noContent().build();
    }

    @PutMapping("/users/{id}/employee-code")
    public ResponseEntity<Void> employeeCode(@RequestHeader(value = "Authorization", required = false) String authorization,
                                             @PathVariable("id") String id, @RequestBody CodeRequest request) {
        settings.setEmployeeCode(Requests.session(auth, authorization), Requests.id(id), request.code());
        return ResponseEntity.noContent().build();
    }

    // ── device imports ──────────────────────────────────────────────────

    @GetMapping("/imports")
    public Map<String, Object> importHealth(@RequestHeader(value = "Authorization", required = false) String authorization) {
        return imports.health(Requests.session(auth, authorization));
    }

    @PostMapping("/imports/preview")
    public Map<String, Object> preview(@RequestHeader(value = "Authorization", required = false) String authorization,
                                       @RequestBody ImportRequest request) {
        return imports.preview(Requests.session(auth, authorization), request.deviceLabel(), request.content());
    }

    @PostMapping("/imports")
    public Map<String, Object> importFile(@RequestHeader(value = "Authorization", required = false) String authorization,
                                          @RequestBody ImportRequest request) {
        return imports.importFile(Requests.session(auth, authorization), request.deviceLabel(), request.fileName(),
                request.content(), request.mapping());
    }
}
