package com.example.loginapp.web;

import java.util.List;
import java.util.Map;

import org.springframework.web.bind.annotation.DeleteMapping;
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
import com.example.loginapp.core.ExitInterview;
import com.example.loginapp.core.ExitLetters;
import com.example.loginapp.core.ExitService;
import com.example.loginapp.core.ExitSettlement;
import com.example.loginapp.core.ExitTasks;

/** Exits: the employee's own (My exit), and everyone's for HR and managers (People → Exits). */
@RestController
@RequestMapping("/api/org")
public class ExitController {

    public record Skip(String reason) {}

    public record Reassign(String membershipId) {}

    public record IssueLetter(String templateId) {}

    private final AuthService auth;
    private final ExitService exits;
    private final ExitTasks tasks;
    private final ExitSettlement settlement;
    private final ExitLetters letters;
    private final ExitInterview interview;

    public ExitController(AuthService auth, ExitService exits, ExitTasks tasks, ExitSettlement settlement, ExitLetters letters,
                          ExitInterview interview) {
        this.auth = auth;
        this.exits = exits;
        this.tasks = tasks;
        this.settlement = settlement;
        this.letters = letters;
        this.interview = interview;
    }

    // ── the employee's own ──────────────────────────────────────────────

    @GetMapping("/me/exit")
    public Map<String, Object> mine(SessionCtx ctx) {
        return exits.mine(ctx);
    }

    @PostMapping("/me/exit")
    public Map<String, Object> resign(SessionCtx ctx,
                                      @RequestBody ExitService.ResignInput request) {
        return exits.resign(ctx, request);
    }

    @PostMapping("/me/exit/withdraw")
    public Map<String, Object> withdraw(SessionCtx ctx,
                                        @RequestBody(required = false) ExitService.WithdrawInput request) {
        return exits.withdraw(ctx, request);
    }

    @PostMapping("/me/exit/interview")
    public Map<String, Object> answer(SessionCtx ctx,
                                      @RequestBody ExitInterview.AnswersInput request) {
        return interview.answer(ctx, request);
    }

    @PostMapping("/me/exit/interview/decline")
    public Map<String, Object> decline(SessionCtx ctx) {
        return interview.decline(ctx);
    }

    // ── HR and managers ─────────────────────────────────────────────────

    @GetMapping("/people/exits")
    public List<Map<String, Object>> list(SessionCtx ctx,
                                          @RequestParam(value = "status", required = false) String status) {
        return exits.list(ctx, status);
    }

    @PostMapping("/people/exits")
    public Map<String, Object> start(SessionCtx ctx,
                                     @RequestBody ExitService.StartInput request) {
        return exits.start(ctx, request);
    }

    @GetMapping("/people/exits/{id}")
    public Map<String, Object> get(SessionCtx ctx,
                                   @PathVariable("id") String id) {
        return exits.get(ctx, Requests.id(id));
    }

    @PostMapping("/people/exits/{id}/recommend")
    public Map<String, Object> recommend(SessionCtx ctx,
                                         @PathVariable("id") String id, @RequestBody ExitService.RecommendInput request) {
        return exits.recommend(ctx, Requests.id(id), request);
    }

    @PostMapping("/people/exits/{id}/accept")
    public Map<String, Object> accept(SessionCtx ctx,
                                      @PathVariable("id") String id, @RequestBody ExitService.AcceptInput request) {
        return exits.accept(ctx, Requests.id(id), request);
    }

    @PostMapping("/people/exits/{id}/withdrawal")
    public Map<String, Object> decideWithdrawal(SessionCtx ctx,
                                                @PathVariable("id") String id, @RequestBody ExitService.DecisionInput request) {
        return exits.decideWithdrawal(ctx, Requests.id(id), request);
    }

    @PostMapping("/people/exits/{id}/cancel")
    public Map<String, Object> cancel(SessionCtx ctx,
                                      @PathVariable("id") String id, @RequestBody ExitService.WithdrawInput request) {
        return exits.cancel(ctx, Requests.id(id), request);
    }

    @PostMapping("/people/exits/{id}/close")
    public Map<String, Object> close(SessionCtx ctx,
                                     @PathVariable("id") String id, @RequestBody ExitService.CloseInput request) {
        return exits.close(ctx, Requests.id(id), request);
    }

    @PutMapping("/people/exits/{id}/legal-hold")
    public Map<String, Object> legalHold(SessionCtx ctx,
                                         @PathVariable("id") String id, @RequestBody ExitService.HoldInput request) {
        return exits.legalHold(ctx, Requests.id(id), request);
    }

    // ── clearance ───────────────────────────────────────────────────────

    @GetMapping("/people/exit-tasks/mine")
    public List<Map<String, Object>> myTasks(SessionCtx ctx) {
        return tasks.mine(ctx);
    }

    @PostMapping("/people/exits/{id}/tasks")
    public Map<String, Object> addTask(SessionCtx ctx,
                                       @PathVariable("id") String id, @RequestBody ExitTasks.NewTask request) {
        return tasks.add(ctx, Requests.id(id), request);
    }

