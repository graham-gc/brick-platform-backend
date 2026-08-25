package apiworkflow.execution;

import apiworkflow.entity.BrickFlowNode;
import apiworkflow.entity.BrickGlobalVariable;
import apiworkflow.mapper.BrickGlobalVariableMapper;
import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import jdk.nashorn.api.scripting.NashornScriptEngineFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.script.ScriptEngine;

@Component
public class GlobalVariableEngine {

    private static final Pattern GLOBAL_VARIABLE_TEMPLATE = Pattern.compile(
            "\\$\\{\\{\\s*([A-Za-z_][A-Za-z0-9_]*)(?:\\s*\\((.*?)\\))?\\s*}}"
    );
    private static final Pattern PARAMETER_NAME = Pattern.compile("^[A-Za-z_][A-Za-z0-9_]*$");
    private static final Pattern UNSAFE_JAVASCRIPT = Pattern.compile(
            "(?i)\\b(?:Java|Packages|java|javax|org|com|load|loadWithNewGlobal|exit|quit)\\b");
    private static final int MAX_FUNCTION_SCRIPT_LENGTH = 20_000;
    private static final long FUNCTION_TIMEOUT_MILLIS = 1_000L;

    @Autowired
    private BrickGlobalVariableMapper globalVariableMapper;

    @Autowired
    private DatabaseQueryVariableExecutor databaseQueryVariableExecutor;

    public Map<String, BrickGlobalVariable> loadEnabledVariables() {
        List<BrickGlobalVariable> variables = globalVariableMapper.selectEnabled();
        if (variables == null || variables.isEmpty()) {
            return Collections.emptyMap();
        }
        Map<String, BrickGlobalVariable> result = new LinkedHashMap<>();
        for (BrickGlobalVariable variable : variables) {
            if (variable != null && StringUtils.hasText(variable.getName())) {
                result.put(variable.getName(), variable);
            }
        }
        return result;
    }

    public void resolveNode(BrickFlowNode node, Map<String, BrickGlobalVariable> variables) {
        if (node == null) {
            return;
        }
        node.setPayloadJson(resolveDocument(node.getPayloadJson(), variables, "request body"));
        node.setQueryParamsJson(resolveDocument(node.getQueryParamsJson(), variables, "query parameters"));
        node.setPathVarsJson(resolveDocument(node.getPathVarsJson(), variables, "path parameters"));
        node.setHeadersJson(resolveDocument(node.getHeadersJson(), variables, "node headers"));
    }

    public String resolveDocument(String source, Map<String, BrickGlobalVariable> variables, String label) {
        if (!StringUtils.hasText(source) || !GLOBAL_VARIABLE_TEMPLATE.matcher(source).find()) {
            return source;
        }
        try {
            Object parsed = JSON.parse(source);
            Object resolved = resolveJsonValue(parsed, variables);
            return JSON.toJSONString(resolved);
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception ignored) {
            return resolveString(source, variables);
        }
    }

    private Object resolveJsonValue(Object value, Map<String, BrickGlobalVariable> variables) {
        if (value instanceof JSONObject) {
            JSONObject source = (JSONObject) value;
            JSONObject resolved = new JSONObject(true);
            for (Map.Entry<String, Object> entry : source.entrySet()) {
                resolved.put(entry.getKey(), resolveJsonValue(entry.getValue(), variables));
            }
            return resolved;
        }
        if (value instanceof JSONArray) {
            JSONArray resolved = new JSONArray();
            for (Object item : (JSONArray) value) {
                resolved.add(resolveJsonValue(item, variables));
            }
            return resolved;
        }
        if (!(value instanceof String)) {
            return value;
        }

        String text = (String) value;
        Matcher exact = GLOBAL_VARIABLE_TEMPLATE.matcher(text);
        if (exact.matches()) {
            return resolveValue(exact.group(1), exact.group(2), variables);
        }
        return resolveString(text, variables);
    }

    private String resolveString(String source, Map<String, BrickGlobalVariable> variables) {
        Matcher matcher = GLOBAL_VARIABLE_TEMPLATE.matcher(source);
        StringBuffer rendered = new StringBuffer();
        while (matcher.find()) {
            Object value = resolveValue(matcher.group(1), matcher.group(2), variables);
            matcher.appendReplacement(rendered, Matcher.quoteReplacement(stringValue(value)));
        }
        matcher.appendTail(rendered);
        return rendered.toString();
    }

