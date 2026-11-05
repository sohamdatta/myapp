package com.example.loginapp.web;

import java.util.List;
import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.example.loginapp.core.AuthService;
import com.example.loginapp.core.SessionCtx;
import com.example.loginapp.core.Notifications;
import com.example.loginapp.core.Notifications.MessageInput;
import com.example.loginapp.core.NumberSeries;
import com.example.loginapp.core.NumberSeries.SeriesInput;

/** The bell, the Organization's message wording, and employee code series. */
@RestController
@RequestMapping("/api/org")
public class NotificationController {

    public record Read(List<String> ids) {}

    public record Sample(String date, String location, String department, String employmentType) {}

    private final AuthService auth;
    private final Notifications notifications;
    private final NumberSeries series;

    public NotificationController(AuthService auth, Notifications notifications, NumberSeries series) {
        this.auth = auth;
        this.notifications = notifications;
        this.series = series;
    }

    @GetMapping("/notifications")
    public Map<String, Object> list(SessionCtx ctx) {
        return notifications.list(ctx);
    }

    @PostMapping("/notifications/read")
    public Map<String, Object> read(SessionCtx ctx,
                                    @RequestBody(required = false) Read request) {
        return notifications.read(ctx, request == null ? null : request.ids());
    }

    @GetMapping("/people/setup/messages")
    public List<Map<String, Object>> messages(SessionCtx ctx) {
        return notifications.messages(ctx);
    }

    @PutMapping("/people/setup/messages/{id}")
    public Map<String, Object> saveMessage(SessionCtx ctx,
                                           @PathVariable("id") String id, @RequestBody MessageInput request) {
        return notifications.saveMessage(ctx, Requests.id(id), request);
    }

    @GetMapping("/people/setup/number-series")
    public List<Map<String, Object>> series(SessionCtx ctx) {
        return series.list(ctx);
    }

    @PostMapping("/people/setup/number-series")
    public Map<String, Object> createSeries(SessionCtx ctx,
                                            @RequestBody SeriesInput request) {
        return series.save(ctx, null, request);
    }

    @PutMapping("/people/setup/number-series/{id}")
    public Map<String, Object> saveSeries(SessionCtx ctx,
                                          @PathVariable("id") String id, @RequestBody SeriesInput request) {
        return series.save(ctx, Requests.id(id), request);
    }

    @PostMapping("/people/setup/number-series/{id}/example")
    public Map<String, Object> example(SessionCtx ctx,
                                       @PathVariable("id") String id, @RequestBody(required = false) Sample request) {
        Sample s = request == null ? new Sample(null, null, null, null) : request;
        return series.example(ctx, Requests.id(id), s.date(), s.location(), s.department(),
                s.employmentType());
    }
}
