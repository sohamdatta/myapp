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

import com.example.loginapp.core.AuthService;
import com.example.loginapp.core.EmployeeService;
import com.example.loginapp.core.IdChecks;

/** Employee records and departments in the caller's Organization. */
@RestController
@RequestMapping("/api/org/people")
public class PeopleController {

    public record Link(String membershipId) {}

    public record Exit(String lastWorkingDay, String reason) {}

    public record Identifier(String kind, String value, String pendingReason) {}

    public record Bank(String holderName, String accountNumber, String ifsc, String bankName, String branch, String effectiveFrom) {}

    public record Reveal(String field, String reason) {}

    public record Department(String name, String parentId, Boolean active) {}

    public record CustomValuesInput(Map<String, Object> values) {}

    private final AuthService auth;
    private final EmployeeService employees;

    public PeopleController(AuthService auth, EmployeeService employees) {
        this.auth = auth;
        this.employees = employees;
    }

    @GetMapping("/employees")
    public ResponseEntity<List<Map<String, Object>>> list(@RequestHeader(value = "Authorization", required = false) String authorization,
                                          @RequestParam(value = "q", required = false) String q,
                                          @RequestParam(value = "status", required = false) String status,
                                          @RequestParam(value = "department", required = false) String department,
                                          @RequestParam(value = "limit", required = false) Integer limit) {
        EmployeeService.Page page = employees.listPage(Requests.session(auth, authorization), q, status, department,
                limit == null ? EmployeeService.MAX_LIST : limit);
        // X-Total-Count is how many match; when it is more than the rows sent, the list was cut off.
        return ResponseEntity.ok().header("X-Total-Count", String.valueOf(page.total())).body(page.rows());
    }

    /** The org chart: everyone working here and who they report to, for every employee. */
    @GetMapping("/org-chart")
    public Map<String, Object> orgChart(@RequestHeader(value = "Authorization", required = false) String authorization) {
        return employees.orgChart(Requests.session(auth, authorization));
    }

    @PostMapping("/employees")
    public Map<String, Object> add(@RequestHeader(value = "Authorization", required = false) String authorization,
                                   @RequestBody EmployeeService.NewEmployee request) {
        return employees.add(Requests.session(auth, authorization), request);
    }

    @GetMapping("/employees/{id}")
    public Map<String, Object> get(@RequestHeader(value = "Authorization", required = false) String authorization,
                                   @PathVariable("id") String id) {
        return employees.get(Requests.session(auth, authorization), Requests.id(id));
    }

    /** The full value of one identifier; a POST so the reason is not left in access logs or browser history. */
    @PostMapping("/employees/{id}/reveal")
    public Map<String, Object> reveal(@RequestHeader(value = "Authorization", required = false) String authorization,
                                      @PathVariable("id") String id, @RequestBody Reveal request) {
        return employees.reveal(Requests.session(auth, authorization), Requests.id(id), request.field(), request.reason());
    }

    @PostMapping("/employees/{id}/job")
    public ResponseEntity<Void> changeJob(@RequestHeader(value = "Authorization", required = false) String authorization,
                                          @PathVariable("id") String id, @RequestBody EmployeeService.JobChange request) {
        employees.changeJob(Requests.session(auth, authorization), Requests.id(id), request);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/employees/{id}/exit")
    public ResponseEntity<Void> exit(@RequestHeader(value = "Authorization", required = false) String authorization,
                                     @PathVariable("id") String id, @RequestBody Exit request) {
        employees.exit(Requests.session(auth, authorization), Requests.id(id), request.lastWorkingDay(), request.reason());
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/employees/{id}/link")
    public ResponseEntity<Void> link(@RequestHeader(value = "Authorization", required = false) String authorization,
                                     @PathVariable("id") String id, @RequestBody Link request) {
        employees.link(Requests.session(auth, authorization), Requests.id(id), Requests.id(request.membershipId()));
        return ResponseEntity.noContent().build();
    }

    @PutMapping("/employees/{id}/identifiers")
    public ResponseEntity<Void> identifier(@RequestHeader(value = "Authorization", required = false) String authorization,
                                           @PathVariable("id") String id, @RequestBody Identifier request) {
        employees.setIdentifier(Requests.session(auth, authorization), Requests.id(id), request.kind(), request.value(),
                request.pendingReason());
        return ResponseEntity.noContent().build();
    }

    @PutMapping("/employees/{id}/bank")
    public ResponseEntity<Void> bank(@RequestHeader(value = "Authorization", required = false) String authorization,
                                     @PathVariable("id") String id, @RequestBody Bank request) {
        employees.setBank(Requests.session(auth, authorization), Requests.id(id), request.holderName(), request.accountNumber(),
                request.ifsc(), request.bankName(), request.branch(), request.effectiveFrom());
        return ResponseEntity.noContent().build();
    }

    @PutMapping("/employees/{id}/custom-fields")
    public ResponseEntity<Void> customFields(@RequestHeader(value = "Authorization", required = false) String authorization,
                                             @PathVariable("id") String id, @RequestBody CustomValuesInput request) {
        employees.saveCustomValues(Requests.session(auth, authorization), Requests.id(id), request.values());
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/unlinked-users")
    public List<Map<String, Object>> unlinkedUsers(@RequestHeader(value = "Authorization", required = false) String authorization) {
        return employees.unlinkedUsers(Requests.session(auth, authorization));
    }

    @GetMapping("/departments")
    public List<Map<String, Object>> departments(@RequestHeader(value = "Authorization", required = false) String authorization) {
        return employees.departments(Requests.session(auth, authorization));
    }

    @PostMapping("/departments")
    public Map<String, Object> createDepartment(@RequestHeader(value = "Authorization", required = false) String authorization,
                                                @RequestBody Department request) {
        return employees.saveDepartment(Requests.session(auth, authorization), null, request.name(), request.parentId(), null);
    }

    @PutMapping("/departments/{id}")
    public Map<String, Object> changeDepartment(@RequestHeader(value = "Authorization", required = false) String authorization,
                                                @PathVariable("id") String id, @RequestBody Department request) {
        return employees.saveDepartment(Requests.session(auth, authorization), Requests.id(id), request.name(),
                request.parentId(), request.active());
    }

    /** States and union territories, for address forms. */
    @GetMapping("/states")
    public Map<String, String> states() {
        return IdChecks.STATES;
    }
}
