package apiworkflow.execution;

import apiworkflow.entity.BrickFlow;
import apiworkflow.entity.BrickFlowNode;
import apiworkflow.entity.BrickFlowRunNode;
import apiworkflow.entity.EndpointDefinition;
import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import org.apache.http.Header;
import org.apache.http.HttpEntity;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpUriRequest;
import org.apache.http.client.methods.RequestBuilder;
import org.apache.http.client.utils.URIBuilder;
import org.apache.http.entity.ContentType;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.util.EntityUtils;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class FlowHttpExecutor {

    private static final Pattern PATH_VARIABLE = Pattern.compile("\\{([^{}]+)}");
    private static final int DEFAULT_TIMEOUT_SECONDS = 30;
    private static final int RESPONSE_PREVIEW_LENGTH = 800;

    public BrickFlowRunNode execute(Long runId, BrickFlow flow, BrickFlowNode node,
                                    EndpointDefinition endpoint, String overrideBaseUrl,
                                    Map<String, String> customHeaders) {
        Date startTime = new Date();
        BrickFlowRunNode result = new BrickFlowRunNode();
        result.setRunId(runId);
        result.setNodeId(node.getId());
        result.setEndpointId(node.getEndpointId());
        result.setGrpcEndpointId(node.getGrpcEndpointId());
        result.setStartTime(startTime);
        result.setRequestMethod(endpoint.getHttpMethod());

        try {
            Map<String, Object> pathVariables = parseJsonObject(node.getPathVarsJson(), "path parameters");
            Map<String, Object> queryParameters = parseJsonObject(node.getQueryParamsJson(), "query parameters");
            Map<String, String> headers = mergeHeaders(flow.getSharedHeadersJson(), node.getHeadersJson(), customHeaders);
            String requestBody = trimToNull(node.getPayloadJson());
            String requestUrl = buildRequestUrl(endpoint, overrideBaseUrl, pathVariables, queryParameters);

            result.setRequestUrl(requestUrl);
            result.setRequestHeaders(JSON.toJSONString(headers));
            result.setRequestBody(requestBody);
            result.setRequestQueryParams(JSON.toJSONString(queryParameters));
            result.setRequestPathParams(JSON.toJSONString(pathVariables));

            int maxAttempts = Math.max(1, valueOrDefault(node.getRetries(), 0) + 1);
            ExecutionResponse response = null;
            Exception lastError = null;
            for (int attempt = 1; attempt <= maxAttempts; attempt++) {
                try {
                    response = send(endpoint.getHttpMethod(), requestUrl, headers, requestBody,
                            valueOrDefault(node.getTimeoutSec(), DEFAULT_TIMEOUT_SECONDS));
                    if (response.statusCode < 500 || attempt == maxAttempts) {
                        break;
                    }
                } catch (Exception e) {
                    lastError = e;
                    if (attempt == maxAttempts) {
                        throw e;
                    }
                }
            }
            if (response == null) {
                throw lastError == null ? new IllegalStateException("HTTP execution returned no response") : lastError;
            }

            result.setHttpStatus(response.statusCode);
            result.setResponseHeaders(JSON.toJSONString(response.headers));
            result.setFullResponse(response.body);
            result.setResponsePreview(preview(response.body));
            result.setResponseSize(response.body.getBytes(StandardCharsets.UTF_8).length);
            if (response.statusCode >= 200 && response.statusCode < 400) {
                result.setStatus("success");
            } else {
                result.setStatus("failed");
                result.setErrorMsg("HTTP " + response.statusCode);
            }
        } catch (Exception e) {
            result.setStatus("failed");
            result.setErrorMsg(rootMessage(e));
        }

        Date endTime = new Date();
        result.setEndTime(endTime);
        result.setDurationMs(endTime.getTime() - startTime.getTime());
        result.setAssertionTotalCount(0);
        result.setAssertionPassedCount(0);
        result.setAssertionFailedCount(0);
        return result;
    }

    private ExecutionResponse send(String method, String url, Map<String, String> headers,
                                   String body, int timeoutSeconds) throws Exception {
        int timeoutMillis = Math.max(1, timeoutSeconds) * 1000;
        RequestConfig requestConfig = RequestConfig.custom()
                .setConnectTimeout(timeoutMillis)
                .setConnectionRequestTimeout(timeoutMillis)
                .setSocketTimeout(timeoutMillis)
                .build();
        RequestBuilder builder = RequestBuilder.create(method).setUri(url).setConfig(requestConfig);
        for (Map.Entry<String, String> header : headers.entrySet()) {
            builder.setHeader(header.getKey(), header.getValue());
        }
        if (body != null && allowsRequestBody(method)) {
            ContentType contentType = contentType(headers.get("Content-Type"));
            builder.setEntity(new StringEntity(body, contentType));
            if (!containsHeaderIgnoreCase(headers, "Content-Type")) {
                builder.setHeader("Content-Type", ContentType.APPLICATION_JSON.getMimeType());
            }
        }

        HttpUriRequest request = builder.build();
        try (CloseableHttpClient client = HttpClients.custom().disableCookieManagement().build();
             CloseableHttpResponse response = client.execute(request)) {
            HttpEntity entity = response.getEntity();
            String responseBody = entity == null ? "" : EntityUtils.toString(entity, StandardCharsets.UTF_8);
            Map<String, List<String>> responseHeaders = new LinkedHashMap<>();
            for (Header header : response.getAllHeaders()) {
                responseHeaders.computeIfAbsent(header.getName(), ignored -> new ArrayList<String>())
                        .add(header.getValue());
            }
            return new ExecutionResponse(response.getStatusLine().getStatusCode(), responseHeaders, responseBody);
        }
    }

    private String buildRequestUrl(EndpointDefinition endpoint, String overrideBaseUrl,
                                   Map<String, Object> pathVariables,
                                   Map<String, Object> queryParameters) throws Exception {
        String rawUrl = StringUtils.hasText(overrideBaseUrl)
                ? stripTrailingSlash(overrideBaseUrl) + normalisePath(endpoint.getBasePath())
                    + normalisePath(endpoint.getEndpointPath())
                : endpoint.getFullUrl();
        if (!StringUtils.hasText(rawUrl)) {
            throw new IllegalArgumentException("Endpoint has no executable URL: " + endpoint.getId());
        }

        String resolvedPath = rawUrl;
        for (Map.Entry<String, Object> pathVariable : pathVariables.entrySet()) {
            resolvedPath = resolvedPath.replace("{" + pathVariable.getKey() + "}",
                    encodePathSegment(String.valueOf(pathVariable.getValue())));
        }
        Matcher unresolved = PATH_VARIABLE.matcher(resolvedPath);
        if (unresolved.find()) {
            throw new IllegalArgumentException("Missing path parameter: " + unresolved.group(1));
        }

        URIBuilder uriBuilder = new URIBuilder(resolvedPath);
        for (Map.Entry<String, Object> query : queryParameters.entrySet()) {
            if (query.getValue() instanceof Iterable) {
                for (Object value : (Iterable<?>) query.getValue()) {
                    uriBuilder.addParameter(query.getKey(), value == null ? "" : String.valueOf(value));
                }
            } else if (query.getValue() != null) {
                uriBuilder.addParameter(query.getKey(), String.valueOf(query.getValue()));
            }
        }
        URI uri = uriBuilder.build();
        return uri.toASCIIString();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parseJsonObject(String json, String label) {
        if (!StringUtils.hasText(json)) {
            return new LinkedHashMap<>();
        }
        try {
            Object value = JSON.parse(json);
            if (!(value instanceof JSONObject)) {
                throw new IllegalArgumentException(label + " must be a JSON object");
            }
            return new LinkedHashMap<>((JSONObject) value);
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid " + label + " JSON", e);
        }
    }

    private Map<String, String> mergeHeaders(String sharedHeadersJson, String nodeHeadersJson,
                                             Map<String, String> customHeaders) {
        Map<String, String> result = new LinkedHashMap<>();
        addHeaders(result, parseJsonObject(sharedHeadersJson, "shared headers"));
        addHeaders(result, parseJsonObject(nodeHeadersJson, "headers"));
        if (customHeaders != null) {
            result.putAll(customHeaders);
        }
        return result;
    }

    private void addHeaders(Map<String, String> target, Map<String, Object> source) {
        for (Map.Entry<String, Object> entry : source.entrySet()) {
            if (entry.getValue() != null) {
                target.put(entry.getKey(), String.valueOf(entry.getValue()));
            }
        }
    }

    private ContentType contentType(String headerValue) {
        if (!StringUtils.hasText(headerValue)) {
            return ContentType.APPLICATION_JSON;
        }
        try {
            return ContentType.parse(headerValue);
        } catch (Exception ignored) {
            return ContentType.APPLICATION_JSON;
        }
    }

    private boolean containsHeaderIgnoreCase(Map<String, String> headers, String expectedName) {
        for (String name : headers.keySet()) {
            if (expectedName.equalsIgnoreCase(name)) {
                return true;
            }
        }
        return false;
    }

    private boolean allowsRequestBody(String method) {
        return "POST".equalsIgnoreCase(method) || "PUT".equalsIgnoreCase(method)
                || "PATCH".equalsIgnoreCase(method) || "DELETE".equalsIgnoreCase(method);
    }

    private String encodePathSegment(String value) throws Exception {
        return URLEncoder.encode(value, StandardCharsets.UTF_8.name()).replace("+", "%20");
    }

    private String normalisePath(String value) {
        if (!StringUtils.hasText(value) || "/".equals(value)) {
            return "";
        }
        return value.startsWith("/") ? value : "/" + value;
    }

    private String stripTrailingSlash(String value) {
        String result = value.trim();
        while (result.endsWith("/")) {
            result = result.substring(0, result.length() - 1);
        }
        return result;
    }

    private String trimToNull(String value) {
        return StringUtils.hasText(value) ? value.trim() : null;
    }

    private int valueOrDefault(Integer value, int defaultValue) {
        return value == null ? defaultValue : value;
    }

    private String preview(String body) {
        return body.length() <= RESPONSE_PREVIEW_LENGTH ? body : body.substring(0, RESPONSE_PREVIEW_LENGTH);
    }

    private String rootMessage(Throwable error) {
        Throwable current = error;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        return StringUtils.hasText(current.getMessage())
                ? current.getMessage() : current.getClass().getSimpleName();
    }

    private static final class ExecutionResponse {
        private final int statusCode;
        private final Map<String, List<String>> headers;
        private final String body;

        private ExecutionResponse(int statusCode, Map<String, List<String>> headers, String body) {
            this.statusCode = statusCode;
            this.headers = headers;
            this.body = body;
        }
    }
}
