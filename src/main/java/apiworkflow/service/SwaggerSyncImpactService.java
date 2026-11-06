package apiworkflow.service;

import apiworkflow.dto.SwaggerSyncImpact;
import apiworkflow.entity.EndpointDefinition;
import apiworkflow.mapper.EndpointDefinitionMapper;
import apiworkflow.mapper.EndpointSchemaMapper;
import apiworkflow.mapper.SwaggerSyncImpactMapper;
import apiworkflow.mapper.SwaggerSyncImpactMapper.ReferenceRow;
import apiworkflow.swagger.EndpointSchemaResolver;
import apiworkflow.swagger.ParsedSwaggerDocument;
import apiworkflow.swagger.SwaggerDocumentParser;
import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONException;
import com.alibaba.fastjson.JSONObject;
import lombok.RequiredArgsConstructor;
import lombok.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
@RequiredArgsConstructor
public class SwaggerSyncImpactService {
    private static final Pattern PATH_SEGMENT = Pattern.compile("\\.([A-Za-z_][A-Za-z0-9_-]*)|\\[(?:[0-9]+|\\*)\\]");

    private final SwaggerDocumentParser parser;
    private final EndpointDefinitionMapper endpointMapper;
    private final EndpointSchemaMapper schemaMapper;
    private final SwaggerSyncImpactMapper impactMapper;
    private final EndpointSchemaResolver schemaResolver;

    @Transactional(readOnly = true)
    public List<SwaggerSyncImpact> preview(Integer mappingId, String swaggerContent,
                                           String customHost, String customBasePath) {
        ParsedSwaggerDocument incoming = parser.parse(swaggerContent, customHost, customBasePath);
        Map<Integer, List<Reference>> references = collectReferences(impactMapper.selectReferences(mappingId));
        if (references.isEmpty()) {
            return Collections.emptyList();
        }

        Map<String, EndpointDefinition> incomingByOperation = new HashMap<>();
        for (EndpointDefinition endpoint : incoming.getEndpoints()) {
            incomingByOperation.put(operationKey(endpoint), endpoint);
        }
        Function<Object, Object> oldResolver = schemaResolver.prepare(schemaMapper.selectBySwaggerMappingId(mappingId));
        Function<Object, Object> newResolver = schemaResolver.prepare(incoming.getSchemas());
        List<SwaggerSyncImpact> impacts = new ArrayList<>();
        for (EndpointDefinition previous : endpointMapper.selectAllBySwaggerMappingId(mappingId, 0)) {
            List<Reference> endpointReferences = references.get(previous.getId());
            EndpointDefinition next = incomingByOperation.get(operationKey(previous));
            if (endpointReferences == null || next == null) {
                continue;
            }
            Map<ResponseKey, JSONObject> oldResponses = responseSchemas(previous, oldResolver);
            Map<ResponseKey, JSONObject> newResponses = responseSchemas(next, newResolver);
            for (Map.Entry<ResponseKey, JSONObject> oldResponse : oldResponses.entrySet()) {
                ResponseKey key = oldResponse.getKey();
                JSONObject newSchema = newResponses.get(key);
                if (newSchema == null) {
                    continue;
                }
                for (Reference reference : endpointReferences) {
                    if (locate(oldResponse.getValue(), reference.segments) == Presence.DECLARED
                            && locate(newSchema, reference.segments) == Presence.MISSING) {
                        impacts.add(reference.impact(previous, key));
                    }
                }
            }
        }
        return impacts;
    }

    private Map<Integer, List<Reference>> collectReferences(List<ReferenceRow> rows) {
        Map<Integer, List<Reference>> references = new LinkedHashMap<>();
        Set<Long> visitedNodes = new HashSet<>();
        for (ReferenceRow row : rows) {
            if (visitedNodes.add(row.getNodeId())) {
                Object variables = parseJson(row.getResponseVariablesJson());
                if (variables instanceof JSONArray) {
                    for (Object value : (JSONArray) variables) {
                        if (value instanceof JSONObject) {
                            JSONObject variable = (JSONObject) value;
                            String name = variable.getString("name");
                            if (StringUtils.hasText(name)) {
                                addReference(references, row, variable.getString("responsePath"), name, null);
                            }
                        }
                    }
                }
            }
            if (row.getAssertionId() != null) {
                addReference(references, row, row.getAssertionPath(), null, row.getAssertionId());
            }
        }
        return references;
    }

