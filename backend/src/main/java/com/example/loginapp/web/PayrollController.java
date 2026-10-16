package com.example.loginapp.web;

import java.util.List;
import java.util.Map;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.example.loginapp.core.AuthService;
import com.example.loginapp.core.PayrollConfig;
import com.example.loginapp.core.PayrollPay;
import com.example.loginapp.core.PayrollRuns;

/** Payroll: setup, each employee's pay, the monthly runs and their outputs, and My pay. */
@RestController
@RequestMapping("/api/org")
public class PayrollController {

    public record FormulaInput(String formula) {}

    public record OpenInput(String groupId, String period) {}

    public record MembersInput(List<String> employmentIds, String validFrom) {}

    public record HoldInput(String employmentId, String reason) {}

    private final AuthService auth;
    private final PayrollConfig config;
    private final PayrollPay pay;
    private final PayrollRuns runs;

    public PayrollController(AuthService auth, PayrollConfig config, PayrollPay pay, PayrollRuns runs) {
        this.auth = auth;
        this.config = config;
        this.pay = pay;
        this.runs = runs;
    }

    // ── setup ───────────────────────────────────────────────────────────

    @GetMapping("/payroll/setup")
    public Map<String, Object> setup(@RequestHeader(value = "Authorization", required = false) String authorization) {
        return config.setup(Requests.session(auth, authorization));
    }

    @PutMapping("/payroll/settings")
    public Map<String, Object> settings(@RequestHeader(value = "Authorization", required = false) String authorization,
                                        @RequestBody PayrollConfig.SettingsInput request) {
        return config.saveSettings(Requests.session(auth, authorization), request);
    }

    @PostMapping("/payroll/registrations")
    public Map<String, Object> addRegistration(@RequestHeader(value = "Authorization", required = false) String authorization,
                                               @RequestBody PayrollConfig.RegistrationInput request) {
        return config.addRegistration(Requests.session(auth, authorization), request);
    }

