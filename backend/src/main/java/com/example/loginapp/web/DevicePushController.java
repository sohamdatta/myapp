package com.example.loginapp.web;

import java.io.IOException;
import java.io.InputStream;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.example.loginapp.core.ApiException;
import com.example.loginapp.core.DeviceService;

import jakarta.servlet.http.HttpServletRequest;

/**
 * Where biometric devices send punches: the eSSL / ZKTeco "ADMS" push protocol, which lives at
 * /iclock on the server the device is pointed at. Devices sign in with nothing but a registered
 * serial number, so this answers in the device's own plain-text dialect and nothing else.
 *
 * <p>The query string is read by hand: a device may label its body as a form, and asking the
 * servlet for parameters would then swallow the punches.
 */
@RestController
@RequestMapping("/iclock")
public class DevicePushController {

    private static final int MAX_BODY = 2 * 1024 * 1024;

    private final DeviceService devices;
    private final boolean trustForwardedFor;

    public DevicePushController(DeviceService devices, @Value("${app.trust-forwarded-for:false}") boolean trustForwardedFor) {
        this.devices = devices;
        this.trustForwardedFor = trustForwardedFor;
    }

    /** The device starts up and asks for its settings. */
    @GetMapping("/cdata")
    public ResponseEntity<byte[]> handshake(HttpServletRequest http) {
        Map<String, String> q = query(http);
        return text(devices.handshake(q.get("sn"), Requests.clientIp(http, trustForwardedFor)));
    }

    /** The device posts part of its log. */
    @PostMapping("/cdata")
    public ResponseEntity<byte[]> upload(HttpServletRequest http, InputStream body) throws IOException {
        Map<String, String> q = query(http);
        byte[] raw = body.readNBytes(MAX_BODY + 1);
        if (raw.length > MAX_BODY) {
            throw new ApiException(413, "device.too_large", "Send at most 2 MB at a time");
        }
        return text(devices.receive(q.get("sn"), q.get("table"), q.get("stamp"), new String(raw, StandardCharsets.UTF_8),
                Requests.clientIp(http, trustForwardedFor)));
    }

    /** The device asks for commands; there are none. */
    @GetMapping("/getrequest")
    public ResponseEntity<byte[]> commands(HttpServletRequest http) {
        return text(devices.commands(query(http).get("sn"), Requests.clientIp(http, trustForwardedFor)));
    }

    /** The device reports on commands it ran; there were none to report on. */
    @PostMapping("/devicecmd")
    public ResponseEntity<byte[]> commandResult(HttpServletRequest http) {
        return text(devices.commands(query(http).get("sn"), Requests.clientIp(http, trustForwardedFor)));
    }

    /** The query string with lower-case names: devices write SN, table and Stamp. */
    static Map<String, String> query(HttpServletRequest http) {
        Map<String, String> out = new HashMap<>();
        String qs = http.getQueryString();
        if (qs == null) {
            return out;
        }
        for (String kv : qs.split("&")) {
            String[] p = kv.split("=", 2);
            try {
                out.put(URLDecoder.decode(p[0], StandardCharsets.UTF_8).toLowerCase(java.util.Locale.ROOT),
                        p.length > 1 ? URLDecoder.decode(p[1], StandardCharsets.UTF_8) : "");
            } catch (IllegalArgumentException e) {
                throw ApiException.badRequest("The address could not be read");
            }
        }
        return out;
    }

    private static ResponseEntity<byte[]> text(String body) {
        return ResponseEntity.ok().header(HttpHeaders.CONTENT_TYPE, "text/plain; charset=utf-8").body(body.getBytes(StandardCharsets.UTF_8));
    }
}
