package apiworkflow.swagger;

import apiworkflow.entity.EndpointDefinition;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SwaggerDocumentParserTest {

    private final SwaggerDocumentParser parser = new SwaggerDocumentParser();

    @Test
    void parsesOpenApi3DocumentAndResolvesLocalResponseReferences() throws IOException {
        ParsedSwaggerDocument document = parser.parse(
                read("examples/mock-commerce-openapi.json"), null, null
        );

        assertEquals("v3", document.getSwaggerVersion());
        assertEquals("http", document.getProtocol());
        assertEquals("localhost:9090", document.getHost());
        assertEquals("/api/v1", document.getBasePath());
        assertEquals(8, document.getEndpoints().size());

        EndpointDefinition createOrder = endpoint(document, "POST", "/orders");
        assertEquals("createOrder", createOrder.getOperationId());
        assertEquals("Orders", createOrder.getTags());
        assertEquals("application/json", createOrder.getConsumesTypes());
        assertEquals("application/json", createOrder.getProducesTypes());
        assertEquals("http://localhost:9090/api/v1/orders", createOrder.getFullUrl());
        assertEquals(Integer.valueOf(0), createOrder.getDeprecated());
        assertNotNull(createOrder.getDocChecksum());
        assertEquals(64, createOrder.getDocChecksum().length());

        EndpointDefinition login = endpoint(document, "POST", "/auth/login");
        assertEquals("application/json", login.getProducesTypes());
    }

    @Test
    void parsesSwagger2DocumentUsingRootContentTypes() throws IOException {
        ParsedSwaggerDocument document = parser.parse(read("test-swagger.json"), null, null);

        assertEquals("v2", document.getSwaggerVersion());
        assertEquals("http", document.getProtocol());
        assertEquals("api.example.com", document.getHost());
        assertEquals("/v1", document.getBasePath());
        assertEquals(9, document.getEndpoints().size());

        EndpointDefinition createUser = endpoint(document, "POST", "/users");
        assertEquals("application/json", createUser.getConsumesTypes());
        assertEquals("application/json", createUser.getProducesTypes());
    }

    @Test
    void appliesAbsoluteHostAndBasePathOverrides() throws IOException {
        ParsedSwaggerDocument document = parser.parse(
                read("examples/mock-commerce-openapi.json"),
                "https://mock.example.nz/ignored", "/sandbox/v2/"
        );

        EndpointDefinition health = endpoint(document, "GET", "/health");
        assertEquals("https", health.getProtocol());
        assertEquals("mock.example.nz", health.getHost());
        assertEquals("/sandbox/v2", health.getBasePath());
        assertEquals("https://mock.example.nz/sandbox/v2/health", health.getFullUrl());
    }

    @Test
    void treatsExplicitDeprecatedFalseAsNotDeprecated() {
        String json = "{\"openapi\":\"3.0.3\",\"paths\":{\"/items\":{\"get\":{"
                + "\"operationId\":\"listItems\",\"deprecated\":false,\"responses\":{\"200\":{\"description\":\"ok\"}}}}}}";

        ParsedSwaggerDocument document = parser.parse(json, null, null);

        assertEquals(Integer.valueOf(0), document.getEndpoints().get(0).getDeprecated());
    }

    @Test
    void rejectsDocumentsWithoutPaths() {
        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> parser.parse("{\"openapi\":\"3.0.3\"}", null, null)
        );

        assertFalse(error.getMessage().isEmpty());
    }

    private EndpointDefinition endpoint(ParsedSwaggerDocument document, String method, String path) {
        return document.getEndpoints().stream()
                .filter(item -> method.equals(item.getHttpMethod()) && path.equals(item.getEndpointPath()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Endpoint not found: " + method + " " + path));
    }

    private String read(String path) throws IOException {
        return new String(Files.readAllBytes(Paths.get(path)), StandardCharsets.UTF_8);
    }
}
