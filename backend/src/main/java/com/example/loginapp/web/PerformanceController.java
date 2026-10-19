package com.example.loginapp.web;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.web.bind.annotation.DeleteMapping;
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
import com.example.loginapp.core.Calibration;
import com.example.loginapp.core.Feedback;
import com.example.loginapp.core.Goals;
import com.example.loginapp.core.OneToOnes;
import com.example.loginapp.core.Outcomes;
import com.example.loginapp.core.PerformanceSetup;
import com.example.loginapp.core.PerformanceTeam;
import com.example.loginapp.core.Plans;
import com.example.loginapp.core.Probation;
import com.example.loginapp.core.Reviews;
import com.example.loginapp.core.SessionCtx;

/** Performance: the employee's own (My performance), and their team's or everyone's for managers and HR. */
@RestController
@RequestMapping("/api/org")
public class PerformanceController {

    private final AuthService auth;
    private final Probation probation;
    private final Goals goals;
    private final OneToOnes oneToOnes;
    private final Feedback feedback;
    private final PerformanceSetup setup;
    private final PerformanceTeam team;
    private final Reviews reviews;
    private final Calibration calibration;
    private final Outcomes outcomes;
    private final Plans plans;

    public PerformanceController(AuthService auth, Probation probation, Goals goals, OneToOnes oneToOnes, Feedback feedback,
                                 PerformanceSetup setup, PerformanceTeam team, Reviews reviews, Calibration calibration, Outcomes outcomes,
                                 Plans plans) {
        this.plans = plans;
        this.reviews = reviews;
        this.calibration = calibration;
        this.outcomes = outcomes;
        this.auth = auth;
        this.probation = probation;
        this.goals = goals;
        this.oneToOnes = oneToOnes;
        this.feedback = feedback;
        this.setup = setup;
        this.team = team;
    }

    private SessionCtx session(String authorization) {
        return Requests.session(auth, authorization);
    }

    // ── the employee's own ──────────────────────────────────────────────

    /** Everything on My performance: probation, this period's goals, feedback received and one-to-ones. */
    @GetMapping("/me/performance")
    public Map<String, Object> mine(@RequestHeader(value = "Authorization", required = false) String authorization) {
        SessionCtx ctx = session(authorization);
        Map<String, Object> out = new LinkedHashMap<>(probation.mine(ctx));
        out.put("goals", goals.mine(ctx));
        out.put("feedback", feedback.received(ctx));
        out.put("oneToOnes", oneToOnes.people(ctx));
        out.put("reviews", reviews.mine(ctx));
        out.put("outcomeLetters", outcomes.myLetters(ctx));
        out.put("plan", plans.mine(ctx));
        return out;
    }

    @PostMapping("/me/performance/probation/checkins/{id}/reply")
    public Map<String, Object> reply(@RequestHeader(value = "Authorization", required = false) String authorization,
                                     @PathVariable String id, @RequestBody Probation.ReplyInput request) {
        return probation.reply(session(authorization), Requests.id(id), request);
    }

    @PostMapping("/me/performance/goals/submit")
    public Map<String, Object> submitGoals(@RequestHeader(value = "Authorization", required = false) String authorization,
                                           @RequestBody Goals.SetInput request) {
        return goals.submit(session(authorization), request);
    }

    @GetMapping("/me/performance/feedback/given")
    public List<Map<String, Object>> given(@RequestHeader(value = "Authorization", required = false) String authorization) {
        return feedback.given(session(authorization));
    }

    // ── probation ───────────────────────────────────────────────────────

    @GetMapping("/performance/probation")
    public List<Map<String, Object>> probations(@RequestHeader(value = "Authorization", required = false) String authorization,
                                                @RequestParam(required = false) String status) {
        return probation.list(session(authorization), status);
    }

    @GetMapping("/performance/probation/{id}")
    public Map<String, Object> probation(@RequestHeader(value = "Authorization", required = false) String authorization,
                                         @PathVariable String id) {
        return probation.get(session(authorization), Requests.id(id));
    }

    @PutMapping("/performance/probation/{id}/checkins/{checkinId}")
    public Map<String, Object> checkin(@RequestHeader(value = "Authorization", required = false) String authorization,
                                       @PathVariable String id, @PathVariable String checkinId, @RequestBody Probation.CheckinInput request) {
        return probation.writeCheckin(session(authorization), Requests.id(id), Requests.id(checkinId), request);
    }

