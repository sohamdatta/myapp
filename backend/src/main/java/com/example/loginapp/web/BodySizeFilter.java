package com.example.loginapp.web;

import java.io.IOException;

import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Refuses a request whose declared body is larger than anything the application accepts (the biggest is a 5 million
 * character import), before the body is read. JSON bodies are otherwise read whole into memory before their own
 * size checks run, so one oversized request could take a large share of the heap.
 */
@Component
public class BodySizeFilter extends OncePerRequestFilter {

    static final long MAX_BYTES = 16L * 1024 * 1024;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (request.getContentLengthLong() > MAX_BYTES) {
            response.setStatus(413);
            response.setContentType("application/json");
            response.getWriter().write("{\"code\":\"request.too_large\",\"message\":\"The request is too large\"}");
            return;
        }
        chain.doFilter(request, response);
    }
}