    private Object resolveValue(String name, String argumentText,
                                Map<String, BrickGlobalVariable> variables) {
        BrickGlobalVariable variable = variables == null ? null : variables.get(name);
        if (variable == null) {
            throw new IllegalStateException("Global variable is not enabled or does not exist: " + name);
        }
        String type = variable.getType() == null ? "" : variable.getType().trim().toUpperCase();
        if ("STATIC".equals(type) || "TOKEN".equals(type)) {
            if (StringUtils.hasText(argumentText)) {
                throw new IllegalStateException("Stored global variable does not accept arguments: " + name);
            }
            return storedValue(variable);
        }
        if ("FUNCTION".equals(type)) {
            return functionValue(variable, parseArguments(argumentText));
        }
        if ("DATABASE_QUERY".equals(type)) {
            Object value = databaseQueryVariableExecutor.execute(variable, parseArguments(argumentText));
            return coerceValue(value, variable.getDataType(), variable.getName());
        }
        throw new IllegalStateException(
                "Global variable type is not executable yet: " + name + " (" + type + ")");
    }

    private Object storedValue(BrickGlobalVariable variable) {
        if (!StringUtils.hasText(variable.getConfig())) {
            throw new IllegalStateException(
                    variable.getType() + " global variable has no configuration: " + variable.getName());
        }
        final Object parsed;
        try {
            parsed = JSON.parse(variable.getConfig());
        } catch (Exception e) {
            throw new IllegalStateException(
                    variable.getType() + " global variable has invalid configuration: " + variable.getName(), e);
        }
        if (!(parsed instanceof JSONObject) || !((JSONObject) parsed).containsKey("value")) {
            throw new IllegalStateException(
                    variable.getType() + " global variable configuration must contain value: " + variable.getName());
        }
        return coerceValue(((JSONObject) parsed).get("value"), variable.getDataType(), variable.getName());
    }

    private Object coerceValue(Object value, String dataType, String name) {
        String normalized = StringUtils.hasText(dataType) ? dataType.trim().toLowerCase() : "string";
        try {
            if ("string".equals(normalized)) {
                return value == null ? "" : String.valueOf(value);
            }
            if ("number".equals(normalized)) {
                return value instanceof Number ? value : new BigDecimal(String.valueOf(value));
            }
            if ("boolean".equals(normalized)) {
                if (value instanceof Boolean) {
                    return value;
                }
                String text = String.valueOf(value);
                if (!"true".equalsIgnoreCase(text) && !"false".equalsIgnoreCase(text)) {
                    throw new IllegalArgumentException("not a boolean");
                }
                return Boolean.valueOf(text);
            }
            if ("object".equals(normalized) && value instanceof JSONObject) {
                return value;
            }
            if ("array".equals(normalized) && value instanceof JSONArray) {
                return value;
            }
        } catch (Exception e) {
            throw new IllegalStateException(
                    "Global variable value does not match data type for " + name, e);
        }
        throw new IllegalStateException(
                "Global variable value does not match data type for " + name);
    }

