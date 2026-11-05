package com.example.loginapp.web;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Which build is running: the release number, the short commit and the build time. Open to signed-out
 * pages, so a deployment can be checked before anyone signs in. It holds nothing private.
 */
@RestController
@RequestMapping("/api/version")
public class VersionController {

    /** What the server reports. */
    public record Version(String version, String commit, String built) {
    }

    private final Version current;

    public VersionController(
            @Value("${app.version:dev}") String version,
            @Value("${app.commit:dev}") String commit,
            @Value("${app.built:}") String built) {
        this.current = new Version(version, commit, built);
    }

    @GetMapping
    public Version version() {
        return current;
    }
}
