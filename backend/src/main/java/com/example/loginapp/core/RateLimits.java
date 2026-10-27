package com.example.loginapp.core;

import java.time.Clock;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Sliding-window limits on how often something may be tried: sign-in, accepting an invitation, and (later) password reset
 * and one-time codes. Each {@link Rule} counts events per key (an email address, a client address) in a window.
 *
 * <p>Not used for the per-email lock after wrong passwords: that lives in the database ({@code auth.login_failure}), the
 * same for an address with an account and one without, so it survives restarts and cannot tell the two apart.
 *
 * <p>Held in memory, so the counts are per application instance and start again when it restarts. That is acceptable
 * while one instance runs (the compose files run one); before a second is added the counts must move to the database,
 * or a guesser gets the limit once per instance. Memory is bounded: past {@link #MAX_KEYS} the least recently used
 * keys are dropped, which an attacker with endless distinct keys could use to forget a quiet one, but not one still
 * being hit.
 *
 * <p>Callers check {@link #allowed} before doing the work, then {@link #hit} what should count (failures for sign-in)
 * and {@link #clear} on success.
 */
public final class RateLimits {

    public record Rule(String name, int max, Duration window) {}

    /** Wrong passwords from one client address, over all the emails it tried. */
    public static final Rule LOGIN_FAILURES_PER_ADDRESS = new Rule("login-address", 100, Duration.ofMinutes(15));
    /** Asks to change the sign-in address, per login. */
    public static final Rule EMAIL_CHANGE_PER_LOGIN = new Rule("email-change-login", 5, Duration.ofHours(1));
    /** Wrong or used-up links for changing or undoing a change of address, per client address. */
    public static final Rule EMAIL_LINK_FAILURES_PER_ADDRESS = new Rule("email-link-address", 20, Duration.ofMinutes(15));
    /** Every attempt to accept an invitation from one client address. */
    /** Requests to send a reset link, per client address. */
    public static final Rule FORGOT_PER_ADDRESS = new Rule("forgot-address", 10, Duration.ofMinutes(15));
    /** Requests for one email address; past it the request is accepted and quietly ignored, so nothing is revealed. */
    public static final Rule FORGOT_PER_EMAIL = new Rule("forgot-email", 5, Duration.ofHours(1));
    /** Reset links that did not work, per client address: someone guessing tokens. */
    public static final Rule RESET_FAILURES_PER_ADDRESS = new Rule("reset-address", 20, Duration.ofMinutes(15));
    /** Wrong two-step codes, per client address. */
    public static final Rule MFA_FAILURES_PER_ADDRESS = new Rule("mfa-address", 30, Duration.ofMinutes(15));
    public static final Rule INVITE_ATTEMPTS_PER_ADDRESS = new Rule("invite-address", 30, Duration.ofMinutes(15));
    /** Looking up what an invitation link is for, from one client address. */
    public static final Rule INVITE_PREVIEWS_PER_ADDRESS = new Rule("invite-preview-address", 120, Duration.ofMinutes(5));

    static final int MAX_KEYS = 20_000;
    private static final Duration KEEP = Duration.ofDays(1);

    private final Clock clock;
    private final Map<String, Deque<Long>> events = new LinkedHashMap<>(256, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Deque<Long>> eldest) {
            return size() > MAX_KEYS;
        }
    };

    public RateLimits() {
        this(Clock.systemUTC());
    }

    public RateLimits(Clock clock) {
        this.clock = clock;
    }

    /** Whether one more event for this key is still within the rule. Counts nothing. */
    public synchronized boolean allowed(Rule rule, String key) {
        return allowed(rule, key, rule.max(), rule.window());
    }

    /** As {@link #allowed(Rule, String)} with a limit and window chosen by the caller. */
    public synchronized boolean allowed(Rule rule, String key, int max, Duration window) {
        long cutoff = clock.millis() - window.toMillis();
        return recent(rule, key, false).stream().filter(t -> t > cutoff).count() < max;
    }

    /** Counts one event for this key. */
    public synchronized void hit(Rule rule, String key) {
        recent(rule, key, true).addLast(clock.millis());
    }

    /** Counts one event if the rule still allows it; returns whether it did. */
    public synchronized boolean tryHit(Rule rule, String key) {
        Deque<Long> times = recent(rule, key, true);
        long cutoff = clock.millis() - rule.window().toMillis();
        if (times.stream().filter(t -> t > cutoff).count() >= rule.max()) {
            return false;
        }
        times.addLast(clock.millis());
        return true;
    }

    /** Forgets this key's events, such as after a successful sign-in. */
    public synchronized void clear(Rule rule, String key) {
        events.remove(rule.name() + "|" + normalise(key));
    }

    private Deque<Long> recent(Rule rule, String key, boolean create) {
        String k = rule.name() + "|" + normalise(key);
        Deque<Long> times = create ? events.computeIfAbsent(k, x -> new ArrayDeque<>()) : events.get(k);
        if (times == null) {
            return new ArrayDeque<>();
        }
        long cutoff = clock.millis() - KEEP.toMillis();   // events older than a day count for nothing under any rule
        while (!times.isEmpty() && times.peekFirst() <= cutoff) {
            times.pollFirst();
        }
        if (times.isEmpty() && !create) {
            events.remove(k);
        }
        return times;
    }

    private static String normalise(String key) {
        String k = key == null ? "" : key.trim().toLowerCase(Locale.ROOT);
        return k.indexOf(':') >= 0 && k.matches("[0-9a-f:.]+") ? ipv6Network(k) : k;
    }

    /** One IPv6 customer holds at least a /64, billions of addresses, so the first 64 bits are the key. */
    static String ipv6Network(String literal) {
        try {
            byte[] bytes = java.net.InetAddress.getByName(literal).getAddress();   // a literal: no name lookup happens
            if (bytes.length != 16) {
                return literal;   // an IPv4 address written the IPv6 way arrives as 4 bytes and is its own key
            }
            StringBuilder sb = new StringBuilder("v6/");
            for (int i = 0; i < 8; i++) {
                sb.append(String.format("%02x", bytes[i]));
            }
            return sb.toString();
        } catch (java.net.UnknownHostException e) {
            return literal;
        }
    }
}
