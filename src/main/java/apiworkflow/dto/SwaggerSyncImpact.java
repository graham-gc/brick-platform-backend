package apiworkflow.dto;

import lombok.Value;

@Value
public class SwaggerSyncImpact {
    Integer flowId;
    String flowName;
    Long nodeId;
    String httpMethod;
    String endpointPath;
    String statusCode;
    String contentType;
    String fieldPath;
    ReferenceType referenceType;
    String variableName;
    Long assertionId;

    public enum ReferenceType {
        RESPONSE_VARIABLE, ASSERTION
    }
}