    private void addReference(Map<Integer, List<Reference>> references, ReferenceRow row,
                              String rawPath, String variableName, Long assertionId) {
        String path = rawPath == null ? "" : rawPath.trim();
        List<String> segments = simplePathSegments(path);
        if (!segments.isEmpty()) {
            references.computeIfAbsent(row.getEndpointId(), key -> new ArrayList<>())
                    .add(new Reference(row, path, segments, variableName, assertionId));
        }
    }

    private List<String> simplePathSegments(String path) {
        if (!path.startsWith("$")) {
            return Collections.emptyList();
        }
        List<String> segments = new ArrayList<>();
        Matcher matcher = PATH_SEGMENT.matcher(path);
        int position = 1;
        while (matcher.find()) {
            if (matcher.start() != position) {
                return Collections.emptyList();
            }
            // A null segment denotes an array item (index or wildcard).
            segments.add(matcher.group(1));
            position = matcher.end();
        }
        return position == path.length() ? segments : Collections.emptyList();
    }

    private Map<ResponseKey, JSONObject> responseSchemas(EndpointDefinition endpoint,
                                                          Function<Object, Object> resolver) {
        Map<ResponseKey, JSONObject> schemas = new LinkedHashMap<>();
        Object definition = parseJson(endpoint.getRequestDefinitionJson());
        Object responses = definition instanceof JSONObject ? ((JSONObject) definition).get("responses") : null;
        if (!(responses instanceof JSONArray)) {
            return schemas;
        }
        for (Object entry : (JSONArray) responses) {
            if (!(entry instanceof JSONObject)) {
                continue;
            }
            JSONObject response = (JSONObject) entry;
            String status = response.getString("statusCode");
            if (!StringUtils.hasText(status) || !(response.get("schema") instanceof JSONObject)) {
                continue;
            }
            try {
                Object schema = resolver.apply(response.get("schema"));
                if (schema instanceof JSONObject) {
                    schemas.put(new ResponseKey(status, response.getString("contentType")), (JSONObject) schema);
                }
            } catch (IllegalArgumentException | IllegalStateException unresolved) {
                // A missing or circular reference cannot establish a removed field.
            }
        }
        return schemas;
    }

    private Presence locate(JSONObject schema, List<String> segments) {
        Object current = schema;
        for (String segment : segments) {
            if (!(current instanceof JSONObject)) {
                return Presence.UNKNOWN;
            }
            JSONObject definition = (JSONObject) current;
            if (definition.containsKey("allOf") || definition.containsKey("anyOf")
                    || definition.containsKey("oneOf") || definition.containsKey("if")) {
                return Presence.UNKNOWN;
            }
            if (segment == null) {
                current = definition.get("items");
                continue;
            }
            Object properties = definition.get("properties");
            if (!(properties instanceof JSONObject)) {
                return Presence.UNKNOWN;
            }
            JSONObject fields = (JSONObject) properties;
            if (!fields.containsKey(segment)) {
                return Presence.MISSING;
            }
            current = fields.get(segment);
        }
        return current instanceof JSONObject || current instanceof Boolean ? Presence.DECLARED : Presence.UNKNOWN;
    }

    private Object parseJson(String json) {
        if (!StringUtils.hasText(json)) {
            return null;
        }
        try {
            return JSON.parse(json);
        } catch (JSONException invalidJson) {
            return null;
        }
    }

    private String operationKey(EndpointDefinition endpoint) {
        return endpoint.getHttpMethod() + " " + endpoint.getEndpointPath();
    }

    private enum Presence { DECLARED, MISSING, UNKNOWN }

    @Value
    private static class ResponseKey {
        String status;
        String contentType;
    }

    @Value
    private static class Reference {
        ReferenceRow row;
        String fieldPath;
        List<String> segments;
        String variableName;
        Long assertionId;

        SwaggerSyncImpact impact(EndpointDefinition endpoint, ResponseKey response) {
            return new SwaggerSyncImpact(row.getFlowId(), row.getFlowName(), row.getNodeId(),
                    endpoint.getHttpMethod(), endpoint.getEndpointPath(), response.status, response.contentType,
                    fieldPath, assertionId == null ? SwaggerSyncImpact.ReferenceType.RESPONSE_VARIABLE
                            : SwaggerSyncImpact.ReferenceType.ASSERTION, variableName, assertionId);
        }
    }
}
