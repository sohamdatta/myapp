package com.example.loginapp.web;

import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import com.example.loginapp.core.ApiException;

/** Turns service errors into {code, message} JSON with the right HTTP status. */
@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<Map<String, String>> handle(ApiException e) {
        Map<String, String> body = new LinkedHashMap<>();
        body.put("code", e.code());
        body.put("message", e.getMessage());
        return ResponseEntity.status(e.status()).body(body);
    }

    /** A body that is not JSON, or a value of the wrong type: a plain 400 the screens can show, no parser details. */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Map<String, String>> unreadable(HttpMessageNotReadableException e) {
        for (Throwable cause = e; cause != null; cause = cause.getCause()) {
            if (cause instanceof BodySizeFilter.TooLarge) {
                return tooLarge((BodySizeFilter.TooLarge) cause);
            }
        }
        return handle(ApiException.badRequest("The request could not be read"));
    }

    /** A body sent in chunks that passed the size limit while being read (see {@link BodySizeFilter}). */
    @ExceptionHandler(BodySizeFilter.TooLarge.class)
    public ResponseEntity<Map<String, String>> tooLarge(BodySizeFilter.TooLarge e) {
        return handle(new ApiException(413, "request.too_large", "The request is too large"));
    }
}
