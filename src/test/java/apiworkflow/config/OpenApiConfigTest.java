package apiworkflow.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.Paths;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.oas.models.responses.ApiResponses;
import org.junit.jupiter.api.Test;
import org.springdoc.core.GroupedOpenApi;

import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OpenApiConfigTest {

    private final OpenApiConfig config = new OpenApiConfig();

    @Test
    void describesThePublicBrickApi() {
        OpenAPI openAPI = config.brickOpenApi("test-version");

        assertEquals("Brick Platform API", openAPI.getInfo().getTitle());
        assertEquals("test-version", openAPI.getInfo().getVersion());
        assertEquals("Graham Cheng", openAPI.getInfo().getContact().getName());
        assertEquals("https://github.com/graham-gc/brick-platform-backend",
                openAPI.getExternalDocs().getUrl());
    }

    @Test
    void limitsThePublishedGroupToBrickEndpoints() {
        GroupedOpenApi group = config.brickApiGroup();

        assertEquals("brick-platform", group.getGroup());
        assertTrue(group.getPathsToMatch().contains("/brick/**"));
    }

    @Test
    void groupsOperationsAndDocumentsRequestTracing() {
        Operation operation = new Operation().responses(
                new ApiResponses().addApiResponse("200", new ApiResponse().description("OK")));
        OpenAPI openAPI = new OpenAPI().paths(new Paths().addPathItem(
                "/brick/flows/1/run", new PathItem().post(operation)));

        config.documentationCustomiser().customise(openAPI);

        assertEquals(Collections.singletonList("Execution & Reports"), operation.getTags());
        assertEquals("X-Request-ID", operation.getParameters().get(0).getName());
        assertTrue(operation.getResponses().get("200").getHeaders().containsKey("X-Request-ID"));
    }
}
