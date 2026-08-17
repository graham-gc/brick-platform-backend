package apiworkflow.execution;

import apiworkflow.entity.BrickFlowNode;
import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class FlowContextEngineTest {

    private final FlowContextEngine engine = new FlowContextEngine();

    @Test
    void extractsResponseFieldsAndBindsThemWithTheirOriginalTypes() {
        BrickFlowNode source = new BrickFlowNode();
        source.setResponseVariablesJson("[{\"name\":\"orderId\",\"responsePath\":\"$.data.id\"},"
                + "{\"name\":\"total\",\"responsePath\":\"$.data.total\"}]");
        Map<String, Object> context = new LinkedHashMap<>();

        engine.captureResponseVariables(source,
                "{\"data\":{\"id\":\"ord-2001\",\"total\":99.8}}", context);

        BrickFlowNode target = new BrickFlowNode();
        target.setPayloadJson("{\"payment\":{\"orderId\":\"\",\"amount\":0}}");
        target.setPathVarsJson("{\"orderId\":\"\"}");
        target.setQueryParamsJson("{}");
        target.setRequestVariableBindingsJson("["
                + "{\"variableName\":\"orderId\",\"targetType\":\"PATH\",\"targetPath\":\"orderId\"},"
                + "{\"variableName\":\"total\",\"targetType\":\"BODY\",\"targetPath\":\"$.payment.amount\"}]");

        engine.applyBindings(target, context);

        assertEquals("ord-2001", JSON.parseObject(target.getPathVarsJson()).getString("orderId"));
        assertEquals(99.8D, JSON.parseObject(target.getPayloadJson())
                .getJSONObject("payment").getDoubleValue("amount"), 0.001D);
    }

    @Test
    void rejectsDuplicateVariableNamesAcrossTheFlow() {
        BrickFlowNode first = new BrickFlowNode();
        first.setResponseVariablesJson("[{\"name\":\"id\",\"responsePath\":\"$.id\"}]");
        BrickFlowNode second = new BrickFlowNode();
        second.setResponseVariablesJson("[{\"name\":\"id\",\"responsePath\":\"$.data.id\"}]");

        assertThrows(IllegalArgumentException.class,
                () -> engine.validateConfiguration(Arrays.asList(first, second)));
    }

    @Test
    void failsClearlyWhenAReferencedVariableIsNotAvailable() {
        BrickFlowNode target = new BrickFlowNode();
        target.setRequestVariableBindingsJson("[{\"variableName\":\"missing\","
                + "\"targetType\":\"QUERY\",\"targetPath\":\"id\"}]");

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> engine.applyBindings(target, new LinkedHashMap<String, Object>()));

        assertEquals("Flow variable is not available yet: missing", error.getMessage());
    }

    @Test
    void extractsAnArrayItemByAConfiguredIndex() {
        BrickFlowNode source = new BrickFlowNode();
        source.setResponseVariablesJson("[{\"name\":\"productId\","
                + "\"responsePath\":\"$.data.items[1].id\",\"resultMode\":\"SINGLE\"}]");
        Map<String, Object> context = new LinkedHashMap<>();

        engine.captureResponseVariables(source,
                "{\"data\":{\"items\":[{\"id\":\"prd-1\"},{\"id\":\"prd-2\"}]}}", context);

        assertEquals("prd-2", context.get("productId"));
    }

    @Test
    void extractsTheFirstArrayItemMatchingAJsonPathFilter() {
        BrickFlowNode source = new BrickFlowNode();
        source.setResponseVariablesJson("[{\"name\":\"productId\","
                + "\"responsePath\":\"$.data.items[?(@.stock > 0)].id\","
                + "\"resultMode\":\"FIRST\"}]");
        Map<String, Object> context = new LinkedHashMap<>();

        engine.captureResponseVariables(source,
                "{\"data\":{\"items\":[{\"id\":\"prd-1\",\"stock\":0},"
                        + "{\"id\":\"prd-2\",\"stock\":8}]}}", context);

        assertEquals("prd-2", context.get("productId"));
    }

    @Test
    void extractsAllArrayMatchesWhenTheVariableIsAList() {
        BrickFlowNode source = new BrickFlowNode();
        source.setResponseVariablesJson("[{\"name\":\"productIds\","
                + "\"responsePath\":\"$.data.items[*].id\",\"resultMode\":\"LIST\"}]");
        Map<String, Object> context = new LinkedHashMap<>();

        engine.captureResponseVariables(source,
                "{\"data\":{\"items\":[{\"id\":\"prd-1\"},{\"id\":\"prd-2\"}]}}", context);

        assertEquals(Arrays.asList("prd-1", "prd-2"), context.get("productIds"));
    }

    @Test
    void rejectsAnIndefiniteExpressionForASingleVariableWhenItReturnsSeveralValues() {
        BrickFlowNode source = new BrickFlowNode();
        source.setResponseVariablesJson("[{\"name\":\"productId\","
                + "\"responsePath\":\"$.data.items[*].id\",\"resultMode\":\"SINGLE\"}]");

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> engine.captureResponseVariables(source,
                        "{\"data\":{\"items\":[{\"id\":\"prd-1\"},{\"id\":\"prd-2\"}]}}",
                        new LinkedHashMap<String, Object>()));

        assertEquals("Response JSONPath $.data.items[*].id returned 2 values for SINGLE flow variable productId",
                error.getMessage());
    }

    @Test
    void rejectsInvalidJsonPathDuringFlowValidation() {
        BrickFlowNode source = new BrickFlowNode();
        source.setResponseVariablesJson("[{\"name\":\"productId\",\"responsePath\":\"$.data.items[?(\"}]");

        assertThrows(IllegalArgumentException.class,
                () -> engine.validateConfiguration(Arrays.asList(source)));
    }

    @Test
    void bindsAScalarFlowVariableIntoANodeHeaderTemplate() {
        BrickFlowNode target = new BrickFlowNode();
        target.setHeadersJson("{\"Accept\":\"application/json\"}");
        target.setRequestVariableBindingsJson("[{\"variableName\":\"accessToken\"," 
                + "\"targetType\":\"HEADER\",\"targetPath\":\"Authorization\"," 
                + "\"valueTemplate\":\"Bearer {{value}}\"}]");
        Map<String, Object> context = new LinkedHashMap<>();
        context.put("accessToken", "mock-access-token");

        engine.applyBindings(target, context);

        JSONObject headers = JSON.parseObject(target.getHeadersJson());
        assertEquals("application/json", headers.getString("Accept"));
        assertEquals("Bearer mock-access-token", headers.getString("Authorization"));
    }

    @Test
    void activatesADynamicFlowHeaderOnlyAfterItsVariableExists() {
        String headers = "{\"Accept\":\"application/json\"," 
                + "\"Authorization\":\"Bearer {{accessToken}}\"}";
        Map<String, Object> context = new LinkedHashMap<>();

        JSONObject beforeLogin = JSON.parseObject(engine.resolveSharedHeaders(headers, context));
        assertEquals("application/json", beforeLogin.getString("Accept"));
        assertEquals(false, beforeLogin.containsKey("Authorization"));

        context.put("accessToken", "mock-access-token");
        JSONObject afterLogin = JSON.parseObject(engine.resolveSharedHeaders(headers, context));
        assertEquals("Bearer mock-access-token", afterLogin.getString("Authorization"));
    }

    @Test
    void rejectsAHeaderBindingTemplateWithoutTheValuePlaceholder() {
        BrickFlowNode source = new BrickFlowNode();
        source.setResponseVariablesJson("[{\"name\":\"accessToken\",\"responsePath\":\"$.token\"}]");
        BrickFlowNode target = new BrickFlowNode();
        target.setRequestVariableBindingsJson("[{\"variableName\":\"accessToken\"," 
                + "\"targetType\":\"HEADER\",\"targetPath\":\"Authorization\"," 
                + "\"valueTemplate\":\"Bearer token\"}]");

        assertThrows(IllegalArgumentException.class,
                () -> engine.validateConfiguration(Arrays.asList(source, target)));
    }

    @Test
    void rejectsAFlowHeaderThatReferencesAnUndefinedVariable() {
        BrickFlowNode source = new BrickFlowNode();
        source.setResponseVariablesJson("[{\"name\":\"accessToken\",\"responsePath\":\"$.token\"}]");

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> engine.validateSharedHeaderVariables(
                        "{\"Authorization\":\"Bearer {{acessToken}}\"}",
                        Arrays.asList(source)));

        assertEquals("Flow header Authorization references an undefined flow variable: acessToken",
                error.getMessage());
    }
}
