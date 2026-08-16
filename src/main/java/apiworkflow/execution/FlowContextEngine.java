package apiworkflow.execution;

import apiworkflow.entity.BrickFlowNode;
import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.jayway.jsonpath.DocumentContext;
import com.jayway.jsonpath.InvalidPathException;
import com.jayway.jsonpath.JsonPath;
import com.jayway.jsonpath.PathNotFoundException;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Component
public class FlowContextEngine {

    private static final Object MISSING = new Object();

    public void validateConfiguration(List<? extends BrickFlowNode> nodes) {
        Set<String> names = new HashSet<>();
        Set<String> referencedNames = new HashSet<>();
        if (nodes == null) {
            return;
        }
        for (BrickFlowNode node : nodes) {
            for (JSONObject definition : objects(node.getResponseVariablesJson(), "response variables")) {
                String name = requiredText(definition, "name", "Response variable name is required");
                String responsePath = requiredText(
                        definition, "responsePath", "Response field is required for variable " + name);
                if (!name.matches("[A-Za-z_][A-Za-z0-9_]*")) {
                    throw new IllegalArgumentException("Invalid flow variable name: " + name);
                }
                if (!names.add(name)) {
                    throw new IllegalArgumentException("Duplicate flow variable name: " + name);
                }
                validateResponsePath(responsePath, name);
                resultMode(definition, name);
            }
            for (JSONObject binding : objects(node.getRequestVariableBindingsJson(), "request variable bindings")) {
                referencedNames.add(requiredText(binding, "variableName", "Binding variable is required"));
                String targetType = requiredText(binding, "targetType", "Binding target type is required");
                requiredText(binding, "targetPath", "Binding target field is required");
                if (!"BODY".equals(targetType) && !"QUERY".equals(targetType) && !"PATH".equals(targetType)) {
                    throw new IllegalArgumentException("Unsupported variable binding target: " + targetType);
                }
            }
        }
        for (String referencedName : referencedNames) {
            if (!names.contains(referencedName)) {
                throw new IllegalArgumentException("Flow variable is not defined: " + referencedName);
            }
        }
    }

    public void applyBindings(BrickFlowNode node, Map<String, Object> context) {
        for (JSONObject binding : objects(node.getRequestVariableBindingsJson(), "request variable bindings")) {
            String variableName = requiredText(binding, "variableName", "Binding variable is required");
            if (!context.containsKey(variableName)) {
                throw new IllegalStateException("Flow variable is not available yet: " + variableName);
            }
            String targetType = requiredText(binding, "targetType", "Binding target type is required");
            String targetPath = requiredText(binding, "targetPath", "Binding target field is required");
            Object value = context.get(variableName);
            if ("BODY".equals(targetType)) {
                node.setPayloadJson(writeJsonPath(node.getPayloadJson(), targetPath, value, false));
            } else if ("QUERY".equals(targetType)) {
                node.setQueryParamsJson(writeJsonPath(node.getQueryParamsJson(), targetPath, value, true));
            } else if ("PATH".equals(targetType)) {
                node.setPathVarsJson(writeJsonPath(node.getPathVarsJson(), targetPath, value, true));
            } else {
                throw new IllegalArgumentException("Unsupported variable binding target: " + targetType);
            }
        }
    }

    public void captureResponseVariables(BrickFlowNode node, String responseBody,
                                         Map<String, Object> context) {
        List<JSONObject> definitions = objects(node.getResponseVariablesJson(), "response variables");
        if (definitions.isEmpty()) {
            return;
        }
        final DocumentContext response;
        try {
            response = JsonPath.parse(responseBody);
        } catch (Exception e) {
            throw new IllegalStateException("Cannot extract flow variables from a non-JSON response", e);
        }
        Map<String, Object> extracted = new LinkedHashMap<>();
        for (JSONObject definition : definitions) {
            String name = requiredText(definition, "name", "Response variable name is required");
            String path = requiredText(definition, "responsePath", "Response field is required for variable " + name);
            String resultMode = resultMode(definition, name);
            Object value = readResponsePath(response, path, name, resultMode);
            extracted.put(name, value);
        }
        context.putAll(extracted);
    }

    private void validateResponsePath(String path, String variableName) {
        if (!path.startsWith("$")) {
            throw new IllegalArgumentException(
                    "Response JSONPath must start with $ for flow variable " + variableName);
        }
        try {
            JsonPath.compile(path);
        } catch (InvalidPathException e) {
            throw new IllegalArgumentException(
                    "Invalid response JSONPath for flow variable " + variableName + ": " + path, e);
        }
    }

    private String resultMode(JSONObject definition, String variableName) {
        String mode = definition.getString("resultMode");
        if (!StringUtils.hasText(mode)) {
            return "SINGLE";
        }
        String normalized = mode.trim().toUpperCase();
        if (!"SINGLE".equals(normalized) && !"FIRST".equals(normalized) && !"LIST".equals(normalized)) {
            throw new IllegalArgumentException(
                    "Unsupported result mode for flow variable " + variableName + ": " + mode);
        }
        return normalized;
    }