    @PostMapping("/people/exit-tasks/{id}/done")
    public Map<String, Object> done(SessionCtx ctx,
                                    @PathVariable("id") String id, @RequestBody(required = false) ExitTasks.DoneInput request) {
        return tasks.done(ctx, Requests.id(id), request);
    }

    @PostMapping("/people/exit-tasks/{id}/skip")
    public Map<String, Object> skip(SessionCtx ctx,
                                    @PathVariable("id") String id, @RequestBody Skip request) {
        return tasks.skip(ctx, Requests.id(id), request.reason());
    }

    @PostMapping("/people/exit-tasks/{id}/reassign")
    public Map<String, Object> reassign(SessionCtx ctx,
                                        @PathVariable("id") String id, @RequestBody Reassign request) {
        return tasks.reassign(ctx, Requests.id(id), Requests.id(request.membershipId()));
    }

    // ── the full and final settlement ───────────────────────────────────

    @GetMapping("/people/exits/{id}/settlement")
    public Map<String, Object> settlement(SessionCtx ctx,
                                          @PathVariable("id") String id) {
        return settlement.get(ctx, Requests.id(id));
    }

    @PostMapping("/people/exits/{id}/settlement/prepare")
    public Map<String, Object> prepare(SessionCtx ctx,
                                       @PathVariable("id") String id) {
        return settlement.prepare(ctx, Requests.id(id));
    }

    @PostMapping("/people/exits/{id}/settlement/lines")
    public Map<String, Object> addLine(SessionCtx ctx,
                                       @PathVariable("id") String id, @RequestBody ExitSettlement.LineInput request) {
        return settlement.addLine(ctx, Requests.id(id), request);
    }

    @PutMapping("/people/exits/{id}/settlement/lines/{lineId}")
    public Map<String, Object> changeLine(SessionCtx ctx,
                                          @PathVariable("id") String id, @PathVariable("lineId") String lineId,
                                          @RequestBody ExitSettlement.LineInput request) {
        return settlement.changeLine(ctx, Requests.id(id), Requests.id(lineId), request);
    }

    @DeleteMapping("/people/exits/{id}/settlement/lines/{lineId}")
    public Map<String, Object> removeLine(SessionCtx ctx,
                                          @PathVariable("id") String id, @PathVariable("lineId") String lineId) {
        return settlement.removeLine(ctx, Requests.id(id), Requests.id(lineId));
    }

    @PostMapping("/people/exits/{id}/settlement/approve")
    public Map<String, Object> approve(SessionCtx ctx,
                                       @PathVariable("id") String id) {
        return settlement.approve(ctx, Requests.id(id));
    }

    @PostMapping("/people/exits/{id}/settlement/lines/{lineId}/paid")
    public Map<String, Object> paid(SessionCtx ctx,
                                    @PathVariable("id") String id, @PathVariable("lineId") String lineId,
                                    @RequestBody ExitSettlement.PaidInput request) {
        return settlement.paid(ctx, Requests.id(id), Requests.id(lineId), request);
    }

    // ── letters and the interview ───────────────────────────────────────

    @GetMapping("/people/exits/{id}/letters/preview")
    public Map<String, Object> previewLetter(SessionCtx ctx,
                                             @PathVariable("id") String id, @RequestParam("template") String template) {
        return letters.preview(ctx, Requests.id(id), Requests.id(template));
    }

    @PostMapping("/people/exits/{id}/letters")
    public Map<String, Object> issueLetter(SessionCtx ctx,
                                           @PathVariable("id") String id, @RequestBody IssueLetter request) {
        return letters.issue(ctx, Requests.id(id), Requests.id(request.templateId()));
    }

    @GetMapping("/people/exits/{id}/interview")
    public Map<String, Object> interview(SessionCtx ctx,
                                         @PathVariable("id") String id) {
        return interview.get(ctx, Requests.id(id));
    }

    @PostMapping("/people/exits/{id}/interview")
    public Map<String, Object> sendInterview(SessionCtx ctx,
                                             @PathVariable("id") String id) {
        return interview.send(ctx, Requests.id(id));
    }

    @PutMapping("/people/exits/{id}/interview/notes")
    public Map<String, Object> interviewNotes(SessionCtx ctx,
                                              @PathVariable("id") String id, @RequestBody ExitInterview.NotesInput request) {
        return interview.notes(ctx, Requests.id(id), request);
    }

    // ── setup ───────────────────────────────────────────────────────────

    @GetMapping("/people/setup/exit")
    public Map<String, Object> settings(SessionCtx ctx) {
        return exits.getSettings(ctx);
    }

    @PutMapping("/people/setup/exit")
    public Map<String, Object> saveSettings(SessionCtx ctx,
                                            @RequestBody ExitService.SettingsInput request) {
        return exits.saveSettings(ctx, request);
    }
}
