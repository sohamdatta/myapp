package com.example.loginapp.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/** A request that declares a body over the limit is refused before anything reads it. */
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
}