    @PostMapping("/performance/probation/{id}/recommend")
    public Map<String, Object> recommend(@RequestHeader(value = "Authorization", required = false) String authorization,
                                         @PathVariable String id, @RequestBody Probation.RecommendInput request) {
        return probation.recommend(session(authorization), Requests.id(id), request);
    }

    @PostMapping("/performance/probation/{id}/decide")
    public Map<String, Object> decide(@RequestHeader(value = "Authorization", required = false) String authorization,
                                      @PathVariable String id, @RequestBody Probation.DecideInput request) {
        return probation.decide(session(authorization), Requests.id(id), request);
    }

    // ── team ────────────────────────────────────────────────────────────

    @GetMapping("/performance/team")
    public Map<String, Object> team(@RequestHeader(value = "Authorization", required = false) String authorization) {
        return team.team(session(authorization));
    }

    // ── goals ───────────────────────────────────────────────────────────

    @GetMapping("/performance/goals")
    public Map<String, Object> goals(@RequestHeader(value = "Authorization", required = false) String authorization,
                                     @RequestParam String employmentId, @RequestParam(required = false) String period) {
        return goals.view(session(authorization), Requests.id(employmentId), period);
    }

    @PostMapping("/performance/goals")
    public Map<String, Object> addGoal(@RequestHeader(value = "Authorization", required = false) String authorization,
                                       @RequestBody Goals.GoalInput request) {
        return goals.create(session(authorization), request);
    }

    @PutMapping("/performance/goals/{id}")
    public Map<String, Object> changeGoal(@RequestHeader(value = "Authorization", required = false) String authorization,
                                          @PathVariable String id, @RequestBody Goals.GoalInput request) {
        return goals.update(session(authorization), Requests.id(id), request);
    }

    @DeleteMapping("/performance/goals/{id}")
    public Map<String, Object> removeGoal(@RequestHeader(value = "Authorization", required = false) String authorization,
                                          @PathVariable String id) {
        return goals.delete(session(authorization), Requests.id(id));
    }

    @PostMapping("/performance/goals/{id}/drop")
    public Map<String, Object> dropGoal(@RequestHeader(value = "Authorization", required = false) String authorization,
                                        @PathVariable String id, @RequestBody Goals.ReasonInput request) {
        return goals.drop(session(authorization), Requests.id(id), request);
    }

    @PostMapping("/performance/goals/{id}/checkins")
    public Map<String, Object> goalCheckin(@RequestHeader(value = "Authorization", required = false) String authorization,
                                           @PathVariable String id, @RequestBody Goals.CheckinInput request) {
        return goals.checkin(session(authorization), Requests.id(id), request);
    }

    @PostMapping("/performance/goals/agree")
    public Map<String, Object> agreeGoals(@RequestHeader(value = "Authorization", required = false) String authorization,
                                          @RequestBody Goals.SetInput request) {
        return goals.agree(session(authorization), request);
    }

    @PostMapping("/performance/goals/return")
    public Map<String, Object> returnGoals(@RequestHeader(value = "Authorization", required = false) String authorization,
                                           @RequestBody Goals.SetInput request) {
        return goals.returnSet(session(authorization), request);
    }

    @GetMapping("/performance/department-goals")
    public List<Map<String, Object>> departmentGoals(@RequestHeader(value = "Authorization", required = false) String authorization,
                                                     @RequestParam(required = false) String period) {
        return goals.departmentGoals(session(authorization), period);
    }

    // ── one-to-ones ─────────────────────────────────────────────────────

    @GetMapping("/performance/one-to-ones")
    public Map<String, Object> oneToOnes(@RequestHeader(value = "Authorization", required = false) String authorization,
                                         @RequestParam String with) {
        return oneToOnes.with(session(authorization), Requests.id(with));
    }

    @PostMapping("/performance/one-to-ones")
    public Map<String, Object> addOneToOne(@RequestHeader(value = "Authorization", required = false) String authorization,
                                           @RequestBody OneToOnes.MeetingInput request) {
        return oneToOnes.create(session(authorization), request);
    }

