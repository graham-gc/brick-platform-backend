package apiworkflow.web;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import javax.servlet.FilterChain;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

@Component
public class RequestTracingFilter extends OncePerRequestFilter {

    public static final String REQUEST_ID_HEADER = "X-Request-ID";
    public static final String REQUEST_ID_MDC_KEY = "requestId";

    private static final Logger LOGGER = LoggerFactory.getLogger(RequestTracingFilter.class);
    private static final Pattern SAFE_REQUEST_ID = Pattern.compile("[A-Za-z0-9._-]{1,100}");

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        long startNanos = System.nanoTime();
        String requestId = resolveRequestId(request.getHeader(REQUEST_ID_HEADER));

        MDC.put(REQUEST_ID_MDC_KEY, requestId);
        response.setHeader(REQUEST_ID_HEADER, requestId);

        try {
            filterChain.doFilter(request, response);
        } finally {
            long durationMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);
            logCompletion(request, response, durationMs);
            MDC.remove(REQUEST_ID_MDC_KEY);
        }
    }

    private String resolveRequestId(String candidate) {
        if (candidate != null && SAFE_REQUEST_ID.matcher(candidate).matches()) {
            return candidate;
        }
        return UUID.randomUUID().toString();
    }

    private void logCompletion(HttpServletRequest request, HttpServletResponse response, long durationMs) {
        String requestUri = request.getRequestURI();
        if (requestUri.endsWith("/actuator/health")) {
            LOGGER.debug("HTTP {} {} completed status={} durationMs={}",
                    request.getMethod(), requestUri, response.getStatus(), durationMs);
            return;
        }
        LOGGER.info("HTTP {} {} completed status={} durationMs={}",
                request.getMethod(), requestUri, response.getStatus(), durationMs);
    }
}