    @DeleteMapping("/payroll/registrations/{id}")
    public ResponseEntity<Void> removeRegistration(@RequestHeader(value = "Authorization", required = false) String authorization,
                                                   @PathVariable("id") String id) {
        config.removeRegistration(Requests.session(auth, authorization), Requests.id(id));
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/payroll/components")
    public Map<String, Object> addComponent(@RequestHeader(value = "Authorization", required = false) String authorization,
                                            @RequestBody PayrollConfig.ComponentInput request) {
        return config.saveComponent(Requests.session(auth, authorization), null, request);
    }

    @PutMapping("/payroll/components/{id}")
    public Map<String, Object> changeComponent(@RequestHeader(value = "Authorization", required = false) String authorization,
                                               @PathVariable("id") String id, @RequestBody PayrollConfig.ComponentInput request) {
        return config.saveComponent(Requests.session(auth, authorization), Requests.id(id), request);
    }

    @DeleteMapping("/payroll/components/{id}")
    public ResponseEntity<Void> retireComponent(@RequestHeader(value = "Authorization", required = false) String authorization,
                                                @PathVariable("id") String id) {
        config.retireComponent(Requests.session(auth, authorization), Requests.id(id));
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/payroll/components/{id}/history")
    public List<Map<String, Object>> componentHistory(@RequestHeader(value = "Authorization", required = false) String authorization,
                                                      @PathVariable("id") String id) {
        return config.componentHistory(Requests.session(auth, authorization), Requests.id(id));
    }

    @PostMapping("/payroll/formula/check")
    public Map<String, Object> checkFormula(@RequestHeader(value = "Authorization", required = false) String authorization,
                                            @RequestBody FormulaInput request) {
        return config.checkFormula(Requests.session(auth, authorization), request.formula());
    }

    @PostMapping("/payroll/tables")
    public Map<String, Object> addTable(@RequestHeader(value = "Authorization", required = false) String authorization,
                                        @RequestBody PayrollConfig.TableInput request) {
        return config.saveTable(Requests.session(auth, authorization), null, request);
    }

    @PutMapping("/payroll/tables/{id}")
    public Map<String, Object> changeTable(@RequestHeader(value = "Authorization", required = false) String authorization,
                                           @PathVariable("id") String id, @RequestBody PayrollConfig.TableInput request) {
        return config.saveTable(Requests.session(auth, authorization), Requests.id(id), request);
    }

    @PostMapping("/payroll/structures")
    public Map<String, Object> addStructure(@RequestHeader(value = "Authorization", required = false) String authorization,
                                            @RequestBody PayrollConfig.StructureInput request) {
        return config.saveStructure(Requests.session(auth, authorization), null, request);
    }

    @PutMapping("/payroll/structures/{id}")
    public Map<String, Object> changeStructure(@RequestHeader(value = "Authorization", required = false) String authorization,
                                               @PathVariable("id") String id, @RequestBody PayrollConfig.StructureInput request) {
        return config.saveStructure(Requests.session(auth, authorization), Requests.id(id), request);
    }

    @PostMapping("/payroll/groups")
    public Map<String, Object> addGroup(@RequestHeader(value = "Authorization", required = false) String authorization,
                                        @RequestBody PayrollConfig.GroupInput request) {
        return config.saveGroup(Requests.session(auth, authorization), null, request);
    }

    @PutMapping("/payroll/groups/{id}")
    public Map<String, Object> changeGroup(@RequestHeader(value = "Authorization", required = false) String authorization,
                                           @PathVariable("id") String id, @RequestBody PayrollConfig.GroupInput request) {
        return config.saveGroup(Requests.session(auth, authorization), Requests.id(id), request);
    }

    @GetMapping("/payroll/groups/{id}/members")
    public Map<String, Object> members(@RequestHeader(value = "Authorization", required = false) String authorization,
                                       @PathVariable("id") String id) {
        return config.members(Requests.session(auth, authorization), Requests.id(id));
    }

    @PostMapping("/payroll/groups/{id}/members")
    public Map<String, Object> addMembers(@RequestHeader(value = "Authorization", required = false) String authorization,
                                          @PathVariable("id") String id, @RequestBody MembersInput request) {
        return config.addMembers(Requests.session(auth, authorization), Requests.id(id), request.employmentIds(), request.validFrom());
    }

    // ── runs ────────────────────────────────────────────────────────────

    @GetMapping("/payroll/runs")
    public Map<String, Object> runs(@RequestHeader(value = "Authorization", required = false) String authorization) {
        return runs.list(Requests.session(auth, authorization));
    }

    @PostMapping("/payroll/runs")
    public Map<String, Object> open(@RequestHeader(value = "Authorization", required = false) String authorization,
                                    @RequestBody OpenInput request) {
        return runs.open(Requests.session(auth, authorization), Requests.id(request.groupId()), request.period());
    }

    @GetMapping("/payroll/runs/{id}")
    public Map<String, Object> run(@RequestHeader(value = "Authorization", required = false) String authorization,
                                   @PathVariable("id") String id) {
        return runs.get(Requests.session(auth, authorization), Requests.id(id));
    }

    @PostMapping("/payroll/runs/{id}/inputs")
    public Map<String, Object> addInput(@RequestHeader(value = "Authorization", required = false) String authorization,
                                        @PathVariable("id") String id, @RequestBody PayrollRuns.InputInput request) {
        return runs.addInput(Requests.session(auth, authorization), Requests.id(id), request);
    }

    @DeleteMapping("/payroll/runs/{id}/inputs/{input}")
    public ResponseEntity<Void> removeInput(@RequestHeader(value = "Authorization", required = false) String authorization,
                                            @PathVariable("id") String id, @PathVariable("input") String input) {
        runs.removeInput(Requests.session(auth, authorization), Requests.id(id), Requests.id(input));
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/payroll/runs/{id}/hold")
    public ResponseEntity<Void> hold(@RequestHeader(value = "Authorization", required = false) String authorization,
                                     @PathVariable("id") String id, @RequestBody HoldInput request) {
        runs.hold(Requests.session(auth, authorization), Requests.id(id), Requests.id(request.employmentId()), request.reason(), true);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/payroll/runs/{id}/unhold")
    public ResponseEntity<Void> unhold(@RequestHeader(value = "Authorization", required = false) String authorization,
                                       @PathVariable("id") String id, @RequestBody HoldInput request) {
        runs.hold(Requests.session(auth, authorization), Requests.id(id), Requests.id(request.employmentId()), null, false);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/payroll/runs/{id}/freeze")
    public Map<String, Object> freeze(@RequestHeader(value = "Authorization", required = false) String authorization,
                                      @PathVariable("id") String id) {
        return runs.freeze(Requests.session(auth, authorization), Requests.id(id));
    }

    @PostMapping("/payroll/runs/{id}/calculate")
    public Map<String, Object> calculate(@RequestHeader(value = "Authorization", required = false) String authorization,
                                         @PathVariable("id") String id) {
        return runs.calculate(Requests.session(auth, authorization), Requests.id(id));
    }

    @PostMapping("/payroll/runs/{id}/reopen")
    public ResponseEntity<Void> reopen(@RequestHeader(value = "Authorization", required = false) String authorization,
                                       @PathVariable("id") String id, @RequestBody PayrollRuns.ReasonInput request) {
        runs.reopen(Requests.session(auth, authorization), Requests.id(id), request.reason());
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/payroll/runs/{id}/exceptions/{exception}/accept")
    public ResponseEntity<Void> accept(@RequestHeader(value = "Authorization", required = false) String authorization,
                                       @PathVariable("id") String id, @PathVariable("exception") String exception,
                                       @RequestBody PayrollRuns.ReasonInput request) {
        runs.accept(Requests.session(auth, authorization), Requests.id(id), Requests.id(exception), request.reason());
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/payroll/runs/{id}/approve")
    public ResponseEntity<Void> approve(@RequestHeader(value = "Authorization", required = false) String authorization,
                                        @PathVariable("id") String id) {
        runs.approve(Requests.session(auth, authorization), Requests.id(id));
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/payroll/runs/{id}/send-back")
    public ResponseEntity<Void> sendBack(@RequestHeader(value = "Authorization", required = false) String authorization,
                                         @PathVariable("id") String id, @RequestBody PayrollRuns.ReasonInput request) {
        runs.sendBack(Requests.session(auth, authorization), Requests.id(id), request.reason());
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/payroll/runs/{id}/paid")
    public ResponseEntity<Void> paid(@RequestHeader(value = "Authorization", required = false) String authorization,
                                     @PathVariable("id") String id, @RequestBody PayrollRuns.PaidInput request) {
        runs.markPaid(Requests.session(auth, authorization), Requests.id(id), request);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/payroll/runs/{id}/release")
    public ResponseEntity<Void> release(@RequestHeader(value = "Authorization", required = false) String authorization,
                                        @PathVariable("id") String id) {
        runs.release(Requests.session(auth, authorization), Requests.id(id));
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/payroll/runs/{id}/employees/{employment}")
    public Map<String, Object> employee(@RequestHeader(value = "Authorization", required = false) String authorization,
                                        @PathVariable("id") String id, @PathVariable("employment") String employment) {
        return runs.employee(Requests.session(auth, authorization), Requests.id(id), Requests.id(employment));
    }

    @GetMapping("/payroll/runs/{id}/employees/{employment}/payslip")
    public ResponseEntity<byte[]> payslip(@RequestHeader(value = "Authorization", required = false) String authorization,
                                          @PathVariable("id") String id, @PathVariable("employment") String employment) {
        return DocumentController.file(runs.payslipPdf(Requests.session(auth, authorization), Requests.id(id), Requests.id(employment)));
    }

    @GetMapping("/payroll/runs/{id}/outputs/{kind}")
    public ResponseEntity<byte[]> output(@RequestHeader(value = "Authorization", required = false) String authorization,
                                         @PathVariable("id") String id, @PathVariable("kind") String kind) {
        return DocumentController.file(runs.output(Requests.session(auth, authorization), Requests.id(id), kind));
    }

    // ── an employee's pay ───────────────────────────────────────────────

    @GetMapping("/people/employees/{id}/pay")
    public Map<String, Object> pay(@RequestHeader(value = "Authorization", required = false) String authorization,
                                   @PathVariable("id") String id) {
        return pay.get(Requests.session(auth, authorization), Requests.id(id));
    }

    @PostMapping("/people/employees/{id}/pay/preview")
    public Map<String, Object> preview(@RequestHeader(value = "Authorization", required = false) String authorization,
                                       @PathVariable("id") String id, @RequestBody PayrollPay.ReviseInput request) {
        return pay.preview(Requests.session(auth, authorization), Requests.id(id), request);
    }

    @PostMapping("/people/employees/{id}/pay")
    public Map<String, Object> revise(@RequestHeader(value = "Authorization", required = false) String authorization,
                                      @PathVariable("id") String id, @RequestBody PayrollPay.ReviseInput request) {
        return pay.revise(Requests.session(auth, authorization), Requests.id(id), request);
    }

    @PostMapping("/people/employees/{id}/pay/overrides")
    public Map<String, Object> addOverride(@RequestHeader(value = "Authorization", required = false) String authorization,
                                           @PathVariable("id") String id, @RequestBody PayrollPay.OverrideInput request) {
        return pay.addOverride(Requests.session(auth, authorization), Requests.id(id), request);
    }

    @DeleteMapping("/people/employees/{id}/pay/overrides/{override}")
    public ResponseEntity<Void> removeOverride(@RequestHeader(value = "Authorization", required = false) String authorization,
                                               @PathVariable("id") String id, @PathVariable("override") String override) {
        pay.removeOverride(Requests.session(auth, authorization), Requests.id(id), Requests.id(override));
        return ResponseEntity.noContent().build();
    }

    @PutMapping("/people/employees/{id}/pay/profile")
    public Map<String, Object> profile(@RequestHeader(value = "Authorization", required = false) String authorization,
                                       @PathVariable("id") String id, @RequestBody PayrollPay.ProfileInput request) {
        return pay.saveProfile(Requests.session(auth, authorization), Requests.id(id), request);
    }

    // ── My pay ──────────────────────────────────────────────────────────

    @GetMapping("/me/pay")
    public Map<String, Object> myPay(@RequestHeader(value = "Authorization", required = false) String authorization) {
        return runs.myPay(Requests.session(auth, authorization));
    }

    @GetMapping("/me/pay/{id}")
    public Map<String, Object> myPayslip(@RequestHeader(value = "Authorization", required = false) String authorization,
                                         @PathVariable("id") String id) {
        return runs.myPayslip(Requests.session(auth, authorization), Requests.id(id));
    }

    @GetMapping("/me/pay/{id}/pdf")
    public ResponseEntity<byte[]> myPayslipPdf(@RequestHeader(value = "Authorization", required = false) String authorization,
                                               @PathVariable("id") String id) {
        return DocumentController.file(runs.myPayslipPdf(Requests.session(auth, authorization), Requests.id(id)));
    }
}
