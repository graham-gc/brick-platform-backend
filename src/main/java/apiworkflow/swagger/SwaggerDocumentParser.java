package apiworkflow.swagger;

import apiworkflow.entity.EndpointDefinition;
import apiworkflow.entity.EndpointSchema;
import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Component
public class SwaggerDocumentParser {

    private static final List<String> HTTP_METHODS = Arrays.asList(
            "get", "post", "put", "delete", "patch", "head", "options", "trace"
    );

    public ParsedSwaggerDocument parse(String json, String customHost, String customBasePath) {
        if (!StringUtils.hasText(json)) {
            throw new IllegalArgumentException("Swagger document is empty");
        }

        final JSONObject root;
        try {
            root = JSON.parseObject(json);
        } catch (Exception e) {
            throw new IllegalArgumentException("Swagger document is not valid JSON", e);
        }

        String swaggerVersion = detectVersion(root);
        JSONObject paths = root.getJSONObject("paths");
        if (paths == null) {
            throw new IllegalArgumentException("Swagger document does not contain a paths object");
        }

        ServerInfo server = "v2".equals(swaggerVersion)
                ? parseSwagger2Server(root)
                : parseOpenApi3Server(root);
        server = applyOverrides(server, customHost, customBasePath);

        List<EndpointDefinition> endpoints = new ArrayList<>();
        for (Map.Entry<String, Object> pathEntry : paths.entrySet()) {
            String endpointPath = normaliseEndpointPath(pathEntry.getKey());
            JSONObject pathItem = paths.getJSONObject(pathEntry.getKey());
            if (pathItem == null) {
                continue;
            }

            for (String method : HTTP_METHODS) {
                JSONObject operation = resolveObject(root, pathItem.getJSONObject(method));
                if (operation == null) {
                    continue;
                }

                EndpointDefinition endpoint = new EndpointDefinition();
                endpoint.setProtocol(server.protocol);
                endpoint.setHost(server.host);
                endpoint.setBasePath(server.basePath);
                endpoint.setEndpointPath(endpointPath);
                endpoint.setFullUrl(buildFullUrl(server, endpointPath));
                endpoint.setHttpMethod(method.toUpperCase());
                endpoint.setOperationId(operation.getString("operationId"));
                endpoint.setSummary(operation.getString("summary"));
                endpoint.setDescription(operation.getString("description"));
                endpoint.setTags(joinArray(operation.getJSONArray("tags")));
                endpoint.setDeprecated(Boolean.TRUE.equals(operation.getBoolean("deprecated")) ? 1 : 0);
                endpoint.setSwaggerVersion(swaggerVersion);
                endpoint.setConsumesTypes("v2".equals(swaggerVersion)
                        ? swagger2Consumes(root, operation)
                        : openApi3Consumes(root, operation));
                endpoint.setProducesTypes("v2".equals(swaggerVersion)
                        ? swagger2Produces(root, operation)
                        : openApi3Produces(root, operation));
                endpoint.setDocChecksum(checksum(swaggerVersion, server, endpointPath, method, operation));
                endpoint.setIsLightweight(0);
                endpoint.setIsDeleted(0);
                endpoints.add(endpoint);
            }
        }

        List<EndpointSchema> schemas = parseSchemas(root, swaggerVersion);
        return new ParsedSwaggerDocument(
                swaggerVersion, server.protocol, server.host, server.basePath, endpoints, schemas
        );
    }

    private List<EndpointSchema> parseSchemas(JSONObject root, String swaggerVersion) {
        JSONObject schemas;
        String refPrefix;
        if ("v2".equals(swaggerVersion)) {
            schemas = root.getJSONObject("definitions");
            refPrefix = "#/definitions/";
        } else {
            JSONObject components = root.getJSONObject("components");
            schemas = components == null ? null : components.getJSONObject("schemas");
            refPrefix = "#/components/schemas/";
        }

        if (schemas == null || schemas.isEmpty()) {
            return Collections.emptyList();
        }

        List<String> schemaNames = new ArrayList<>(schemas.keySet());
        Collections.sort(schemaNames);
        List<EndpointSchema> result = new ArrayList<>(schemaNames.size());
        for (String schemaName : schemaNames) {
            Object schemaValue = schemas.get(schemaName);
            if (schemaValue == null) {
                continue;
            }
            EndpointSchema schema = new EndpointSchema();
            schema.setSchemaName(schemaName);
            schema.setSchemaRef(refPrefix + escapeJsonPointerToken(schemaName));
            schema.setSchemaJson(JSON.toJSONString(schemaValue));
            schema.setIsDeleted(0);
            result.add(schema);
        }
        return result;
    }

