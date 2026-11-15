package com.example.loginapp.core;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** The token endpoint call, with the JDK's own HTTP client: a form POST with the client secret and the PKCE verifier. */
public final class JdkOidcTokenClient implements OidcTokenClient {

    private static final Logger LOG = LoggerFactory.getLogger(JdkOidcTokenClient.class);

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    @Override
    public String idToken(OidcProvider provider, String code, String codeVerifier, String redirectUri) {
        Map<String, String> form = new LinkedHashMap<>();
        form.put("grant_type", "authorization_code");
        form.put("code", code);
        form.put("redirect_uri", redirectUri);
        form.put("client_id", provider.clientId());
        form.put("client_secret", provider.clientSecret());
        form.put("code_verifier", codeVerifier);
        String body = form.entrySet().stream()
                .map(e -> URLEncoder.encode(e.getKey(), StandardCharsets.UTF_8) + "=" + URLEncoder.encode(e.getValue(), StandardCharsets.UTF_8))
                .collect(Collectors.joining("&"));
        HttpRequest request = HttpRequest.newBuilder(URI.create(provider.tokenUrl())).timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/x-www-form-urlencoded").header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body)).build();
        HttpResponse<String> response;
        try {
            response = http.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            LOG.warn("Sign-in with {}: token endpoint not reachable: {}", provider.name(), e.toString());
            throw unavailable();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw unavailable();
        }
        if (response.statusCode() >= 500) {
            LOG.warn("Sign-in with {}: token endpoint answered {}", provider.name(), response.statusCode());
            throw unavailable();
        }
        if (response.statusCode() != 200) {
            LOG.info("Sign-in with {}: token endpoint refused the code ({})", provider.name(), response.statusCode());
            throw new ApiException(401, "oidc.invalid", "That sign-in could not be verified. Try again");
        }
        Object parsed;
        try {
            parsed = Json.parse(response.body());
        } catch (RuntimeException e) {
            throw unavailable();
        }
        if (parsed instanceof Map<?, ?> map && map.get("id_token") instanceof String token && !token.isBlank()) {
            return token;
        }
        throw new ApiException(401, "oidc.invalid", "That sign-in could not be verified. Try again");
    }

    private static ApiException unavailable() {
        return new ApiException(503, "oidc.unavailable", "Sign-in with that provider is not available right now. Try again shortly");
    }
}
