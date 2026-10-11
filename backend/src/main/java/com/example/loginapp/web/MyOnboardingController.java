package com.example.loginapp.web;

import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.example.loginapp.core.AuthService;
import com.example.loginapp.core.JoinerService;

/** The joiner's own onboarding: the notice, their sections, and sending each for checking. */
@RestController
@RequestMapping("/api/org/onboarding/me")
public class MyOnboardingController {

    public record Accept(String noticeId) {}

    private final AuthService auth;
    private final JoinerService joiners;

    public MyOnboardingController(AuthService auth, JoinerService joiners) {
        this.auth = auth;
        this.joiners = joiners;
    }

    @GetMapping
    public Map<String, Object> mine(@RequestHeader(value = "Authorization", required = false) String authorization) {
        return joiners.mine(Requests.session(auth, authorization));
    }

    @PostMapping("/notice")
    public Map<String, Object> notice(@RequestHeader(value = "Authorization", required = false) String authorization,
                                      @RequestBody Accept request) {
        return joiners.acceptNotice(Requests.session(auth, authorization), Requests.id(request.noticeId()));
    }

    @GetMapping("/sections/{section}")
    public Map<String, Object> section(@RequestHeader(value = "Authorization", required = false) String authorization,
                                       @PathVariable("section") String section) {
        return joiners.mySection(Requests.session(auth, authorization), section);
    }

    @PutMapping("/sections/{section}")
    public Map<String, Object> save(@RequestHeader(value = "Authorization", required = false) String authorization,
                                    @PathVariable("section") String section, @RequestBody Map<String, Object> values) {
        return joiners.saveMySection(Requests.session(auth, authorization), section, values);
    }

    @PostMapping("/sections/{section}/submit")
    public Map<String, Object> submit(@RequestHeader(value = "Authorization", required = false) String authorization,
                                      @PathVariable("section") String section) {
        return joiners.submitMySection(Requests.session(auth, authorization), section);
    }
}
