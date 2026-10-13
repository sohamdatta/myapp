package com.example.loginapp.web;

import java.util.List;
import java.util.Map;

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
import com.example.loginapp.core.ExitService;

/** Exits: the employee's own (My exit), and everyone's for HR and managers (People → Exits). */
@RestController
@RequestMapping("/api/org")
public class ExitController {

    private final AuthService auth;
    private final ExitService exits;

    public ExitController(AuthService auth, ExitService exits) {
        this.auth = auth;
        this.exits = exits;
    }

    // ── the employee's own ──────────────────────────────────────────────

    @GetMapping("/me/exit")
    public Map<String, Object> mine(@RequestHeader(value = "Authorization", required = false) String authorization) {
        return exits.mine(Requests.session(auth, authorization));
    }

    @PostMapping("/me/exit")
    public Map<String, Object> resign(@RequestHeader(value = "Authorization", required = false) String authorization,
                                      @RequestBody ExitService.ResignInput request) {
        return exits.resign(Requests.session(auth, authorization), request);
    }

    @PostMapping("/me/exit/withdraw")
    public Map<String, Object> withdraw(@RequestHeader(value = "Authorization", required = false) String authorization,
                                        @RequestBody(required = false) ExitService.WithdrawInput request) {
        return exits.withdraw(Requests.session(auth, authorization), request);
    }

    // ── HR and managers ─────────────────────────────────────────────────

    @GetMapping("/people/exits")
    public List<Map<String, Object>> list(@RequestHeader(value = "Authorization", required = false) String authorization,
                                          @RequestParam(value = "status", required = false) String status) {
        return exits.list(Requests.session(auth, authorization), status);
    }

    @PostMapping("/people/exits")
    public Map<String, Object> start(@RequestHeader(value = "Authorization", required = false) String authorization,
                                     @RequestBody ExitService.StartInput request) {
        return exits.start(Requests.session(auth, authorization), request);
    }

    @GetMapping("/people/exits/{id}")
    public Map<String, Object> get(@RequestHeader(value = "Authorization", required = false) String authorization,
                                   @PathVariable("id") String id) {
        return exits.get(Requests.session(auth, authorization), Requests.id(id));
    }

    @PostMapping("/people/exits/{id}/recommend")
    public Map<String, Object> recommend(@RequestHeader(value = "Authorization", required = false) String authorization,
                                         @PathVariable("id") String id, @RequestBody ExitService.RecommendInput request) {
        return exits.recommend(Requests.session(auth, authorization), Requests.id(id), request);
    }

    @PostMapping("/people/exits/{id}/accept")
    public Map<String, Object> accept(@RequestHeader(value = "Authorization", required = false) String authorization,
                                      @PathVariable("id") String id, @RequestBody ExitService.AcceptInput request) {
        return exits.accept(Requests.session(auth, authorization), Requests.id(id), request);
    }

    @PostMapping("/people/exits/{id}/withdrawal")
    public Map<String, Object> decideWithdrawal(@RequestHeader(value = "Authorization", required = false) String authorization,
                                                @PathVariable("id") String id, @RequestBody ExitService.DecisionInput request) {
        return exits.decideWithdrawal(Requests.session(auth, authorization), Requests.id(id), request);
    }

    @PostMapping("/people/exits/{id}/cancel")
    public Map<String, Object> cancel(@RequestHeader(value = "Authorization", required = false) String authorization,
                                      @PathVariable("id") String id, @RequestBody ExitService.WithdrawInput request) {
        return exits.cancel(Requests.session(auth, authorization), Requests.id(id), request);
    }

    @PostMapping("/people/exits/{id}/close")
    public Map<String, Object> close(@RequestHeader(value = "Authorization", required = false) String authorization,
                                     @PathVariable("id") String id, @RequestBody ExitService.CloseInput request) {
        return exits.close(Requests.session(auth, authorization), Requests.id(id), request);
    }

    // ── setup ───────────────────────────────────────────────────────────

    @GetMapping("/people/setup/exit")
    public Map<String, Object> settings(@RequestHeader(value = "Authorization", required = false) String authorization) {
        return exits.getSettings(Requests.session(auth, authorization));
    }

    @PutMapping("/people/setup/exit")
    public Map<String, Object> saveSettings(@RequestHeader(value = "Authorization", required = false) String authorization,
                                            @RequestBody ExitService.SettingsInput request) {
        return exits.saveSettings(Requests.session(auth, authorization), request);
    }
}
