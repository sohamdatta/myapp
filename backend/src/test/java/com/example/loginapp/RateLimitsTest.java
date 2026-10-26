package com.example.loginapp;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.Test;

import com.example.loginapp.core.RateLimits;
import com.example.loginapp.core.RateLimits.Rule;

class RateLimitsTest {

    private final AtomicLong now = new AtomicLong(Instant.parse("2026-11-02T10:00:00Z").toEpochMilli());
    private final Clock clock = new Clock() {
        @Override
        public ZoneId getZone() {
            return ZoneId.of("UTC");
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return Instant.ofEpochMilli(now.get());
        }
    };
    private final Rule rule = new Rule("test", 3, Duration.ofMinutes(10));

    @Test
    void anEventIsAllowedUntilTheLimitIsReachedThenAgainWhenTheWindowPasses() {
        RateLimits limits = new RateLimits(clock);
        for (int i = 0; i < 3; i++) {
            assertTrue(limits.allowed(rule, "a@x.test"), "event " + i);
            limits.hit(rule, "a@x.test");
        }
        assertFalse(limits.allowed(rule, "a@x.test"));
        now.addAndGet(Duration.ofMinutes(9).toMillis());
        assertFalse(limits.allowed(rule, "a@x.test"), "still inside the window");
        now.addAndGet(Duration.ofMinutes(2).toMillis());
        assertTrue(limits.allowed(rule, "a@x.test"));
    }

    @Test
    void keysAreIndependentAndCaseDoesNotMatter() {
        RateLimits limits = new RateLimits(clock);
        for (int i = 0; i < 3; i++) {
            limits.hit(rule, "Asha@X.test ");
        }
        assertFalse(limits.allowed(rule, "asha@x.test"));
        assertTrue(limits.allowed(rule, "someone-else@x.test"));
        assertTrue(limits.allowed(new Rule("other", 3, Duration.ofMinutes(10)), "asha@x.test"), "another rule counts separately");
    }

    @Test
    void tryHitCountsOnlyWhatItAllows() {
        RateLimits limits = new RateLimits(clock);
        assertTrue(limits.tryHit(rule, "10.0.0.1"));
        assertTrue(limits.tryHit(rule, "10.0.0.1"));
        assertTrue(limits.tryHit(rule, "10.0.0.1"));
        assertFalse(limits.tryHit(rule, "10.0.0.1"));
        assertFalse(limits.tryHit(rule, "10.0.0.1"));
        now.addAndGet(Duration.ofMinutes(11).toMillis());
        assertTrue(limits.tryHit(rule, "10.0.0.1"), "refused tries did not extend the wait");
    }

    @Test
    void clearForgetsAKeyAndTheCallerCanChooseTheLimitAndWindow() {
        RateLimits limits = new RateLimits(clock);
        limits.hit(rule, "k");
        limits.hit(rule, "k");
        assertFalse(limits.allowed(rule, "k", 2, Duration.ofMinutes(10)));
        assertTrue(limits.allowed(rule, "k", 3, Duration.ofMinutes(10)));
        now.addAndGet(Duration.ofMinutes(5).toMillis());
        assertTrue(limits.allowed(rule, "k", 2, Duration.ofMinutes(4)), "a shorter window sees fewer events");
        limits.clear(rule, "k");
        assertTrue(limits.allowed(rule, "k", 1, Duration.ofMinutes(10)));
    }

    @Test
    void memoryIsBoundedAndTheBusyKeyIsKept() {
        RateLimits limits = new RateLimits(clock);
        for (int i = 0; i < 3; i++) {
            limits.hit(rule, "target");
        }
        for (int i = 0; i < 25_000; i++) {
            limits.hit(rule, "noise-" + i);
            if (i % 1000 == 0) {
                limits.allowed(rule, "target");   // still being hit, so still recent
            }
        }
        assertFalse(limits.allowed(rule, "target"), "flooding other keys must not forget one that is in use");
    }
}
