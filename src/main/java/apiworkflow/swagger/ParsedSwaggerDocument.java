package apiworkflow.swagger;

import apiworkflow.entity.EndpointDefinition;
import apiworkflow.entity.EndpointSchema;

import java.util.Collections;
import java.util.List;

public class ParsedSwaggerDocument {

    private final String swaggerVersion;
    private final String protocol;
    private final String host;
    private final String basePath;
    private final List<EndpointDefinition> endpoints;
    private final List<EndpointSchema> schemas;

    public ParsedSwaggerDocument(String swaggerVersion, String protocol, String host,
                                 String basePath, List<EndpointDefinition> endpoints,
                                 List<EndpointSchema> schemas) {
        this.swaggerVersion = swaggerVersion;
        this.protocol = protocol;
        this.host = host;
        this.basePath = basePath;
        this.endpoints = Collections.unmodifiableList(endpoints);
        this.schemas = Collections.unmodifiableList(schemas);
    }

    public String getSwaggerVersion() {
        return swaggerVersion;
    }

    public String getProtocol() {
        return protocol;
    }

    public String getHost() {
        return host;
    }

    public String getBasePath() {
        return basePath;
    }

    public List<EndpointDefinition> getEndpoints() {
        return endpoints;
    }

    public List<EndpointSchema> getSchemas() {
        return schemas;
    }
}
