package com.example.loginapp.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.DelegatingServletInputStream;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletInputStream;

/**
 * A request that declares a body over the limit is refused before anything reads it; one sent in chunks, which
 * declares no length, is cut off once that much of it has been read.
 */
class BodySizeFilterTest {

    private final BodySizeFilter filter = new BodySizeFilter();

    private MockHttpServletResponse send(long declared) throws Exception {
        // The body itself is empty: only the declared length matters to the filter, and a 16 MB array would be wasteful.
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/org/people/import") {
            @Override
            public long getContentLengthLong() {
                return declared;
            }
        };
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, new MockFilterChain());
        return response;
    }

    @Test
    void aBodyWithinTheLimitGoesThrough() throws Exception {
        MockHttpServletResponse response = send(BodySizeFilter.MAX_BYTES);
        assertEquals(200, response.getStatus());
        assertNull(response.getErrorMessage());
    }

    @Test
    void aBodyOverTheLimitIsRefusedWithTheUsualErrorShape() throws Exception {
        MockHttpServletResponse response = send(BodySizeFilter.MAX_BYTES + 1);
        assertEquals(413, response.getStatus());
        assertTrue(response.getContentAsString().contains("\"code\":\"request.too_large\""));
        assertTrue(response.getContentAsString().contains("\"message\""));
    }

    @Test
    void aRequestWithNoDeclaredLengthIsNotRefusedHere() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/auth/me");
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, new MockFilterChain());
        assertEquals(200, response.getStatus());
    }

    /** A request with a body of this many bytes that declares no length, as a chunked one arrives. */
    private static MockHttpServletRequest chunked(int bytes) {
        return new MockHttpServletRequest("POST", "/api/org/people/import") {
            @Override
            public long getContentLengthLong() {
                return -1;
            }

            @Override
            public ServletInputStream getInputStream() {
                return new DelegatingServletInputStream(new ByteArrayInputStream(new byte[bytes]));
            }
        };
    }

    /** A chain that reads the whole body, as a controller would, and notes how much there was. */
    private static FilterChain reading(AtomicLong read) {
        return (request, response) -> read.set(request.getInputStream().readAllBytes().length);
    }

    @Test
    void aChunkedBodyWithinTheLimitIsReadWhole() throws Exception {
        AtomicLong read = new AtomicLong(-1);
        filter.doFilter(chunked(1024), new MockHttpServletResponse(), reading(read));
        assertEquals(1024, read.get());
    }

    @Test
    void aChunkedBodyOverTheLimitIsCutOffWhileBeingRead() {
        AtomicLong read = new AtomicLong(-1);
        IOException e = assertThrows(IOException.class,
                () -> filter.doFilter(chunked((int) BodySizeFilter.MAX_BYTES + 1), new MockHttpServletResponse(), reading(read)));
        assertTrue(e instanceof BodySizeFilter.TooLarge);
        assertEquals(-1, read.get(), "the body was never handed over whole");
    }
}
