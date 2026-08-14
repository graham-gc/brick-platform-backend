package apiworkflow.dto;

import lombok.Data;
import java.io.Serializable;

@Data
public class SyncRequest implements Serializable {
    private String env;
    private String appRealName;
    private String versionTag;
    private String swaggerUrl;
    private String swaggerContent;
    private String operator;
    private String selectedEndpoint;
    private String customHost;
    private String customBasePath;
    private PreviewStats previewStats;

    @Data
    public static class PreviewStats {
        private Integer interfacesBefore;
        private Integer interfacesAdded;
        private Integer interfacesUpdated;
        private Integer interfacesDeleted;
    }
}