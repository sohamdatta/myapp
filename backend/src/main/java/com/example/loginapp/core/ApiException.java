package com.example.loginapp.core;

/** An error the API reports to the caller: an HTTP status, a stable code and a readable message. */
public class ApiException extends RuntimeException {

    private final int status;
    private final String code;

    public ApiException(int status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    public int status() {
        return status;
    }

    public String code() {
        return code;
    }

    public static ApiException badRequest(String message) {
        return new ApiException(400, "request.invalid", message);
    }

    public static ApiException notFound(String what) {
        return new ApiException(404, "not_found", what + " was not found");
    }

    public static ApiException denied(String permission) {
        return new ApiException(403, "permission.denied", "You need the " + permission + " permission to do this");
    }

    public static ApiException notSignedIn() {
        return new ApiException(401, "auth.session_revoked", "Sign in to continue");
    }
}
