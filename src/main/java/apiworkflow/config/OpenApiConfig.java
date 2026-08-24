package apiworkflow.config;

import io.swagger.v3.oas.models.ExternalDocumentation;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.media.StringSchema;
import io.swagger.v3.oas.models.parameters.HeaderParameter;
import io.swagger.v3.oas.models.headers.Header;
import org.springdoc.core.GroupedOpenApi;
import org.springdoc.core.customizers.OpenApiCustomiser;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Collections;

@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI brickOpenApi(@Value("${info.app.version:1.0-SNAPSHOT}") String version) {
        return new OpenAPI()
                .info(new Info()
                        .title("Brick Platform API")
                        .version(version)
                        .description("API workflow automation, execution, assertion, and reporting service")
                        .contact(new Contact()
                                .name("Graham Cheng")
                                .url("https://github.com/graham-gc")))
                .externalDocs(new ExternalDocumentation()
                        .description("Brick Platform backend repository")
                        .url("https://github.com/graham-gc/brick-platform-backend"));
    }

    @Bean
    public GroupedOpenApi brickApiGroup() {
        return GroupedOpenApi.builder()
                .group("brick-platform")
                .pathsToMatch("/brick/**")
                .addOpenApiCustomiser(documentationCustomiser())
                .build();
    }

    OpenApiCustomiser documentationCustomiser() {
        return openApi -> {
            if (openApi.getPaths() == null) {
                return;
            }
            openApi.getPaths().forEach((path, pathItem) -> pathItem.readOperations()
                    .forEach(operation -> customiseOperation(path, operation)));
        };
    }

    private void customiseOperation(String path, Operation operation) {
        operation.setTags(Collections.singletonList(tagFor(path)));
        operation.addParametersItem(new HeaderParameter()
                .name("X-Request-ID")
                .description("Optional caller-provided correlation ID. Unsafe values are replaced.")
                .required(false)
                .schema(new StringSchema().maxLength(100)));

        if (operation.getResponses() != null) {
            operation.getResponses().values().forEach(response -> response.addHeaderObject(
                    "X-Request-ID",
                    new Header()
                            .description("Correlation ID used in server logs")
                            .schema(new StringSchema())));
        }
    }

    private String tagFor(String path) {
        if (path.startsWith("/brick/test-suites")) {
            return "Test Suites";
        }
        if (path.startsWith("/brick/global-variables")) {
            return "Global Variables";
        }
        if (path.startsWith("/brick/mappings")
                || path.startsWith("/brick/validate-and-parse")
                || path.startsWith("/brick/sync")) {
            return "Swagger Import";
        }
        if (path.startsWith("/brick/endpoints") || path.startsWith("/brick/schemas")) {
            return "Endpoint Definitions";
        }
        if (path.contains("/run")) {
            return "Execution & Reports";
        }
        if (path.startsWith("/brick/nodes")) {
            return "Assertions";
        }
        if (path.startsWith("/brick/flows") || path.startsWith("/brick/full/flows")) {
            return "Flow Designer";
        }
        return "Brick Platform";
    }
}
