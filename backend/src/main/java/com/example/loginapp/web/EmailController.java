package com.example.loginapp.web;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.example.loginapp.core.AuthService;
import com.example.loginapp.core.MailOutbox;
import com.example.loginapp.core.MailSender;

/** The platform console's Email page: how mail is doing, what failed, who is not mailed. */
@RestController
@RequestMapping("/api/platform/email")
public class EmailController {

    public record SuppressRequest(String email, String reason) {}

    public record TestRequest(String to) {}

    private final AuthService auth;
    private final MailOutbox outbox;
    private final MailSender sender;

    public EmailController(AuthService auth, MailOutbox outbox, java.util.Optional<MailSender> sender) {
        this.auth = auth;
        this.outbox = outbox;
        this.sender = sender.orElse(null);
    }

    @GetMapping
    public MailOutbox.Overview overview(@RequestHeader(value = "Authorization", required = false) String authorization) {
        return outbox.overview(Requests.session(auth, authorization), sender);
    }

    @PostMapping("/{id}/retry")
    public ResponseEntity<Void> retry(@RequestHeader(value = "Authorization", required = false) String authorization,
                                      @PathVariable("id") long id) {
        outbox.retry(Requests.session(auth, authorization), id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/suppressions")
    public ResponseEntity<Void> suppress(@RequestHeader(value = "Authorization", required = false) String authorization,
                                         @RequestBody SuppressRequest request) {
        outbox.suppress(Requests.session(auth, authorization), request.email(), request.reason());
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/suppressions")
    public ResponseEntity<Void> unsuppress(@RequestHeader(value = "Authorization", required = false) String authorization,
                                           @RequestParam("email") String email) {
        outbox.unsuppress(Requests.session(auth, authorization), email);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/test")
    public ResponseEntity<Void> test(@RequestHeader(value = "Authorization", required = false) String authorization,
                                     @RequestBody TestRequest request) {
        outbox.sendTest(Requests.session(auth, authorization), request.to());
        return ResponseEntity.noContent().build();
    }
}