    @PutMapping("/performance/one-to-ones/{id}")
    public Map<String, Object> changeOneToOne(@RequestHeader(value = "Authorization", required = false) String authorization,
                                              @PathVariable String id, @RequestBody OneToOnes.MeetingInput request) {
        return oneToOnes.update(session(authorization), Requests.id(id), request);
    }

    @PostMapping("/performance/one-to-ones/{id}/items")
    public Map<String, Object> addItem(@RequestHeader(value = "Authorization", required = false) String authorization,
                                       @PathVariable String id, @RequestBody OneToOnes.ItemInput request) {
        return oneToOnes.addItem(session(authorization), Requests.id(id), request);
    }

    @PutMapping("/performance/one-to-one-items/{id}")
    public Map<String, Object> markItem(@RequestHeader(value = "Authorization", required = false) String authorization,
                                        @PathVariable String id, @RequestBody OneToOnes.DoneInput request) {
        return oneToOnes.markItem(session(authorization), Requests.id(id), request);
    }

    // ── feedback ────────────────────────────────────────────────────────

    @GetMapping("/performance/feedback")
    public List<Map<String, Object>> feedbackAbout(@RequestHeader(value = "Authorization", required = false) String authorization,
                                                   @RequestParam String employmentId) {
        return feedback.about(session(authorization), Requests.id(employmentId));
    }

    @PostMapping("/performance/feedback")
    public Map<String, Object> giveFeedback(@RequestHeader(value = "Authorization", required = false) String authorization,
                                            @RequestBody Feedback.GiveInput request) {
        return feedback.give(session(authorization), request);
    }

    @PutMapping("/performance/feedback/{id}")
    public Map<String, Object> editFeedback(@RequestHeader(value = "Authorization", required = false) String authorization,
                                            @PathVariable String id, @RequestBody Feedback.TextInput request) {
        return feedback.edit(session(authorization), Requests.id(id), request);
    }

    @PostMapping("/performance/feedback/{id}/withdraw")
    public Map<String, Object> withdrawFeedback(@RequestHeader(value = "Authorization", required = false) String authorization,
                                                @PathVariable String id) {
        return feedback.withdraw(session(authorization), Requests.id(id));
    }

    @GetMapping("/performance/colleagues")
    public List<Map<String, Object>> colleagues(@RequestHeader(value = "Authorization", required = false) String authorization) {
        return feedback.colleagues(session(authorization));
    }

    // ── review cycles and reviews ───────────────────────────────────────

    @GetMapping("/performance/cycles")
    public List<Map<String, Object>> cycles(@RequestHeader(value = "Authorization", required = false) String authorization) {
        return reviews.cycles(session(authorization));
    }

    @GetMapping("/performance/cycles/{id}")
    public Map<String, Object> cycle(@RequestHeader(value = "Authorization", required = false) String authorization, @PathVariable String id) {
        return reviews.cycle(session(authorization), Requests.id(id));
    }

    @PostMapping("/performance/cycles")
    public Map<String, Object> addCycle(@RequestHeader(value = "Authorization", required = false) String authorization,
                                        @RequestBody Reviews.CycleInput request) {
        return reviews.createCycle(session(authorization), request);
    }

    @PutMapping("/performance/cycles/{id}")
    public Map<String, Object> changeCycle(@RequestHeader(value = "Authorization", required = false) String authorization,
                                           @PathVariable String id, @RequestBody Reviews.CycleInput request) {
        return reviews.updateCycle(session(authorization), Requests.id(id), request);
    }

    @PostMapping("/performance/cycles/{id}/{action}")
    public Map<String, Object> cycleAction(@RequestHeader(value = "Authorization", required = false) String authorization,
                                           @PathVariable String id, @PathVariable String action,
                                           @RequestBody(required = false) Reviews.PeopleInput request) {
        SessionCtx ctx = session(authorization);
        return switch (action) {
            case "start" -> reviews.startCycle(ctx, Requests.id(id));
            case "people" -> reviews.addPeople(ctx, Requests.id(id), request);
            case "calibrate" -> reviews.startCalibration(ctx, Requests.id(id));
            case "release" -> reviews.release(ctx, Requests.id(id));
            case "close" -> reviews.closeCycle(ctx, Requests.id(id));
            default -> throw com.example.loginapp.core.ApiException.notFound("That action");
        };
    }

