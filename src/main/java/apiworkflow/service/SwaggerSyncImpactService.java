package apiworkflow.service;

import apiworkflow.dto.SwaggerSyncImpact;
import apiworkflow.dto.SwaggerSyncPreviewReport;
import apiworkflow.dto.SwaggerSyncPreviewReport.ContractVersion;
import apiworkflow.dto.SwaggerSyncPreviewReport.Reason;
import apiworkflow.dto.SwaggerSyncPreviewReport.UnassessedReference;
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
    public SwaggerSyncPreviewReport preview(Integer mappingId, String swaggerContent,
                                            String customHost, String customBasePath) {
        ParsedSwaggerDocument incoming = parser.parse(swaggerContent, customHost, customBasePath);
        Map<Integer, List<Reference>> references = collectReferences(impactMapper.selectReferences(mappingId));
        if (references.isEmpty()) {
            return new SwaggerSyncPreviewReport(0, Collections.emptyList(), Collections.emptyList());
        }

        Map<String, EndpointDefinition> incomingByOperation = new HashMap<>();
        for (EndpointDefinition endpoint : incoming.getEndpoints()) {
            incomingByOperation.put(operationKey(endpoint), endpoint);
        }
        Function<Object, Object> oldResolver = schemaResolver.prepare(schemaMapper.selectBySwaggerMappingId(mappingId));
        Function<Object, Object> newResolver = schemaResolver.prepare(incoming.getSchemas());
        int checkedCount = 0;
        List<SwaggerSyncImpact> impacts = new ArrayList<>();
        List<UnassessedReference> unassessed = new ArrayList<>();
        for (EndpointDefinition previous : endpointMapper.selectAllBySwaggerMappingId(mappingId, 0)) {
            List<Reference> endpointReferences = references.get(previous.getId());
            if (endpointReferences == null) {
                continue;
            }
            EndpointDefinition next = incomingByOperation.get(operationKey(previous));
            Map<ResponseKey, ResponseSchema> oldResponses = responseSchemas(previous, oldResolver);
            Map<ResponseKey, ResponseSchema> newResponses = next == null ? null : responseSchemas(next, newResolver);
            for (Reference reference : endpointReferences) {
                checkedCount += assessReference(previous, reference, oldResponses, newResponses, impacts, unassessed);
            }
        }
        return new SwaggerSyncPreviewReport(checkedCount, impacts, unassessed);
    }

    private int assessReference(EndpointDefinition endpoint, Reference reference,
                                Map<ResponseKey, ResponseSchema> oldResponses,
                                Map<ResponseKey, ResponseSchema> newResponses,
                                List<SwaggerSyncImpact> impacts, List<UnassessedReference> unassessed) {
        if (reference.segments.isEmpty()) {
            unassessed.add(reference.unassessed(endpoint, null, Reason.UNSUPPORTED_JSONPATH, null));
            return 0;
        }
        if (newResponses == null) {
            unassessed.add(reference.unassessed(endpoint, null, Reason.OPERATION_MISSING, ContractVersion.INCOMING));
            return 0;
        }
        if (oldResponses.isEmpty()) {
            unassessed.add(reference.unassessed(endpoint, null, Reason.NO_DOCUMENTED_RESPONSE, ContractVersion.CURRENT));
            return 0;
        }

        int checkedCount = 0;
        boolean hasCandidate = false;
        for (Map.Entry<ResponseKey, ResponseSchema> entry : oldResponses.entrySet()) {
            ResponseKey key = entry.getKey();
            PathAssessment before = locate(entry.getValue(), reference.segments);
            if (before == PathAssessment.MISSING) {
                continue;
            }
            hasCandidate = true;
            if (before.reason != null) {
                unassessed.add(reference.unassessed(endpoint, key, before.reason, ContractVersion.CURRENT));
                continue;
            }

            ResponseSchema newResponse = newResponses.get(key);
            if (newResponse == null) {
                unassessed.add(reference.unassessed(endpoint, key, Reason.RESPONSE_MISSING, ContractVersion.INCOMING));
                continue;
            }
            PathAssessment after = locate(newResponse, reference.segments);
            if (after.reason != null) {
                unassessed.add(reference.unassessed(endpoint, key, after.reason, ContractVersion.INCOMING));
                continue;
            }

            checkedCount++;
            if (after == PathAssessment.MISSING) {
                impacts.add(reference.detail(endpoint, key));
            }
        }
        if (!hasCandidate) {
            unassessed.add(reference.unassessed(endpoint, null, Reason.PATH_NOT_DECLARED, ContractVersion.CURRENT));
        }
        return checkedCount;
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
        references.computeIfAbsent(row.getEndpointId(), key -> new ArrayList<>())
                .add(new Reference(row, path, simplePathSegments(path), variableName, assertionId));
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

    private Map<ResponseKey, ResponseSchema> responseSchemas(EndpointDefinition endpoint,
                                                              Function<Object, Object> resolver) {
        Map<ResponseKey, ResponseSchema> schemas = new LinkedHashMap<>();
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
            if (!StringUtils.hasText(status)) {
                continue;
            }
            ResponseKey key = new ResponseKey(status, response.getString("contentType"));
            if (!(response.get("schema") instanceof JSONObject)) {
                schemas.put(key, new ResponseSchema(null, PathAssessment.SCHEMA_MISSING));
                continue;
            }
            try {
                Object schema = resolver.apply(response.get("schema"));
                if (schema instanceof JSONObject) {
                    schemas.put(key, new ResponseSchema((JSONObject) schema, null));
                } else {
                    schemas.put(key, new ResponseSchema(null, PathAssessment.UNSPECIFIED_SCHEMA_SHAPE));
                }
            } catch (IllegalArgumentException | IllegalStateException unresolved) {
                schemas.put(key, new ResponseSchema(null, PathAssessment.UNRESOLVED_SCHEMA));
            }
        }
        return schemas;
    }

    private PathAssessment locate(ResponseSchema response, List<String> segments) {
        if (response.issue != null) {
            return response.issue;
        }
        Object current = response.schema;
        for (String segment : segments) {
            if (!(current instanceof JSONObject)) {
                return PathAssessment.UNSPECIFIED_SCHEMA_SHAPE;
            }
            JSONObject definition = (JSONObject) current;
            if (definition.containsKey("allOf") || definition.containsKey("anyOf")
                    || definition.containsKey("oneOf") || definition.containsKey("if")) {
                return PathAssessment.COMPOSED_SCHEMA;
            }
            if (segment == null) {
                current = definition.get("items");
                continue;
            }
            Object properties = definition.get("properties");
            if (!(properties instanceof JSONObject)) {
                return PathAssessment.UNSPECIFIED_SCHEMA_SHAPE;
            }
            JSONObject fields = (JSONObject) properties;
            if (!fields.containsKey(segment)) {
                return PathAssessment.MISSING;
            }
            current = fields.get(segment);
        }
        return current instanceof JSONObject || current instanceof Boolean
                ? PathAssessment.DECLARED : PathAssessment.UNSPECIFIED_SCHEMA_SHAPE;
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

    @RequiredArgsConstructor
    private enum PathAssessment {
        DECLARED(null),
        MISSING(null),
        SCHEMA_MISSING(Reason.SCHEMA_MISSING),
        UNRESOLVED_SCHEMA(Reason.UNRESOLVED_SCHEMA),
        COMPOSED_SCHEMA(Reason.COMPOSED_SCHEMA),
        UNSPECIFIED_SCHEMA_SHAPE(Reason.UNSPECIFIED_SCHEMA_SHAPE);

        private final Reason reason;
    }

    @Value
    private static class ResponseSchema {
        JSONObject schema;
        PathAssessment issue;
    }

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

        SwaggerSyncImpact detail(EndpointDefinition endpoint, ResponseKey response) {
            return new SwaggerSyncImpact(row.getFlowId(), row.getFlowName(), row.getNodeId(),
                    endpoint.getHttpMethod(), endpoint.getEndpointPath(),
                    response == null ? null : response.status, response == null ? null : response.contentType,
                    fieldPath, assertionId == null ? SwaggerSyncImpact.ReferenceType.RESPONSE_VARIABLE
                            : SwaggerSyncImpact.ReferenceType.ASSERTION, variableName, assertionId);
        }

        UnassessedReference unassessed(EndpointDefinition endpoint, ResponseKey response,
                                       Reason reason, ContractVersion version) {
            return new UnassessedReference(detail(endpoint, response), reason, version);
        }
    }
}