    private Object functionValue(final BrickGlobalVariable variable, final List<Object> arguments) {
        final JSONObject config = objectConfig(variable, "FUNCTION");
        final String script = config.getString("script");
        if (!StringUtils.hasText(script)) {
            throw new IllegalStateException("FUNCTION global variable has no JavaScript: " + variable.getName());
        }
        if (script.length() > MAX_FUNCTION_SCRIPT_LENGTH) {
            throw new IllegalStateException("FUNCTION JavaScript is too large: " + variable.getName());
        }
        if (UNSAFE_JAVASCRIPT.matcher(script).find()) {
            throw new IllegalStateException(
                    "FUNCTION JavaScript contains a disallowed host-access identifier: " + variable.getName());
        }
        final List<String> parameterNames = parameterNames(variable);
        if (arguments.size() != parameterNames.size()) {
            throw new IllegalStateException("FUNCTION " + variable.getName() + " expects "
                    + parameterNames.size() + " argument(s) but received " + arguments.size());
        }

        ExecutorService executor = Executors.newSingleThreadExecutor(new ThreadFactory() {
            @Override
            public Thread newThread(Runnable runnable) {
                Thread thread = new Thread(runnable, "brick-global-function-" + variable.getName());
                thread.setDaemon(true);
                return thread;
            }
        });
        Future<Object> future = executor.submit(new Callable<Object>() {
            @Override
            public Object call() throws Exception {
                NashornScriptEngineFactory factory = new NashornScriptEngineFactory();
                ScriptEngine engine = factory.getScriptEngine("--no-java", "--no-syntax-extensions");
                String function = "var __brickFunction = function("
                        + String.join(",", parameterNames) + ") { \"use strict\";\n"
                        + script + "\n};";
                engine.eval(function);
                engine.put("__brickArgumentsJson", JSON.toJSONString(arguments));
                engine.eval("var __brickResult = __brickFunction.apply(null, JSON.parse(__brickArgumentsJson));");
                Object json = engine.eval("JSON.stringify(__brickResult)");
                if (json == null) {
                    throw new IllegalStateException("FUNCTION returned undefined: " + variable.getName());
                }
                return JSON.parse(String.valueOf(json));
            }
        });
        try {
            Object result = future.get(FUNCTION_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS);
            return coerceValue(result, variable.getDataType(), variable.getName());
        } catch (TimeoutException e) {
            future.cancel(true);
            throw new IllegalStateException("FUNCTION execution timed out: " + variable.getName(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("FUNCTION execution was interrupted: " + variable.getName(), e);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause() == null ? e : e.getCause();
            throw new IllegalStateException("FUNCTION execution failed for " + variable.getName()
                    + ": " + (cause.getMessage() == null ? cause.getClass().getSimpleName() : cause.getMessage()), cause);
        } finally {
            executor.shutdownNow();
        }
    }

    private JSONObject objectConfig(BrickGlobalVariable variable, String type) {
        if (!StringUtils.hasText(variable.getConfig())) {
            throw new IllegalStateException(type + " global variable has no configuration: " + variable.getName());
        }
        try {
            Object parsed = JSON.parse(variable.getConfig());
            if (parsed instanceof JSONObject) {
                return (JSONObject) parsed;
            }
        } catch (Exception ignored) {
            // The stable public error below deliberately excludes parser internals.
        }
        throw new IllegalStateException(type + " global variable has invalid configuration: " + variable.getName());
    }

    private List<String> parameterNames(BrickGlobalVariable variable) {
        if (!StringUtils.hasText(variable.getParamSchema())) {
            return Collections.emptyList();
        }
        final JSONArray parameters;
        try {
            parameters = JSON.parseArray(variable.getParamSchema());
        } catch (Exception e) {
            throw new IllegalStateException(
                    "FUNCTION has invalid parameter schema: " + variable.getName(), e);
        }
        List<String> names = new ArrayList<>();
        Set<String> uniqueNames = new HashSet<>();
        for (Object item : parameters) {
            if (!(item instanceof JSONObject)) {
                throw new IllegalStateException(
                        "FUNCTION has invalid parameter schema: " + variable.getName());
            }
            String name = ((JSONObject) item).getString("name");
            if (!StringUtils.hasText(name) || !PARAMETER_NAME.matcher(name).matches()
                    || !uniqueNames.add(name)) {
                throw new IllegalStateException(
                        "FUNCTION has invalid or duplicate parameter name: " + variable.getName());
            }
            names.add(name);
        }
        return names;
    }

    private List<Object> parseArguments(String argumentText) {
        if (!StringUtils.hasText(argumentText)) {
            return Collections.emptyList();
        }
        try {
            JSONArray values = JSON.parseArray("[" + argumentText + "]");
            return new ArrayList<Object>(values);
        } catch (Exception e) {
            throw new IllegalStateException("Invalid global function arguments: " + argumentText, e);
        }
    }

    private String stringValue(Object value) {
        if (value == null) {
            return "";
        }
        if (value instanceof JSONObject || value instanceof JSONArray) {
            return JSON.toJSONString(value);
        }
        return String.valueOf(value);
    }
}
