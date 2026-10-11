package com.example.loginapp.web;

import java.io.InputStream;
import java.util.List;
import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.example.loginapp.core.AuthService;
import com.example.loginapp.core.ChecklistService;
import com.example.loginapp.core.ChecklistService.NewTask;

/** Checklist tasks: a joiner's checklist for HR, and the caller's own tasks for anyone they are assigned to. */
@RestController
@RequestMapping("/api/org/people")
public class TaskController {

    public record Reason(String reason) {}

    public record Assignee(String membershipId) {}

    private final AuthService auth;
    private final ChecklistService checklists;

    public TaskController(AuthService auth, ChecklistService checklists) {
        this.auth = auth;
        this.checklists = checklists;
    }

    @GetMapping("/joiners/{id}/tasks")
    public Map<String, Object> forJoiner(@RequestHeader(value = "Authorization", required = false) String authorization,
                                         @PathVariable("id") String id) {
        return checklists.forJoiner(Requests.session(auth, authorization), Requests.id(id));
    }

    @PostMapping("/joiners/{id}/tasks")
    public Map<String, Object> add(@RequestHeader(value = "Authorization", required = false) String authorization,
                                   @PathVariable("id") String id, @RequestBody NewTask request) {
        return checklists.add(Requests.session(auth, authorization), Requests.id(id), request);
    }

    @GetMapping("/tasks/mine")
    public List<Map<String, Object>> mine(@RequestHeader(value = "Authorization", required = false) String authorization) {
        return checklists.mine(Requests.session(auth, authorization));
    }

    @PostMapping("/tasks/{id}/done")
    public Map<String, Object> done(@RequestHeader(value = "Authorization", required = false) String authorization,
                                    @PathVariable("id") String id) {
        return checklists.done(Requests.session(auth, authorization), Requests.id(id));
    }

    @PostMapping("/tasks/{id}/skip")
    public Map<String, Object> skip(@RequestHeader(value = "Authorization", required = false) String authorization,
                                    @PathVariable("id") String id, @RequestBody Reason request) {
        return checklists.skip(Requests.session(auth, authorization), Requests.id(id), request.reason());
    }

    @PostMapping("/tasks/{id}/reassign")
    public Map<String, Object> reassign(@RequestHeader(value = "Authorization", required = false) String authorization,
                                        @PathVariable("id") String id, @RequestBody Assignee request) {
        return checklists.reassign(Requests.session(auth, authorization), Requests.id(id), Requests.id(request.membershipId()));
    }

    @PostMapping("/tasks/{id}/approve")
    public Map<String, Object> approve(@RequestHeader(value = "Authorization", required = false) String authorization,
                                       @PathVariable("id") String id) {
        return checklists.approve(Requests.session(auth, authorization), Requests.id(id));
    }

    @PostMapping("/tasks/{id}/attachment")
    public Map<String, Object> attach(@RequestHeader(value = "Authorization", required = false) String authorization,
                                      @PathVariable("id") String id,
                                      @RequestParam(value = "name", required = false) String name, InputStream body) {
        var ctx = Requests.session(auth, authorization);
        return checklists.attach(ctx, Requests.id(id), name, DocumentController.read(body));
    }
}
