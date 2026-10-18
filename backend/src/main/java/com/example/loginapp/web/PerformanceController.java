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
import com.example.loginapp.core.Probation;

/** Performance: the employee's own (My performance), and their team's or everyone's for managers and HR. */
@RestController
@RequestMapping("/api/org")
public class PerformanceController {

    private final AuthService auth;
    private final Probation probation;

    public PerformanceController(AuthService auth, Probation probation) {
        this.auth = auth;
        this.probation = probation;
    }

    // ── the employee's own ──────────────────────────────────────────────

    @GetMapping("/me/performance")
    public Map<String, Object> mine(@RequestHeader(value = "Authorization", required = false) String authorization) {
        return probation.mine(Requests.session(auth, authorization));
    }

    @PostMapping("/me/performance/probation/checkins/{id}/reply")
    public Map<String, Object> reply(@RequestHeader(value = "Authorization", required = false) String authorization,
                                     @PathVariable String id, @RequestBody Probation.ReplyInput request) {
        return probation.reply(Requests.session(auth, authorization), Requests.id(id), request);
    }

    // ── probation ───────────────────────────────────────────────────────

    @GetMapping("/performance/probation")
    public List<Map<String, Object>> probations(@RequestHeader(value = "Authorization", required = false) String authorization,
                                                @RequestParam(required = false) String status) {
        return probation.list(Requests.session(auth, authorization), status);
    }

    @GetMapping("/performance/probation/{id}")
    public Map<String, Object> probation(@RequestHeader(value = "Authorization", required = false) String authorization,
                                         @PathVariable String id) {
        return probation.get(Requests.session(auth, authorization), Requests.id(id));
    }

    @PutMapping("/performance/probation/{id}/checkins/{checkinId}")
    public Map<String, Object> checkin(@RequestHeader(value = "Authorization", required = false) String authorization,
                                       @PathVariable String id, @PathVariable String checkinId, @RequestBody Probation.CheckinInput request) {
        return probation.writeCheckin(Requests.session(auth, authorization), Requests.id(id), Requests.id(checkinId), request);
    }

    @PostMapping("/performance/probation/{id}/recommend")
    public Map<String, Object> recommend(@RequestHeader(value = "Authorization", required = false) String authorization,
                                         @PathVariable String id, @RequestBody Probation.RecommendInput request) {
        return probation.recommend(Requests.session(auth, authorization), Requests.id(id), request);
    }

    @PostMapping("/performance/probation/{id}/decide")
    public Map<String, Object> decide(@RequestHeader(value = "Authorization", required = false) String authorization,
                                      @PathVariable String id, @RequestBody Probation.DecideInput request) {
        return probation.decide(Requests.session(auth, authorization), Requests.id(id), request);
    }

    // ── setup ───────────────────────────────────────────────────────────

    @GetMapping("/performance/setup/probation")
    public Map<String, Object> probationSettings(@RequestHeader(value = "Authorization", required = false) String authorization) {
        return probation.getSettings(Requests.session(auth, authorization));
    }

    @PutMapping("/performance/setup/probation")
    public Map<String, Object> saveProbationSettings(@RequestHeader(value = "Authorization", required = false) String authorization,
                                                     @RequestBody Probation.SettingsInput request) {
        return probation.saveSettings(Requests.session(auth, authorization), request);
    }
}
