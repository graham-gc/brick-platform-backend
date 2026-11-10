package apiworkflow.dto;

import lombok.Value;

import java.util.List;

@Value
public class SwaggerSyncPreviewReport {
    int checkedCount;
    List<SwaggerSyncImpact> impacts;
    List<UnassessedReference> unassessed;

    @Value
    public static class UnassessedReference {
        SwaggerSyncImpact reference;
        Reason reason;
        ContractVersion contractVersion;
    }

    public enum Reason {
        UNSUPPORTED_JSONPATH,
        OPERATION_MISSING,
        RESPONSE_MISSING,
        NO_DOCUMENTED_RESPONSE,
        SCHEMA_MISSING,
        UNRESOLVED_SCHEMA,
        COMPOSED_SCHEMA,
        UNSPECIFIED_SCHEMA_SHAPE,
        PATH_NOT_DECLARED
    }

    public enum ContractVersion {
        CURRENT, INCOMING
    }
}
