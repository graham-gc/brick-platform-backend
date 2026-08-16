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
}
