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

import com.example.loginapp.core.AuthService;
import com.example.loginapp.core.SessionCtx;
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
    public ResponseEntity<List<Map<String, Object>>> list(SessionCtx ctx,
                                          @RequestParam(value = "q", required = false) String q,
                                          @RequestParam(value = "status", required = false) String status,
                                          @RequestParam(value = "department", required = false) String department,
                                          @RequestParam(value = "limit", required = false) Integer limit) {
        EmployeeService.Page page = employees.listPage(ctx, q, status, department,
                limit == null ? EmployeeService.MAX_LIST : limit);
        // X-Total-Count is how many match; when it is more than the rows sent, the list was cut off.
        return ResponseEntity.ok().header("X-Total-Count", String.valueOf(page.total())).body(page.rows());
    }

    /** The org chart: everyone working here and who they report to, for every employee. */
    @GetMapping("/org-chart")
    public Map<String, Object> orgChart(SessionCtx ctx) {
        return employees.orgChart(ctx);
    }

    /** Employee licences bought and in use, so screens can say "42 of 50" and stop offering Add at the limit. */
    @GetMapping("/employees/licence")
    public EmployeeService.Licence licence(SessionCtx ctx) {
        return employees.licence(ctx);
    }

    @PostMapping("/employees")
    public Map<String, Object> add(SessionCtx ctx,
                                   @RequestBody EmployeeService.NewEmployee request) {
        return employees.add(ctx, request);
    }

    @GetMapping("/employees/{id}")
    public Map<String, Object> get(SessionCtx ctx,
                                   @PathVariable("id") String id) {
        return employees.get(ctx, Requests.id(id));
    }

    /** The full value of one identifier; a POST so the reason is not left in access logs or browser history. */
    @PostMapping("/employees/{id}/reveal")
    public Map<String, Object> reveal(SessionCtx ctx,
                                      @PathVariable("id") String id, @RequestBody Reveal request) {
        return employees.reveal(ctx, Requests.id(id), request.field(), request.reason());
    }

    @PostMapping("/employees/{id}/job")
    public ResponseEntity<Void> changeJob(SessionCtx ctx,
                                          @PathVariable("id") String id, @RequestBody EmployeeService.JobChange request) {
        employees.changeJob(ctx, Requests.id(id), request);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/employees/{id}/exit")
    public ResponseEntity<Void> exit(SessionCtx ctx,
                                     @PathVariable("id") String id, @RequestBody Exit request) {
        employees.exit(ctx, Requests.id(id), request.lastWorkingDay(), request.reason());
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/employees/{id}/link")
    public ResponseEntity<Void> link(SessionCtx ctx,
                                     @PathVariable("id") String id, @RequestBody Link request) {
        employees.link(ctx, Requests.id(id), Requests.id(request.membershipId()));
        return ResponseEntity.noContent().build();
    }

    @PutMapping("/employees/{id}/identifiers")
    public ResponseEntity<Void> identifier(SessionCtx ctx,
                                           @PathVariable("id") String id, @RequestBody Identifier request) {
        employees.setIdentifier(ctx, Requests.id(id), request.kind(), request.value(),
                request.pendingReason());
        return ResponseEntity.noContent().build();
    }

    @PutMapping("/employees/{id}/bank")
    public ResponseEntity<Void> bank(SessionCtx ctx,
                                     @PathVariable("id") String id, @RequestBody Bank request) {
        employees.setBank(ctx, Requests.id(id), request.holderName(), request.accountNumber(),
                request.ifsc(), request.bankName(), request.branch(), request.effectiveFrom());
        return ResponseEntity.noContent().build();
    }

    @PutMapping("/employees/{id}/custom-fields")
    public ResponseEntity<Void> customFields(SessionCtx ctx,
                                             @PathVariable("id") String id, @RequestBody CustomValuesInput request) {
        employees.saveCustomValues(ctx, Requests.id(id), request.values());
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/unlinked-users")
    public List<Map<String, Object>> unlinkedUsers(SessionCtx ctx) {
        return employees.unlinkedUsers(ctx);
    }

    @GetMapping("/departments")
    public List<Map<String, Object>> departments(SessionCtx ctx) {
        return employees.departments(ctx);
    }

    @PostMapping("/departments")
    public Map<String, Object> createDepartment(SessionCtx ctx,
                                                @RequestBody Department request) {
        return employees.saveDepartment(ctx, null, request.name(), request.parentId(), null);
    }

    @PutMapping("/departments/{id}")
    public Map<String, Object> changeDepartment(SessionCtx ctx,
                                                @PathVariable("id") String id, @RequestBody Department request) {
        return employees.saveDepartment(ctx, Requests.id(id), request.name(),
                request.parentId(), request.active());
    }

    /** States and union territories, for address forms. */
    @GetMapping("/states")
    public Map<String, String> states() {
        return IdChecks.STATES;
    }
}
