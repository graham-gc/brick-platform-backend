package apiworkflow.swagger;

import apiworkflow.entity.EndpointSchema;
import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class EndpointSchemaResolver {

    public Object resolve(List<EndpointSchema> schemas, String schemaRef) {
        if (!StringUtils.hasText(schemaRef)) {
            throw new IllegalArgumentException("Schema reference is required");
        }

        return resolveReference(schemaRef, schemasByRef(schemas), new ArrayDeque<String>());
    }

    public Object resolveValue(List<EndpointSchema> schemas, Object value) {
        return resolveValue(value, schemasByRef(schemas), new ArrayDeque<String>());
    }

    private Map<String, Object> schemasByRef(List<EndpointSchema> schemas) {
        Map<String, Object> schemasByRef = new LinkedHashMap<>();
        for (EndpointSchema schema : schemas) {
            if (schema == null || !StringUtils.hasText(schema.getSchemaRef())
                    || !StringUtils.hasText(schema.getSchemaJson())) {
                continue;
            }
            try {
                schemasByRef.put(schema.getSchemaRef(), JSON.parse(schema.getSchemaJson()));
            } catch (Exception e) {
                throw new IllegalArgumentException(
                        "Stored schema is not valid JSON: " + schema.getSchemaRef(), e);
            }
        }
        return schemasByRef;
    }

    private Object resolveReference(String schemaRef, Map<String, Object> schemasByRef,
                                    Deque<String> resolutionPath) {
        if (resolutionPath.contains(schemaRef)) {
            throw new IllegalStateException(
                    "Circular schema reference detected: " + formatCycle(resolutionPath, schemaRef));
        }
        if (!schemasByRef.containsKey(schemaRef)) {
            throw new IllegalArgumentException("Schema reference not found: " + schemaRef);
        }

        resolutionPath.addLast(schemaRef);
        try {
            return resolveValue(schemasByRef.get(schemaRef), schemasByRef, resolutionPath);
        } finally {
            resolutionPath.removeLast();
        }
    }

    private Object resolveValue(Object value, Map<String, Object> schemasByRef,
                                Deque<String> resolutionPath) {
        if (value instanceof JSONArray) {
            JSONArray resolved = new JSONArray();
            for (Object item : (JSONArray) value) {
                resolved.add(resolveValue(item, schemasByRef, resolutionPath));
            }
            return resolved;
        }
        if (!(value instanceof JSONObject)) {
            return value;
        }

        JSONObject source = (JSONObject) value;
        String nestedRef = source.getString("$ref");
        Object referenced = null;
        if (StringUtils.hasText(nestedRef)) {
            referenced = resolveReference(nestedRef, schemasByRef, resolutionPath);
            if (source.size() == 1) {
                return referenced;
            }
            if (!(referenced instanceof JSONObject)) {
                throw new IllegalArgumentException(
                        "Schema reference with sibling fields must resolve to an object: " + nestedRef);
            }
        }

        JSONObject resolved = new JSONObject(true);
        if (referenced instanceof JSONObject) {
            resolved.putAll((JSONObject) referenced);
        }
        for (Map.Entry<String, Object> entry : source.entrySet()) {
            if ("$ref".equals(entry.getKey())) {
                continue;
            }
            resolved.put(entry.getKey(), resolveValue(entry.getValue(), schemasByRef, resolutionPath));
        }
        return resolved;
    }

    private String formatCycle(Deque<String> resolutionPath, String repeatedRef) {
        StringBuilder result = new StringBuilder();
        for (String ref : resolutionPath) {
            if (result.length() > 0) {
                result.append(" -> ");
            }
            result.append(ref);
        }
        if (result.length() > 0) {
            result.append(" -> ");
        }
        return result.append(repeatedRef).toString();
    }
}