    // ── improvement plans ───────────────────────────────────────────────

    @GetMapping("/performance/plans")
    public List<Map<String, Object>> plans(@RequestHeader(value = "Authorization", required = false) String authorization,
                                           @RequestParam(value = "status", required = false) String status) {
        return plans.list(session(authorization), status);
    }

    @PostMapping("/performance/plans")
    public Map<String, Object> createPlan(@RequestHeader(value = "Authorization", required = false) String authorization,
                                          @RequestBody Plans.PlanInput request) {
        return plans.create(session(authorization), request);
    }

    @GetMapping("/performance/plans/{id}")
    public Map<String, Object> plan(@RequestHeader(value = "Authorization", required = false) String authorization, @PathVariable String id) {
        return plans.get(session(authorization), Requests.id(id));
    }

    @PutMapping("/performance/plans/{id}")
    public Map<String, Object> updatePlan(@RequestHeader(value = "Authorization", required = false) String authorization, @PathVariable String id,
                                          @RequestBody Plans.PlanInput request) {
        return plans.update(session(authorization), Requests.id(id), request);
    }

    @PostMapping("/performance/plans/{id}/start")
    public Map<String, Object> startPlan(@RequestHeader(value = "Authorization", required = false) String authorization, @PathVariable String id,
                                         @RequestBody(required = false) Plans.StartInput request) {
        return plans.start(session(authorization), Requests.id(id), request);
    }

    @PostMapping("/performance/plans/{id}/decide")
    public Map<String, Object> decidePlan(@RequestHeader(value = "Authorization", required = false) String authorization, @PathVariable String id,
                                          @RequestBody Plans.DecideInput request) {
        return plans.decide(session(authorization), Requests.id(id), request);
    }

    @PutMapping("/performance/plans/{id}/checkins/{checkinId}")
    public Map<String, Object> planCheckin(@RequestHeader(value = "Authorization", required = false) String authorization,
                                           @PathVariable String id, @PathVariable String checkinId, @RequestBody Plans.CheckinInput request) {
        return plans.checkin(session(authorization), Requests.id(id), Requests.id(checkinId), request);
    }

    @PostMapping("/me/performance/plan/{id}/acknowledge")
    public Map<String, Object> acknowledgePlan(@RequestHeader(value = "Authorization", required = false) String authorization,
                                               @PathVariable String id, @RequestBody(required = false) Plans.TextInput request) {
        return plans.acknowledge(session(authorization), Requests.id(id), request);
    }

    @PostMapping("/me/performance/plan/checkins/{id}/reply")
    public Map<String, Object> replyToPlanCheckin(@RequestHeader(value = "Authorization", required = false) String authorization,
                                                  @PathVariable String id, @RequestBody Plans.TextInput request) {
        return plans.reply(session(authorization), Requests.id(id), request);
    }

    // ── calibration ─────────────────────────────────────────────────────

    @GetMapping("/performance/cycles/{id}/calibration")
    public Map<String, Object> calibration(@RequestHeader(value = "Authorization", required = false) String authorization, @PathVariable String id) {
        return calibration.overview(session(authorization), Requests.id(id));
    }

    @PostMapping("/performance/cycles/{id}/calibration/sessions")
    public Map<String, Object> openSession(@RequestHeader(value = "Authorization", required = false) String authorization, @PathVariable String id,
                                           @RequestBody Calibration.SessionInput request) {
        return calibration.createSession(session(authorization), Requests.id(id), request);
    }

    @GetMapping("/performance/calibration/{id}")
    public Map<String, Object> calibrationSession(@RequestHeader(value = "Authorization", required = false) String authorization,
                                                  @PathVariable String id) {
        return calibration.session(session(authorization), Requests.id(id));
    }

    @PostMapping("/performance/calibration/{id}/{action}")
    public Map<String, Object> sessionAction(@RequestHeader(value = "Authorization", required = false) String authorization,
                                             @PathVariable String id, @PathVariable String action,
                                             @RequestBody(required = false) Calibration.NotesInput request) {
        SessionCtx ctx = session(authorization);
        return switch (action) {
            case "refresh" -> calibration.refresh(ctx, Requests.id(id));
            case "notes" -> calibration.saveNotes(ctx, Requests.id(id), request);
            case "close" -> calibration.close(ctx, Requests.id(id));
            default -> throw com.example.loginapp.core.ApiException.notFound("That action");
        };
    }