    private String escapeJsonPointerToken(String value) {
        return value.replace("~", "~0").replace("/", "~1");
    }

    private String detectVersion(JSONObject root) {
        String swagger = root.getString("swagger");
        if (StringUtils.hasText(swagger)) {
            if (!swagger.startsWith("2.")) {
                throw new IllegalArgumentException("Unsupported Swagger version: " + swagger);
            }
            return "v2";
        }

        String openapi = root.getString("openapi");
        if (StringUtils.hasText(openapi)) {
            if (!openapi.startsWith("3.")) {
                throw new IllegalArgumentException("Unsupported OpenAPI version: " + openapi);
            }
            return "v3";
        }
        throw new IllegalArgumentException("Document is neither Swagger 2.x nor OpenAPI 3.x");
    }

    private ServerInfo parseSwagger2Server(JSONObject root) {
        String protocol = "http";
        JSONArray schemes = root.getJSONArray("schemes");
        if (schemes != null && !schemes.isEmpty() && StringUtils.hasText(schemes.getString(0))) {
            protocol = schemes.getString(0);
        }
        return new ServerInfo(protocol, valueOrEmpty(root.getString("host")),
                normaliseBasePath(root.getString("basePath")));
    }

    private ServerInfo parseOpenApi3Server(JSONObject root) {
        JSONArray servers = root.getJSONArray("servers");
        if (servers == null || servers.isEmpty()) {
            return new ServerInfo("http", "", "");
        }

        JSONObject serverObject = servers.getJSONObject(0);
        String url = serverObject == null ? null : serverObject.getString("url");
        if (!StringUtils.hasText(url)) {
            return new ServerInfo("http", "", "");
        }

        JSONObject variables = serverObject.getJSONObject("variables");
        if (variables != null) {
            for (String variableName : variables.keySet()) {
                JSONObject variable = variables.getJSONObject(variableName);
                if (variable != null && variable.getString("default") != null) {
                    url = url.replace("{" + variableName + "}", variable.getString("default"));
                }
            }
        }
        return parseServerUrl(url, "http");
    }

