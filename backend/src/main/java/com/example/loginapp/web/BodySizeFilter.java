package com.example.loginapp.web;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;

import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Refuses a request whose declared body is larger than anything the application accepts (the biggest is a 5 million
 * character import), before the body is read; a body that declares no length (sent in chunks) is refused once that
 * much of it has been read. JSON bodies are otherwise read whole into memory before their own
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
        // A body sent in chunks declares no length, so it is counted as it is read and cut off at the same limit.
        chain.doFilter(request.getContentLengthLong() < 0 ? new Limited(request) : request, response);
    }

    /** Thrown while reading a body that turns out larger than {@link #MAX_BYTES}. */
    static final class TooLarge extends IOException {
        TooLarge() {
            super("The request is too large");
        }
    }

    /** The request, with a body that refuses to be read past {@link #MAX_BYTES}. */
    private static final class Limited extends HttpServletRequestWrapper {

        private ServletInputStream body;

        Limited(HttpServletRequest request) {
            super(request);
        }

        @Override
        public ServletInputStream getInputStream() throws IOException {
            if (body == null) {
                body = new Counting(super.getInputStream());
            }
            return body;
        }

        @Override
        public BufferedReader getReader() throws IOException {
            String charset = getCharacterEncoding();
            return new BufferedReader(new InputStreamReader(getInputStream(), charset == null ? "UTF-8" : charset));
        }
    }

    private static final class Counting extends ServletInputStream {

        private final ServletInputStream in;
        private long read;

        Counting(ServletInputStream in) {
            this.in = in;
        }

        private int counted(int n) throws IOException {
            if (n > 0 && (read += n) > MAX_BYTES) {
                throw new TooLarge();
            }
            return n;
        }

        @Override
        public int read() throws IOException {
            int b = in.read();
            counted(b < 0 ? 0 : 1);
            return b;
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            return counted(in.read(buffer, offset, length));
        }

        @Override
        public boolean isFinished() {
            return in.isFinished();
        }

        @Override
        public boolean isReady() {
            return in.isReady();
        }

        @Override
        public void setReadListener(ReadListener listener) {
            in.setReadListener(listener);
        }
    }
}