    @PostMapping("/performance/calibration/{id}/reviews/{reviewId}")
    public Map<String, Object> calibrate(@RequestHeader(value = "Authorization", required = false) String authorization, @PathVariable String id,
                                         @PathVariable String reviewId, @RequestBody Calibration.ChangeInput request) {
        return calibration.change(session(authorization), Requests.id(id), Requests.id(reviewId), request);
    }

    @GetMapping("/performance/merit")
    public Map<String, Object> merit(@RequestHeader(value = "Authorization", required = false) String authorization) {
        return calibration.settings(session(authorization));
    }

    @PutMapping("/performance/merit")
    public Map<String, Object> saveMerit(@RequestHeader(value = "Authorization", required = false) String authorization,
                                         @RequestBody Calibration.SettingsInput request) {
        return calibration.saveSettings(session(authorization), request);
    }

    // ── outcomes ────────────────────────────────────────────────────────

    @GetMapping("/performance/cycles/{id}/outcomes")
    public Map<String, Object> outcomes(@RequestHeader(value = "Authorization", required = false) String authorization, @PathVariable String id) {
        return outcomes.list(session(authorization), Requests.id(id));
    }

    @PostMapping("/performance/cycles/{id}/outcomes/{action}")
    public Map<String, Object> outcomesAction(@RequestHeader(value = "Authorization", required = false) String authorization,
                                              @PathVariable String id, @PathVariable String action,
                                              @RequestBody(required = false) Outcomes.BatchInput request) {
        SessionCtx ctx = session(authorization);
        return switch (action) {
            case "prepare" -> outcomes.prepare(ctx, Requests.id(id));
            case "submit" -> outcomes.submit(ctx, Requests.id(id), request);
            case "return" -> outcomes.sendBack(ctx, Requests.id(id), request);
            case "approve" -> outcomes.approve(ctx, Requests.id(id), request);
            default -> throw com.example.loginapp.core.ApiException.notFound("That action");
        };
    }

    @PutMapping("/performance/outcomes/{id}")
    public Map<String, Object> updateOutcome(@RequestHeader(value = "Authorization", required = false) String authorization,
                                             @PathVariable String id, @RequestBody Outcomes.OutcomeInput request) {
        return outcomes.update(session(authorization), Requests.id(id), request);
    }

    @GetMapping("/performance/reviews/to-write")
    public List<Map<String, Object>> reviewsToWrite(@RequestHeader(value = "Authorization", required = false) String authorization) {
        return reviews.toWrite(session(authorization));
    }

    @GetMapping("/me/performance/reviews")
    public List<Map<String, Object>> myReviews(@RequestHeader(value = "Authorization", required = false) String authorization) {
        return reviews.mine(session(authorization));
    }

    @GetMapping("/performance/reviews/{id}")
    public Map<String, Object> review(@RequestHeader(value = "Authorization", required = false) String authorization, @PathVariable String id) {
        return reviews.review(session(authorization), Requests.id(id));
    }

    @PutMapping("/performance/reviews/{id}/entries/{role}")
    public Map<String, Object> saveEntry(@RequestHeader(value = "Authorization", required = false) String authorization,
                                         @PathVariable String id, @PathVariable String role, @RequestBody Reviews.EntryInput request) {
        return reviews.saveEntry(session(authorization), Requests.id(id), role, request);
    }

    @PostMapping("/performance/reviews/{id}/return-self")
    public Map<String, Object> returnSelf(@RequestHeader(value = "Authorization", required = false) String authorization,
                                          @PathVariable String id, @RequestBody Reviews.ReasonInput request) {
        return reviews.returnSelf(session(authorization), Requests.id(id), request);
    }

    @PostMapping("/performance/reviews/{id}/acknowledge")
    public Map<String, Object> acknowledge(@RequestHeader(value = "Authorization", required = false) String authorization,
                                           @PathVariable String id, @RequestBody(required = false) Reviews.AckInput request) {
        return reviews.acknowledge(session(authorization), Requests.id(id), request);
    }

