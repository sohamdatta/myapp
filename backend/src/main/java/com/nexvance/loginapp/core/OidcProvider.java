package com.nexvance.loginapp.core;

/**
 * One sign-in provider as configured for this installation. A provider whose client id is empty is off: it is not offered
 * and its endpoints refuse.
 */
public record OidcProvider(String name, String clientId, String clientSecret, String authorizeUrl, String tokenUrl) {

    public static OidcProvider google(String clientId, String clientSecret) {
        return new OidcProvider("google", clientId, clientSecret, "https://accounts.google.com/o/oauth2/v2/auth",
                "https://oauth2.googleapis.com/token");
    }

    public boolean enabled() {
        return clientId != null && !clientId.isBlank() && clientSecret != null && !clientSecret.isBlank();
    }
}
