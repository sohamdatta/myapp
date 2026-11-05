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
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.example.loginapp.core.AuthService;
import com.example.loginapp.core.SessionCtx;
import com.example.loginapp.core.Modules;
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
    public Map<String, Object> setup(@RequiresModule(Modules.PAYROLL) SessionCtx ctx) {
        return config.setup(ctx);
    }

    @PutMapping("/payroll/settings")
    public Map<String, Object> settings(@RequiresModule(Modules.PAYROLL) SessionCtx ctx,
                                        @RequestBody PayrollConfig.SettingsInput request) {
        return config.saveSettings(ctx, request);
    }

    @PostMapping("/payroll/registrations")
    public Map<String, Object> addRegistration(@RequiresModule(Modules.PAYROLL) SessionCtx ctx,
                                               @RequestBody PayrollConfig.RegistrationInput request) {
        return config.addRegistration(ctx, request);
    }

    @DeleteMapping("/payroll/registrations/{id}")
    public ResponseEntity<Void> removeRegistration(@RequiresModule(Modules.PAYROLL) SessionCtx ctx,
                                                   @PathVariable("id") String id) {
        config.removeRegistration(ctx, Requests.id(id));
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/payroll/components")
    public Map<String, Object> addComponent(@RequiresModule(Modules.PAYROLL) SessionCtx ctx,
                                            @RequestBody PayrollConfig.ComponentInput request) {
        return config.saveComponent(ctx, null, request);
    }

    @PutMapping("/payroll/components/{id}")
    public Map<String, Object> changeComponent(@RequiresModule(Modules.PAYROLL) SessionCtx ctx,
                                               @PathVariable("id") String id, @RequestBody PayrollConfig.ComponentInput request) {
        return config.saveComponent(ctx, Requests.id(id), request);
    }

    @DeleteMapping("/payroll/components/{id}")
    public ResponseEntity<Void> retireComponent(@RequiresModule(Modules.PAYROLL) SessionCtx ctx,
                                                @PathVariable("id") String id) {
        config.retireComponent(ctx, Requests.id(id));
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/payroll/components/{id}/history")
    public List<Map<String, Object>> componentHistory(@RequiresModule(Modules.PAYROLL) SessionCtx ctx,
                                                      @PathVariable("id") String id) {
        return config.componentHistory(ctx, Requests.id(id));
    }

    @PostMapping("/payroll/formula/check")
    public Map<String, Object> checkFormula(@RequiresModule(Modules.PAYROLL) SessionCtx ctx,
                                            @RequestBody FormulaInput request) {
        return config.checkFormula(ctx, request.formula());
    }

    @PostMapping("/payroll/tables")
    public Map<String, Object> addTable(@RequiresModule(Modules.PAYROLL) SessionCtx ctx,
                                        @RequestBody PayrollConfig.TableInput request) {
        return config.saveTable(ctx, null, request);
    }

    @PutMapping("/payroll/tables/{id}")
    public Map<String, Object> changeTable(@RequiresModule(Modules.PAYROLL) SessionCtx ctx,
                                           @PathVariable("id") String id, @RequestBody PayrollConfig.TableInput request) {
        return config.saveTable(ctx, Requests.id(id), request);
    }

    @PostMapping("/payroll/structures")
    public Map<String, Object> addStructure(@RequiresModule(Modules.PAYROLL) SessionCtx ctx,
                                            @RequestBody PayrollConfig.StructureInput request) {
        return config.saveStructure(ctx, null, request);
    }

    @PutMapping("/payroll/structures/{id}")
    public Map<String, Object> changeStructure(@RequiresModule(Modules.PAYROLL) SessionCtx ctx,
                                               @PathVariable("id") String id, @RequestBody PayrollConfig.StructureInput request) {
        return config.saveStructure(ctx, Requests.id(id), request);
    }

    @PostMapping("/payroll/groups")
    public Map<String, Object> addGroup(@RequiresModule(Modules.PAYROLL) SessionCtx ctx,
                                        @RequestBody PayrollConfig.GroupInput request) {
        return config.saveGroup(ctx, null, request);
    }

    @PutMapping("/payroll/groups/{id}")
    public Map<String, Object> changeGroup(@RequiresModule(Modules.PAYROLL) SessionCtx ctx,
                                           @PathVariable("id") String id, @RequestBody PayrollConfig.GroupInput request) {
        return config.saveGroup(ctx, Requests.id(id), request);
    }

    @GetMapping("/payroll/groups/{id}/members")
    public Map<String, Object> members(@RequiresModule(Modules.PAYROLL) SessionCtx ctx,
                                       @PathVariable("id") String id) {
        return config.members(ctx, Requests.id(id));
    }

    @PostMapping("/payroll/groups/{id}/members")
    public Map<String, Object> addMembers(@RequiresModule(Modules.PAYROLL) SessionCtx ctx,
                                          @PathVariable("id") String id, @RequestBody MembersInput request) {
        return config.addMembers(ctx, Requests.id(id), request.employmentIds(), request.validFrom());
    }

    // ── runs ────────────────────────────────────────────────────────────

    @GetMapping("/payroll/runs")
    public Map<String, Object> runs(@RequiresModule(Modules.PAYROLL) SessionCtx ctx) {
        return runs.list(ctx);
    }

    @PostMapping("/payroll/runs")
    public Map<String, Object> open(@RequiresModule(Modules.PAYROLL) SessionCtx ctx,
                                    @RequestBody OpenInput request) {
        return runs.open(ctx, Requests.id(request.groupId()), request.period());
    }

    @GetMapping("/payroll/runs/{id}")
    public Map<String, Object> run(@RequiresModule(Modules.PAYROLL) SessionCtx ctx,
                                   @PathVariable("id") String id) {
        return runs.get(ctx, Requests.id(id));
    }

    @PostMapping("/payroll/runs/{id}/inputs")
    public Map<String, Object> addInput(@RequiresModule(Modules.PAYROLL) SessionCtx ctx,
                                        @PathVariable("id") String id, @RequestBody PayrollRuns.InputInput request) {
        return runs.addInput(ctx, Requests.id(id), request);
    }

    @DeleteMapping("/payroll/runs/{id}/inputs/{input}")
    public ResponseEntity<Void> removeInput(@RequiresModule(Modules.PAYROLL) SessionCtx ctx,
                                            @PathVariable("id") String id, @PathVariable("input") String input) {
        runs.removeInput(ctx, Requests.id(id), Requests.id(input));
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/payroll/runs/{id}/hold")
    public ResponseEntity<Void> hold(@RequiresModule(Modules.PAYROLL) SessionCtx ctx,
                                     @PathVariable("id") String id, @RequestBody HoldInput request) {
        runs.hold(ctx, Requests.id(id), Requests.id(request.employmentId()), request.reason(), true);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/payroll/runs/{id}/unhold")
    public ResponseEntity<Void> unhold(@RequiresModule(Modules.PAYROLL) SessionCtx ctx,
                                       @PathVariable("id") String id, @RequestBody HoldInput request) {
        runs.hold(ctx, Requests.id(id), Requests.id(request.employmentId()), null, false);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/payroll/runs/{id}/freeze")
    public Map<String, Object> freeze(@RequiresModule(Modules.PAYROLL) SessionCtx ctx,
                                      @PathVariable("id") String id) {
        return runs.freeze(ctx, Requests.id(id));
    }

    @PostMapping("/payroll/runs/{id}/calculate")
    public Map<String, Object> calculate(@RequiresModule(Modules.PAYROLL) SessionCtx ctx,
                                         @PathVariable("id") String id) {
        return runs.calculate(ctx, Requests.id(id));
    }

    @PostMapping("/payroll/runs/{id}/reopen")
    public ResponseEntity<Void> reopen(@RequiresModule(Modules.PAYROLL) SessionCtx ctx,
                                       @PathVariable("id") String id, @RequestBody PayrollRuns.ReasonInput request) {
        runs.reopen(ctx, Requests.id(id), request.reason());
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/payroll/runs/{id}/exceptions/{exception}/accept")
    public ResponseEntity<Void> accept(@RequiresModule(Modules.PAYROLL) SessionCtx ctx,
                                       @PathVariable("id") String id, @PathVariable("exception") String exception,
                                       @RequestBody PayrollRuns.ReasonInput request) {
        runs.accept(ctx, Requests.id(id), Requests.id(exception), request.reason());
        return ResponseEntity.noContent().build();
    }

    public record Accepted(int accepted) {}

    /** Accepts several exceptions together with one reason; each is its own audit event. Answers how many were accepted. */
    @PostMapping("/payroll/runs/{id}/exceptions/accept")
    public Accepted acceptMany(@RequiresModule(Modules.PAYROLL) SessionCtx ctx,
                                                      @PathVariable("id") String id, @RequestBody PayrollRuns.AcceptManyInput request) {
        java.util.List<java.util.UUID> ids = new java.util.ArrayList<>();
        for (String x : request.ids() == null ? java.util.List.<String>of() : request.ids()) {
            ids.add(Requests.id(x));
        }
        return new Accepted(runs.acceptMany(ctx, Requests.id(id), ids, request.reason()));
    }

    @PostMapping("/payroll/runs/{id}/approve")
    public ResponseEntity<Void> approve(@RequiresModule(Modules.PAYROLL) SessionCtx ctx,
                                        @PathVariable("id") String id) {
        runs.approve(ctx, Requests.id(id));
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/payroll/runs/{id}/send-back")
    public ResponseEntity<Void> sendBack(@RequiresModule(Modules.PAYROLL) SessionCtx ctx,
                                         @PathVariable("id") String id, @RequestBody PayrollRuns.ReasonInput request) {
        runs.sendBack(ctx, Requests.id(id), request.reason());
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/payroll/runs/{id}/paid")
    public ResponseEntity<Void> paid(@RequiresModule(Modules.PAYROLL) SessionCtx ctx,
                                     @PathVariable("id") String id, @RequestBody PayrollRuns.PaidInput request) {
        runs.markPaid(ctx, Requests.id(id), request);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/payroll/runs/{id}/release")
    public ResponseEntity<Void> release(@RequiresModule(Modules.PAYROLL) SessionCtx ctx,
                                        @PathVariable("id") String id) {
        runs.release(ctx, Requests.id(id));
        return ResponseEntity.noContent().build();
    }

    /** A person's payslips from approved runs, for HR to read from the employee's record. */
    @GetMapping("/payroll/employees/{employment}/payslips")
    public List<PayrollRuns.PayslipRow> payslipsOf(@RequiresModule(Modules.PAYROLL) SessionCtx ctx,
                                                              @PathVariable("employment") String employment) {
        return runs.payslipsOf(ctx, Requests.id(employment));
    }

    /** One of them in full, with its explanations; the reading is recorded in the audit. */
    @GetMapping("/payroll/employees/{employment}/payslips/{payslip}")
    public Object payslipOf(@RequiresModule(Modules.PAYROLL) SessionCtx ctx,
                            @PathVariable("employment") String employment, @PathVariable("payslip") String payslip) {
        return runs.payslipOf(ctx, Requests.id(employment), Requests.id(payslip));
    }

    @GetMapping("/payroll/runs/{id}/employees/{employment}")
    public Map<String, Object> employee(@RequiresModule(Modules.PAYROLL) SessionCtx ctx,
                                        @PathVariable("id") String id, @PathVariable("employment") String employment) {
        return runs.employee(ctx, Requests.id(id), Requests.id(employment));
    }

    @GetMapping("/payroll/runs/{id}/employees/{employment}/payslip")
    public ResponseEntity<byte[]> payslip(@RequiresModule(Modules.PAYROLL) SessionCtx ctx,
                                          @PathVariable("id") String id, @PathVariable("employment") String employment) {
        return DocumentController.file(runs.payslipPdf(ctx, Requests.id(id), Requests.id(employment)));
    }

    @GetMapping("/payroll/runs/{id}/outputs/{kind}")
    public ResponseEntity<byte[]> output(@RequiresModule(Modules.PAYROLL) SessionCtx ctx,
                                         @PathVariable("id") String id, @PathVariable("kind") String kind) {
        return DocumentController.file(runs.output(ctx, Requests.id(id), kind));
    }

    // ── an employee's pay ───────────────────────────────────────────────

    @GetMapping("/people/employees/{id}/pay")
    public Map<String, Object> pay(@RequiresModule(Modules.PAYROLL) SessionCtx ctx,
                                   @PathVariable("id") String id) {
        return pay.get(ctx, Requests.id(id));
    }

    @PostMapping("/people/employees/{id}/pay/preview")
    public Map<String, Object> preview(@RequiresModule(Modules.PAYROLL) SessionCtx ctx,
                                       @PathVariable("id") String id, @RequestBody PayrollPay.ReviseInput request) {
        return pay.preview(ctx, Requests.id(id), request);
    }

    @PostMapping("/people/employees/{id}/pay")
    public Map<String, Object> revise(@RequiresModule(Modules.PAYROLL) SessionCtx ctx,
                                      @PathVariable("id") String id, @RequestBody PayrollPay.ReviseInput request) {
        return pay.revise(ctx, Requests.id(id), request);
    }

    @PostMapping("/people/employees/{id}/pay/overrides")
    public Map<String, Object> addOverride(@RequiresModule(Modules.PAYROLL) SessionCtx ctx,
                                           @PathVariable("id") String id, @RequestBody PayrollPay.OverrideInput request) {
        return pay.addOverride(ctx, Requests.id(id), request);
    }

    @DeleteMapping("/people/employees/{id}/pay/overrides/{override}")
    public ResponseEntity<Void> removeOverride(@RequiresModule(Modules.PAYROLL) SessionCtx ctx,
                                               @PathVariable("id") String id, @PathVariable("override") String override) {
        pay.removeOverride(ctx, Requests.id(id), Requests.id(override));
        return ResponseEntity.noContent().build();
    }

    @PutMapping("/people/employees/{id}/pay/profile")
    public Map<String, Object> profile(@RequiresModule(Modules.PAYROLL) SessionCtx ctx,
                                       @PathVariable("id") String id, @RequestBody PayrollPay.ProfileInput request) {
        return pay.saveProfile(ctx, Requests.id(id), request);
    }

    // ── My pay ──────────────────────────────────────────────────────────

    @GetMapping("/me/pay")
    public Map<String, Object> myPay(@RequiresModule(Modules.PAYROLL) SessionCtx ctx) {
        return runs.myPay(ctx);
    }

    @GetMapping("/me/pay/{id}")
    public Map<String, Object> myPayslip(@RequiresModule(Modules.PAYROLL) SessionCtx ctx,
                                         @PathVariable("id") String id) {
        return runs.myPayslip(ctx, Requests.id(id));
    }

    @GetMapping("/me/pay/{id}/pdf")
    public ResponseEntity<byte[]> myPayslipPdf(@RequiresModule(Modules.PAYROLL) SessionCtx ctx,
                                               @PathVariable("id") String id) {
        return DocumentController.file(runs.myPayslipPdf(ctx, Requests.id(id)));
    }
}
