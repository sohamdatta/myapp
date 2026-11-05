package com.example.loginapp;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

import com.example.loginapp.web.VersionController;

/** The build number the app reports: exactly what the deployment set, and 'dev' when nothing was set. */
class VersionControllerTest {

    @Test
    void reportsWhatTheDeploymentSet() {
        var v = new VersionController("0.1.0", "abc1234", "2026-11-06T10:00Z").version();
        assertEquals("0.1.0", v.version());
        assertEquals("abc1234", v.commit());
        assertEquals("2026-11-06T10:00Z", v.built());
    }

    @Test
    void saysDevWhenRunLocally() {
        var v = new VersionController("dev", "dev", "").version();
        assertEquals("dev", v.version());
        assertEquals("", v.built());
    }
}
