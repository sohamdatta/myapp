package com.example.loginapp.core;

/** Trades the one-time code the provider sent back for the ID token. A seam so tests need no network. */
public interface OidcTokenClient {

    /** Returns the {@code id_token}. Throws {@link ApiException} 503 when the provider cannot be reached, 401 when it refuses the code. */
    String idToken(OidcProvider provider, String code, String codeVerifier, String redirectUri);
}
