package apiworkflow.execution;

import apiworkflow.entity.BrickFlowNode;
import apiworkflow.entity.BrickGlobalVariable;
import apiworkflow.mapper.BrickGlobalVariableMapper;
import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Arrays;
import java.util.Collections;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class GlobalVariableEngineTest {

    @Mock
    private BrickGlobalVariableMapper mapper;

    @Mock
    private DatabaseQueryVariableExecutor databaseQueryVariableExecutor;

    private GlobalVariableEngine engine;

    @BeforeEach
    void setUp() {
        engine = new GlobalVariableEngine();
        ReflectionTestUtils.setField(engine, "globalVariableMapper", mapper);
        ReflectionTestUtils.setField(engine, "databaseQueryVariableExecutor", databaseQueryVariableExecutor);
    }

    @Test
    void loadsEnabledVariablesByName() {
        BrickGlobalVariable baseUrl = variable("baseUrl", "string", "http://localhost:9090");
        when(mapper.selectEnabled()).thenReturn(Arrays.asList(baseUrl));

        Map<String, BrickGlobalVariable> result = engine.loadEnabledVariables();

        assertEquals(baseUrl, result.get("baseUrl"));
    }

    @Test
    void resolvesStaticVariablesAcrossEveryRequestArea() {
        Map<String, BrickGlobalVariable> variables = variables(
                variable("tenant", "string", "demo"),
                variable("limit", "number", 25),
                variable("enabled", "boolean", true));
        BrickFlowNode node = new BrickFlowNode();
        node.setPayloadJson("{\"tenant\":\"${{tenant}}\",\"enabled\":\"${{enabled}}\"}");
        node.setQueryParamsJson("{\"limit\":\"${{limit}}\"}");
        node.setPathVarsJson("{\"tenantId\":\"prefix-${{tenant}}\"}");
        node.setHeadersJson("{\"X-Tenant\":\"${{tenant}}\"}");

        engine.resolveNode(node, variables);

        JSONObject body = JSON.parseObject(node.getPayloadJson());
        assertEquals("demo", body.getString("tenant"));
        assertEquals(Boolean.TRUE, body.getBoolean("enabled"));
        assertEquals(25, JSON.parseObject(node.getQueryParamsJson()).getIntValue("limit"));
        assertEquals("prefix-demo", JSON.parseObject(node.getPathVarsJson()).getString("tenantId"));
        assertEquals("demo", JSON.parseObject(node.getHeadersJson()).getString("X-Tenant"));
    }

    @Test
    void keepsStructuredValueWhenPlaceholderOccupiesWholeJsonValue() {
        JSONObject customer = new JSONObject(true);
        customer.put("id", 7);
        Map<String, BrickGlobalVariable> variables = variables(
                variable("customer", "object", customer));

        String result = engine.resolveDocument(
                "{\"customer\":\"${{customer}}\"}", variables, "request body");

        assertEquals(7, JSON.parseObject(result).getJSONObject("customer").getIntValue("id"));
    }

    @Test
    void rejectsMissingAndUnsupportedVariables() {
        IllegalStateException missing = assertThrows(IllegalStateException.class,
                () -> engine.resolveDocument("${{missing}}", Collections.emptyMap(), "request body"));
        assertEquals("Global variable is not enabled or does not exist: missing", missing.getMessage());

        BrickGlobalVariable dynamic = variable("computedValue", "string", "ignored");
        dynamic.setType("DYNAMIC");
        IllegalStateException unsupported = assertThrows(IllegalStateException.class,
                () -> engine.resolveDocument("${{computedValue}}", variables(dynamic), "body"));
        assertEquals("Global variable type is not executable yet: computedValue (DYNAMIC)", unsupported.getMessage());
    }

    @Test
    void resolvesTokenAsAStoredValueWithoutAuthenticationSideEffects() {
        BrickGlobalVariable cookie = variable("sessionCookie", "string", "SESSION=demo-session");
        cookie.setType("TOKEN");

        String result = engine.resolveDocument(
                "{\"Cookie\":\"${{sessionCookie}}\"}", variables(cookie), "headers");

        assertEquals("SESSION=demo-session", JSON.parseObject(result).getString("Cookie"));
    }

    @Test
    void executesJavascriptFunctionWithOneOrMultipleArguments() {
        BrickGlobalVariable randomString = function(
                "randomString", "string",
                "return Array(length + 1).join('x');",
                "[{\"name\":\"length\",\"type\":\"number\"}]");
        BrickGlobalVariable labelledCount = function(
                "labelledCount", "string",
                "return prefix + '-' + count;",
                "[{\"name\":\"prefix\",\"type\":\"string\"},{\"name\":\"count\",\"type\":\"number\"}]");

        String result = engine.resolveDocument(
                "{\"code\":\"${{randomString(8)}}\",\"label\":\"${{labelledCount(\"item\",3)}}\"}",
                variables(randomString, labelledCount), "request body");

        JSONObject parsed = JSON.parseObject(result);
        assertEquals("xxxxxxxx", parsed.getString("code"));
        assertEquals("item-3", parsed.getString("label"));
    }

    @Test
    void rejectsWrongFunctionArityAndJavaHostAccess() {
        BrickGlobalVariable function = function(
                "makeId", "string", "return prefix;",
                "[{\"name\":\"prefix\",\"type\":\"string\"}]");
        IllegalStateException arity = assertThrows(IllegalStateException.class,
                () -> engine.resolveDocument("${{makeId()}}", variables(function), "body"));
        assertEquals("FUNCTION makeId expects 1 argument(s) but received 0", arity.getMessage());

        BrickGlobalVariable unsafe = function(
                "readHost", "string", "return Java.type('java.lang.System').getenv();", "[]");
        IllegalStateException blocked = assertThrows(IllegalStateException.class,
                () -> engine.resolveDocument("${{readHost}}", variables(unsafe), "body"));
        assertEquals(
                "FUNCTION JavaScript contains a disallowed host-access identifier: readHost",
                blocked.getMessage());
    }

    @Test
    void resolvesDatabaseQueryThroughBackendExecutor() {
        BrickGlobalVariable query = variable("orderStatus", "string", "ignored");
        query.setType("DATABASE_QUERY");
        query.setParamSchema("[{\"name\":\"orderId\",\"type\":\"string\"}]");
        org.mockito.Mockito.when(databaseQueryVariableExecutor.execute(
                org.mockito.ArgumentMatchers.eq(query), org.mockito.ArgumentMatchers.anyList()))
                .thenReturn("PAID");

        String result = engine.resolveDocument(
                "{\"status\":\"${{orderStatus(\"ord-1\")}}\"}", variables(query), "body");

        assertEquals("PAID", JSON.parseObject(result).getString("status"));
    }

    private Map<String, BrickGlobalVariable> variables(BrickGlobalVariable... variables) {
        java.util.LinkedHashMap<String, BrickGlobalVariable> result = new java.util.LinkedHashMap<>();
        for (BrickGlobalVariable variable : variables) {
            result.put(variable.getName(), variable);
        }
        return result;
    }

    private BrickGlobalVariable variable(String name, String dataType, Object value) {
        BrickGlobalVariable variable = new BrickGlobalVariable();
        variable.setName(name);
        variable.setType("STATIC");
        variable.setDataType(dataType);
        JSONObject config = new JSONObject(true);
        config.put("value", value);
        variable.setConfig(config.toJSONString());
        variable.setIsEnabled(1);
        return variable;
    }

    private BrickGlobalVariable function(String name, String dataType, String script,
                                         String paramSchema) {
        BrickGlobalVariable variable = new BrickGlobalVariable();
        variable.setName(name);
        variable.setType("FUNCTION");
        variable.setDataType(dataType);
        JSONObject config = new JSONObject(true);
        config.put("script", script);
        variable.setConfig(config.toJSONString());
        variable.setParamSchema(paramSchema);
        variable.setIsEnabled(1);
        return variable;
    }
}