    private ServerInfo applyOverrides(ServerInfo original, String customHost, String customBasePath) {
        ServerInfo result = original;
        if (StringUtils.hasText(customHost)) {
            String trimmed = customHost.trim();
            if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
                ServerInfo override = parseServerUrl(trimmed, original.protocol);
                String basePath = StringUtils.hasText(customBasePath)
                        ? normaliseBasePath(customBasePath)
                        : (StringUtils.hasText(override.basePath) ? override.basePath : original.basePath);
                result = new ServerInfo(override.protocol, override.host, basePath);
            } else {
                result = new ServerInfo(original.protocol, stripTrailingSlash(trimmed), original.basePath);
            }
        }
        if (StringUtils.hasText(customBasePath)) {
            result = new ServerInfo(result.protocol, result.host, normaliseBasePath(customBasePath));
        }
        return result;
    }

    private ServerInfo parseServerUrl(String url, String defaultProtocol) {
        try {
            URI uri = URI.create(url.trim());
            if (uri.isAbsolute()) {
                return new ServerInfo(
                        valueOrDefault(uri.getScheme(), defaultProtocol),
                        valueOrEmpty(uri.getRawAuthority()),
                        normaliseBasePath(uri.getRawPath())
                );
            }
            return new ServerInfo(defaultProtocol, "", normaliseBasePath(uri.getPath()));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Invalid server URL in Swagger document: " + url, e);
        }
    }

    private String swagger2Consumes(JSONObject root, JSONObject operation) {
        JSONArray consumes = operation.getJSONArray("consumes");
        return joinArray(consumes != null ? consumes : root.getJSONArray("consumes"));
    }

    private String swagger2Produces(JSONObject root, JSONObject operation) {
        JSONArray produces = operation.getJSONArray("produces");
        return joinArray(produces != null ? produces : root.getJSONArray("produces"));
    }

    private String openApi3Consumes(JSONObject root, JSONObject operation) {
        JSONObject requestBody = resolveObject(root, operation.getJSONObject("requestBody"));
        JSONObject content = requestBody == null ? null : requestBody.getJSONObject("content");
        return joinKeys(content);
    }

    private String openApi3Produces(JSONObject root, JSONObject operation) {
        Set<String> contentTypes = new LinkedHashSet<>();
        JSONObject responses = operation.getJSONObject("responses");
        if (responses != null) {
            for (String status : responses.keySet()) {
                JSONObject response = resolveObject(root, responses.getJSONObject(status));
                JSONObject content = response == null ? null : response.getJSONObject("content");
                if (content != null) {
                    contentTypes.addAll(content.keySet());
                }
            }
        }
        List<String> sorted = new ArrayList<>(contentTypes);
        Collections.sort(sorted);
        return sorted.isEmpty() ? null : String.join(",", sorted);
    }

    private JSONObject resolveObject(JSONObject root, JSONObject object) {
        if (object == null) {
            return null;
        }
        String ref = object.getString("$ref");
        if (!StringUtils.hasText(ref)) {
            return object;
        }
        if (!ref.startsWith("#/")) {
            throw new IllegalArgumentException("External references are not supported: " + ref);
        }

        Object current = root;
        String[] segments = ref.substring(2).split("/");
        for (String rawSegment : segments) {
            if (!(current instanceof JSONObject)) {
                throw new IllegalArgumentException("Unresolvable reference: " + ref);
            }
            String segment = rawSegment.replace("~1", "/").replace("~0", "~");
            current = ((JSONObject) current).get(segment);
        }
        if (!(current instanceof JSONObject)) {
            throw new IllegalArgumentException("Unresolvable reference: " + ref);
        }
        return (JSONObject) current;
    }

    private String buildFullUrl(ServerInfo server, String endpointPath) {
        String path = joinPaths(server.basePath, endpointPath);
        if (!StringUtils.hasText(server.host)) {
            return path;
        }
        return server.protocol + "://" + server.host + path;
    }

    private String checksum(String swaggerVersion, ServerInfo server, String endpointPath,
                            String method, JSONObject operation) {
        String source = swaggerVersion + "\n" + server.protocol + "\n" + server.host + "\n"
                + server.basePath + "\n" + endpointPath + "\n" + method + "\n"
                + JSON.toJSONString(operation, true);
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(source.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(bytes.length * 2);
            for (byte value : bytes) {
                hex.append(String.format("%02x", value & 0xff));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }

    private String joinArray(JSONArray values) {
        if (values == null || values.isEmpty()) {
            return null;
        }
        List<String> result = new ArrayList<>();
        for (Object value : values) {
            if (value != null) {
                result.add(String.valueOf(value));
            }
        }
        Collections.sort(result);
        return result.isEmpty() ? null : String.join(",", result);
    }

    private String joinKeys(JSONObject object) {
        if (object == null || object.isEmpty()) {
            return null;
        }
        List<String> keys = new ArrayList<>(object.keySet());
        Collections.sort(keys);
        return String.join(",", keys);
    }

    private String normaliseEndpointPath(String path) {
        if (!StringUtils.hasText(path)) {
            return "/";
        }
        return path.startsWith("/") ? path : "/" + path;
    }

    private String normaliseBasePath(String path) {
        if (!StringUtils.hasText(path) || "/".equals(path.trim())) {
            return "";
        }
        String result = path.trim();
        if (!result.startsWith("/")) {
            result = "/" + result;
        }
        return stripTrailingSlash(result);
    }

    private String stripTrailingSlash(String value) {
        String result = value;
        while (result.length() > 1 && result.endsWith("/")) {
            result = result.substring(0, result.length() - 1);
        }
        return result;
    }

    private String joinPaths(String left, String right) {
        String normalisedLeft = normaliseBasePath(left);
        String normalisedRight = normaliseEndpointPath(right);
        return normalisedLeft + normalisedRight;
    }

    private String valueOrEmpty(String value) {
        return value == null ? "" : value;
    }

    private String valueOrDefault(String value, String defaultValue) {
        return StringUtils.hasText(value) ? value : defaultValue;
    }

    private static final class ServerInfo {
        private final String protocol;
        private final String host;
        private final String basePath;

        private ServerInfo(String protocol, String host, String basePath) {
            this.protocol = protocol;
            this.host = host;
            this.basePath = basePath;
        }
    }
}
