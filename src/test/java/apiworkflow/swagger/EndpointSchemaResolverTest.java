package apiworkflow.swagger;

import apiworkflow.entity.EndpointSchema;
import com.alibaba.fastjson.JSONObject;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EndpointSchemaResolverTest {

    private final EndpointSchemaResolver resolver = new EndpointSchemaResolver();

    @Test
    void resolvesNestedReferencesAtEveryDepth() {
        EndpointSchema address = schema("#/components/schemas/Address",
                "{\"type\":\"object\",\"properties\":{\"city\":{\"type\":\"string\"}}}");
        EndpointSchema customer = schema("#/components/schemas/Customer",
                "{\"type\":\"object\",\"properties\":{\"addresses\":{\"type\":\"array\","
                        + "\"items\":{\"$ref\":\"#/components/schemas/Address\"}}}}");
        EndpointSchema request = schema("#/components/schemas/CreateRequest",
                "{\"allOf\":[{\"$ref\":\"#/components/schemas/Customer\"}],"
                        + "\"properties\":{\"billingAddress\":{\"$ref\":\"#/components/schemas/Address\"}}}");

        Object resolved = resolver.resolve(Arrays.asList(address, customer, request), request.getSchemaRef());

        assertTrue(resolved instanceof JSONObject);
        assertFalse(containsRef(resolved));
        JSONObject object = (JSONObject) resolved;
        assertEquals("string", object.getJSONObject("properties")
                .getJSONObject("billingAddress")
                .getJSONObject("properties")
                .getJSONObject("city")
                .getString("type"));
        assertEquals("string", object.getJSONArray("allOf").getJSONObject(0)
                .getJSONObject("properties")
                .getJSONObject("addresses")
                .getJSONObject("items")
                .getJSONObject("properties")
                .getJSONObject("city")
                .getString("type"));
    }

    @Test
    void rejectsCircularReferencesInsteadOfLoopingOrTruncating() {
        EndpointSchema first = schema("#/components/schemas/First",
                "{\"$ref\":\"#/components/schemas/Second\"}");
        EndpointSchema second = schema("#/components/schemas/Second",
                "{\"$ref\":\"#/components/schemas/First\"}");

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> resolver.resolve(Arrays.asList(first, second), first.getSchemaRef()));

        assertTrue(error.getMessage().contains("First"));
        assertTrue(error.getMessage().contains("Second"));
    }

    @Test
    void rejectsMissingReferences() {
        EndpointSchema schema = schema("#/definitions/Order",
                "{\"$ref\":\"#/definitions/Missing\"}");

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> resolver.resolve(Collections.singletonList(schema), schema.getSchemaRef()));

        assertTrue(error.getMessage().contains("#/definitions/Missing"));
    }

    private EndpointSchema schema(String ref, String json) {
        EndpointSchema schema = new EndpointSchema();
        schema.setSchemaRef(ref);
        schema.setSchemaJson(json);
        return schema;
    }

    private boolean containsRef(Object value) {
        if (value instanceof JSONObject) {
            JSONObject object = (JSONObject) value;
            if (object.containsKey("$ref")) {
                return true;
            }
            return object.values().stream().anyMatch(this::containsRef);
        }
        if (value instanceof Iterable) {
            for (Object item : (Iterable<?>) value) {
                if (containsRef(item)) {
                    return true;
                }
            }
        }
        return false;
    }
}
