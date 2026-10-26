package com.example.loginapp.core;

import java.time.Clock;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Records errors the browser caught, so a page that crashes is heard of before a customer says so.
 *
 * <p>Anyone can send one, signed in or not, so it is limited and cleaned: a few per minute per address,
 * a ceiling for everyone together, every field cut to a short length, and email addresses, ids and long
 * numbers blanked out of the message. No user, no Organization, no stack and no address is stored.
 */
public final class ClientErrors {

    /** What the browser says; every part is cleaned before it is kept. */
    public record Report(String page, String region, String message, String build) {}

    public static final int PER_ADDRESS_PER_MINUTE = 10;
    public static final int EVERYONE_PER_MINUTE = 300;
    static final int MESSAGE_LIMIT = 300;
    private static final long WINDOW_MS = 60_000;
    private static final int KEEP_DAYS = 90;
    private static final int MAX_ADDRESSES_TRACKED = 10_000;

    private static final Pattern ID_TEXT = Pattern.compile("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");
    private static final Pattern EMAIL_TEXT = Pattern.compile("[^\\s@<>\"']+@[^\\s@<>\"']+\\.[A-Za-z]{2,}");
    private static final Pattern PAN_TEXT = Pattern.compile("\\b[A-Za-z]{5}\\d{4}[A-Za-z]\\b");
    private static final Pattern LONG_NUMBER = Pattern.compile("\\d{6,}");
    private static final Pattern TOKEN_TEXT = Pattern.compile("[A-Za-z0-9_-]{32,}");
    private static final Pattern NAME = Pattern.compile("[a-z0-9_-]{1,40}");
    private static final Pattern BUILD = Pattern.compile("[A-Za-z0-9._-]{1,40}");

    private final Db db;
    private final Clock clock;
    private final Map<String, Deque<Long>> byAddress = new ConcurrentHashMap<>();
    private final Deque<Long> everyone = new ArrayDeque<>();
    private static final Logger LOG = LoggerFactory.getLogger(ClientErrors.class);
    private final AtomicLong dropped = new AtomicLong();
    private volatile long lastDropWarning;
    private volatile long lastCleanup;

    public ClientErrors(Db db) {
        this(db, Clock.systemUTC());
    }

    public ClientErrors(Db db, Clock clock) {
        this.db = db;
        this.clock = clock;
    }

    /** Keeps the report, or drops it quietly when this address (or everyone) is sending too many. Returns whether it was kept. */
    public boolean record(String address, Report report) {
        if (report == null) {
            return false;
        }
        if (!allow(address == null ? "unknown" : address)) {
            noteDropped();
            return false;
        }
        String message = clean(report.message());
        if (message.isEmpty()) {
            return false;
        }
        String page = name(report.page());
        String region = name(report.region());
        String build = report.build() != null && BUILD.matcher(report.build()).matches() ? report.build() : null;
        db.inAuth(c -> {
            Sql.update(c, "insert into auth.client_error (page, region, message, build) values (?, ?, ?, ?)", page, region, message, build);
            long now = clock.millis();
            if (now - lastCleanup > 3_600_000) {   // old rows go at most once an hour, by whoever reports next
                lastCleanup = now;
                Sql.update(c, "delete from auth.client_error where occurred_at < now() - make_interval(days => ?)", KEEP_DAYS);
            }
            return null;
        });
        return true;
    }

    /** A flood hides real reports, so say so in the log, at most once a minute, with how many were turned away. */
    private void noteDropped() {
        long total = dropped.incrementAndGet();
        long now = clock.millis();
        if (now - lastDropWarning > WINDOW_MS) {
            lastDropWarning = now;
            LOG.warn("Client error reports turned away by the rate limit: {} so far", total);
        }
    }

    private synchronized boolean allow(String address) {
        long now = clock.millis();
        if (byAddress.size() > MAX_ADDRESSES_TRACKED) {
            byAddress.values().removeIf(times -> times.isEmpty() || now - times.peekLast() > WINDOW_MS);
            if (byAddress.size() > MAX_ADDRESSES_TRACKED) {
                byAddress.clear();   // under attack from many addresses: forget them rather than grow
            }
        }
        while (!everyone.isEmpty() && now - everyone.peekFirst() > WINDOW_MS) {
            everyone.pollFirst();
        }
        Deque<Long> times = byAddress.computeIfAbsent(address, a -> new ArrayDeque<>());
        while (!times.isEmpty() && now - times.peekFirst() > WINDOW_MS) {
            times.pollFirst();
        }
        if (times.size() >= PER_ADDRESS_PER_MINUTE || everyone.size() >= EVERYONE_PER_MINUTE) {
            return false;
        }
        times.addLast(now);
        everyone.addLast(now);
        return true;
    }

    static String clean(String message) {
        if (message == null) {
            return "";
        }
        // Cut first: the cleaning below should not run over a megabyte someone chose to send.
        String text = (message.length() > 2_000 ? message.substring(0, 2_000) : message).replaceAll("[\\p{Cntrl}]", " ").strip();
        text = EMAIL_TEXT.matcher(text).replaceAll("[email]");
        text = ID_TEXT.matcher(text).replaceAll("[id]");
        text = TOKEN_TEXT.matcher(text).replaceAll("[token]");
        text = PAN_TEXT.matcher(text).replaceAll("[pan]");
        text = LONG_NUMBER.matcher(text).replaceAll("[number]");
        return text.length() > MESSAGE_LIMIT ? text.substring(0, MESSAGE_LIMIT) : text;
    }

    private static String name(String value) {
        return value != null && NAME.matcher(value).matches() ? value : "unknown";
    }
}
