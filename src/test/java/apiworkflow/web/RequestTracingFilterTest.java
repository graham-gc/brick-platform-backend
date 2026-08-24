package apiworkflow.web;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import javax.servlet.ServletException;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RequestTracingFilterTest {

    private final RequestTracingFilter filter = new RequestTracingFilter();

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    @Test
    void preservesASafeCallerProvidedRequestId() throws Exception {
        MockHttpServletRequest request = request("/api/brick/flows");
        request.addHeader(RequestTracingFilter.REQUEST_ID_HEADER, "client-request_123");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<String> requestIdDuringChain = new AtomicReference<>();

        filter.doFilter(request, response,
                (servletRequest, servletResponse) -> requestIdDuringChain.set(
                        MDC.get(RequestTracingFilter.REQUEST_ID_MDC_KEY)));

        assertEquals("client-request_123", requestIdDuringChain.get());
        assertEquals("client-request_123", response.getHeader(RequestTracingFilter.REQUEST_ID_HEADER));
        assertNull(MDC.get(RequestTracingFilter.REQUEST_ID_MDC_KEY));
    }

    @Test
    void replacesAnUnsafeRequestId() throws Exception {
        MockHttpServletRequest request = request("/api/brick/flows");
        request.addHeader(RequestTracingFilter.REQUEST_ID_HEADER, "unsafe request id");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, (servletRequest, servletResponse) -> { });

        String generatedId = response.getHeader(RequestTracingFilter.REQUEST_ID_HEADER);
        assertNotNull(generatedId);
        assertNotEquals("unsafe request id", generatedId);
        assertTrue(generatedId.matches("[0-9a-f-]{36}"));
    }

    @Test
    void clearsTheMdcWhenRequestProcessingThrows() {
        MockHttpServletRequest request = request("/api/brick/flows/1");
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertThrows(ServletException.class, () -> filter.doFilter(request, response,
                (servletRequest, servletResponse) -> {
                    throw new ServletException("test failure");
                }));

        assertNotNull(response.getHeader(RequestTracingFilter.REQUEST_ID_HEADER));
        assertNull(MDC.get(RequestTracingFilter.REQUEST_ID_MDC_KEY));
    }

    private MockHttpServletRequest request(String uri) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", uri);
        request.setRequestURI(uri);
        return request;
    }
}
