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
import com.example.loginapp.core.JoinerService;
import com.example.loginapp.core.JoinerService.NewJoiner;

/** HR's side of onboarding: joiners, their sections, verification, invitation, activation and withdrawal. */
@RestController
@RequestMapping("/api/org/people/joiners")
public class JoinerController {

    public record Note(String note) {}

    public record Reason(String reason) {}

    private final AuthService auth;
    private final JoinerService joiners;

    public JoinerController(AuthService auth, JoinerService joiners) {
        this.auth = auth;
        this.joiners = joiners;
    }

    @GetMapping
    public Map<String, Object> list(@RequestHeader(value = "Authorization", required = false) String authorization,
                                    @RequestParam(value = "status", required = false) String status) {
        return joiners.list(Requests.session(auth, authorization), status);
    }

    @PostMapping
    public Map<String, Object> add(@RequestHeader(value = "Authorization", required = false) String authorization,
                                   @RequestBody NewJoiner request) {
        return joiners.add(Requests.session(auth, authorization), request);
    }

    @GetMapping("/{id}")
    public Map<String, Object> get(@RequestHeader(value = "Authorization", required = false) String authorization,
                                   @PathVariable("id") String id) {
        return joiners.get(Requests.session(auth, authorization), Requests.id(id));
    }

    @GetMapping("/{id}/sections/{section}")
    public Map<String, Object> section(@RequestHeader(value = "Authorization", required = false) String authorization,
                                       @PathVariable("id") String id, @PathVariable("section") String section) {
        return joiners.section(Requests.session(auth, authorization), Requests.id(id), section);
    }

    @PutMapping("/{id}/sections/{section}")
    public Map<String, Object> saveSection(@RequestHeader(value = "Authorization", required = false) String authorization,
                                           @PathVariable("id") String id, @PathVariable("section") String section,
                                           @RequestBody Map<String, Object> values) {
        return joiners.saveSection(Requests.session(auth, authorization), Requests.id(id), section, values);
    }

    @PostMapping("/{id}/sections/{section}/submit")
    public Map<String, Object> submit(@RequestHeader(value = "Authorization", required = false) String authorization,
                                      @PathVariable("id") String id, @PathVariable("section") String section) {
        return joiners.submitSection(Requests.session(auth, authorization), Requests.id(id), section);
    }

    @PostMapping("/{id}/sections/{section}/verify")
    public Map<String, Object> verify(@RequestHeader(value = "Authorization", required = false) String authorization,
                                      @PathVariable("id") String id, @PathVariable("section") String section,
                                      @RequestBody(required = false) Note request) {
        return joiners.verify(Requests.session(auth, authorization), Requests.id(id), section, request == null ? null : request.note());
    }

    @PostMapping("/{id}/sections/{section}/return")
    public Map<String, Object> returnSection(@RequestHeader(value = "Authorization", required = false) String authorization,
                                             @PathVariable("id") String id, @PathVariable("section") String section,
                                             @RequestBody Note request) {
        return joiners.returnSection(Requests.session(auth, authorization), Requests.id(id), section, request.note());
    }

    @PostMapping("/{id}/invite")
    public Map<String, Object> invite(@RequestHeader(value = "Authorization", required = false) String authorization,
                                      @PathVariable("id") String id) {
        return joiners.invite(Requests.session(auth, authorization), Requests.id(id));
    }

    @PostMapping("/{id}/activate")
    public Map<String, Object> activate(@RequestHeader(value = "Authorization", required = false) String authorization,
                                        @PathVariable("id") String id) {
        return joiners.activate(Requests.session(auth, authorization), Requests.id(id));
    }

    @PostMapping("/{id}/withdraw")
    public Map<String, Object> withdraw(@RequestHeader(value = "Authorization", required = false) String authorization,
                                        @PathVariable("id") String id, @RequestBody Reason request) {
        joiners.withdraw(Requests.session(auth, authorization), Requests.id(id), request.reason());
        return Map.of("withdrawn", true);
    }

    @GetMapping("/{id}/history")
    public List<Map<String, Object>> history(@RequestHeader(value = "Authorization", required = false) String authorization,
                                             @PathVariable("id") String id) {
        return joiners.history(Requests.session(auth, authorization), Requests.id(id));
    }
}