    @PostMapping("/performance/reviews/{id}/withdraw")
    public Map<String, Object> withdrawReview(@RequestHeader(value = "Authorization", required = false) String authorization,
                                              @PathVariable String id, @RequestBody Reviews.ReasonInput request) {
        return reviews.withdraw(session(authorization), Requests.id(id), request);
    }

    @PostMapping("/performance/reviews/{id}/move-on")
    public Map<String, Object> moveOn(@RequestHeader(value = "Authorization", required = false) String authorization,
                                      @PathVariable String id, @RequestBody Reviews.ReasonInput request) {
        return reviews.moveOn(session(authorization), Requests.id(id), request);
    }

    @GetMapping("/performance/setup/forms")
    public List<Map<String, Object>> forms(@RequestHeader(value = "Authorization", required = false) String authorization) {
        return reviews.forms(session(authorization));
    }

    @PostMapping("/performance/setup/forms")
    public List<Map<String, Object>> addForm(@RequestHeader(value = "Authorization", required = false) String authorization,
                                             @RequestBody Reviews.FormInput request) {
        return reviews.saveForm(session(authorization), null, request);
    }

    @PutMapping("/performance/setup/forms/{id}")
    public List<Map<String, Object>> changeForm(@RequestHeader(value = "Authorization", required = false) String authorization,
                                                @PathVariable String id, @RequestBody Reviews.FormInput request) {
        return reviews.saveForm(session(authorization), Requests.id(id), request);
    }

    // ── setup ───────────────────────────────────────────────────────────

    @GetMapping("/performance/setup")
    public Map<String, Object> setup(@RequestHeader(value = "Authorization", required = false) String authorization) {
        return setup.get(session(authorization));
    }

    @PutMapping("/performance/setup/general")
    public Map<String, Object> saveGeneral(@RequestHeader(value = "Authorization", required = false) String authorization,
                                           @RequestBody PerformanceSetup.GeneralInput request) {
        return setup.saveGeneral(session(authorization), request);
    }

    @PutMapping("/performance/setup/scale")
    public Map<String, Object> saveScale(@RequestHeader(value = "Authorization", required = false) String authorization,
                                         @RequestBody PerformanceSetup.ScaleInput request) {
        return setup.saveScale(session(authorization), request);
    }

    @PostMapping("/performance/setup/competencies")
    public Map<String, Object> addCompetency(@RequestHeader(value = "Authorization", required = false) String authorization,
                                             @RequestBody PerformanceSetup.CompetencyInput request) {
        return setup.saveCompetency(session(authorization), null, request);
    }

    @PutMapping("/performance/setup/competencies/{id}")
    public Map<String, Object> changeCompetency(@RequestHeader(value = "Authorization", required = false) String authorization,
                                                @PathVariable String id, @RequestBody PerformanceSetup.CompetencyInput request) {
        return setup.saveCompetency(session(authorization), Requests.id(id), request);
    }

    @PostMapping("/performance/setup/competency-sets")
    public Map<String, Object> addSet(@RequestHeader(value = "Authorization", required = false) String authorization,
                                      @RequestBody PerformanceSetup.SetInput request) {
        return setup.saveSet(session(authorization), null, request);
    }

    @PutMapping("/performance/setup/competency-sets/{id}")
    public Map<String, Object> changeSet(@RequestHeader(value = "Authorization", required = false) String authorization,
                                         @PathVariable String id, @RequestBody PerformanceSetup.SetInput request) {
        return setup.saveSet(session(authorization), Requests.id(id), request);
    }

    @PutMapping("/performance/setup/competency-sets/order")
    public Map<String, Object> orderSets(@RequestHeader(value = "Authorization", required = false) String authorization,
                                         @RequestBody PerformanceSetup.OrderInput request) {
        return setup.orderSets(session(authorization), request);
    }

    @GetMapping("/performance/setup/probation")
    public Map<String, Object> probationSettings(@RequestHeader(value = "Authorization", required = false) String authorization) {
        return probation.getSettings(session(authorization));
    }

    @PutMapping("/performance/setup/probation")
    public Map<String, Object> saveProbationSettings(@RequestHeader(value = "Authorization", required = false) String authorization,
                                                     @RequestBody Probation.SettingsInput request) {
        return probation.saveSettings(session(authorization), request);
    }
}