    private Object readResponsePath(DocumentContext response, String path, String variableName,
                                    String resultMode) {
        final Object value;
        try {
            value = response.read(path);
        } catch (PathNotFoundException e) {
            throw new IllegalStateException(
                    "Response JSONPath " + path + " did not match a value for flow variable " + variableName, e);
        } catch (InvalidPathException e) {
            throw new IllegalStateException(
                    "Invalid response JSONPath for flow variable " + variableName + ": " + path, e);
        }

        if ("LIST".equals(resultMode)) {
            if (!(value instanceof Collection)) {
                throw new IllegalStateException(
                        "Response JSONPath " + path + " must return a list for flow variable " + variableName);
            }
            return value;
        }

        if (value instanceof Collection) {
            Collection<?> values = (Collection<?>) value;
            if (values.isEmpty()) {
                throw new IllegalStateException(
                        "Response JSONPath " + path + " did not match a value for flow variable " + variableName);
            }
            if ("FIRST".equals(resultMode)) {
                return values.iterator().next();
            }
            if (values.size() != 1) {
                throw new IllegalStateException(
                        "Response JSONPath " + path + " returned " + values.size()
                                + " values for SINGLE flow variable " + variableName);
            }
            return values.iterator().next();
        }
        return value;
    }

    private String writeJsonPath(String json, String path, Object value, boolean rootObject) {
        Object root;
        try {
            root = StringUtils.hasText(json) ? JSON.parse(json) : new JSONObject(true);
        } catch (Exception e) {
            throw new IllegalArgumentException("Cannot bind a flow variable into invalid request JSON", e);
        }
        if (rootObject && !(root instanceof JSONObject)) {
            throw new IllegalArgumentException("Request parameters must be a JSON object");
        }
        List<PathToken> tokens = parsePath(path);
        if (tokens.isEmpty()) {
            return JSON.toJSONString(value);
        }
        Object current = root;
        for (int index = 0; index < tokens.size() - 1; index++) {
            current = child(current, tokens.get(index));
            if (current == MISSING) {
                throw new IllegalArgumentException("Request target field does not exist: " + path);
            }
        }
        setChild(current, tokens.get(tokens.size() - 1), value, path);
        return JSON.toJSONString(root);
    }

    private Object child(Object current, PathToken token) {
        if (token.index != null) {
            if (!(current instanceof JSONArray) || token.index < 0 || token.index >= ((JSONArray) current).size()) {
                return MISSING;
            }
            return ((JSONArray) current).get(token.index);
        }
        if (!(current instanceof JSONObject) || !((JSONObject) current).containsKey(token.key)) {
            return MISSING;
        }
        return ((JSONObject) current).get(token.key);
    }

    private void setChild(Object current, PathToken token, Object value, String fullPath) {
        if (token.index != null) {
            if (!(current instanceof JSONArray) || token.index < 0 || token.index >= ((JSONArray) current).size()) {
                throw new IllegalArgumentException("Request target field does not exist: " + fullPath);
            }
            ((JSONArray) current).set(token.index, value);
            return;
        }
        if (!(current instanceof JSONObject)) {
            throw new IllegalArgumentException("Request target field does not exist: " + fullPath);
        }
        ((JSONObject) current).put(token.key, value);
    }

    private List<PathToken> parsePath(String path) {
        String normalized = path == null ? "" : path.trim();
        if (normalized.startsWith("$")) {
            normalized = normalized.substring(1);
        }
        if (normalized.startsWith(".")) {
            normalized = normalized.substring(1);
        }
        java.util.ArrayList<PathToken> result = new java.util.ArrayList<>();
        int start = 0;
        for (int index = 0; index <= normalized.length(); index++) {
            if (index == normalized.length() || normalized.charAt(index) == '.') {
                addSegment(result, normalized.substring(start, index));
                start = index + 1;
            }
        }
        return result;
    }

    private void addSegment(List<PathToken> target, String segment) {
        if (!StringUtils.hasText(segment)) {
            return;
        }
        int bracket = segment.indexOf('[');
        String key = bracket < 0 ? segment : segment.substring(0, bracket);
        if (StringUtils.hasText(key)) {
            target.add(PathToken.key(key));
        }
        while (bracket >= 0) {
            int close = segment.indexOf(']', bracket);
            if (close < 0) {
                throw new IllegalArgumentException("Invalid field path: " + segment);
            }
            target.add(PathToken.index(Integer.parseInt(segment.substring(bracket + 1, close))));
            bracket = segment.indexOf('[', close + 1);
        }
    }

    private List<JSONObject> objects(String json, String label) {
        java.util.ArrayList<JSONObject> result = new java.util.ArrayList<>();
        if (!StringUtils.hasText(json)) {
            return result;
        }
        try {
            JSONArray array = JSON.parseArray(json);
            for (Object item : array) {
                if (!(item instanceof JSONObject)) {
                    throw new IllegalArgumentException(label + " must contain JSON objects");
                }
                result.add((JSONObject) item);
            }
            return result;
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid " + label + " JSON", e);
        }
    }

    private String requiredText(JSONObject object, String field, String message) {
        String value = object.getString(field);
        if (!StringUtils.hasText(value)) {
            throw new IllegalArgumentException(message);
        }
        return value.trim();
    }

    private static final class PathToken {
        private final String key;
        private final Integer index;

        private PathToken(String key, Integer index) {
            this.key = key;
            this.index = index;
        }

        private static PathToken key(String key) {
            return new PathToken(key, null);
        }

        private static PathToken index(int index) {
            return new PathToken(null, index);
        }
    }
}
