package apiworkflow.dto;

import lombok.Data;
import java.io.Serializable;
import java.util.Map;

@Data
public class RunFlowCustomReq implements Serializable {
    private String overrideBaseUrl;
    private Integer runType;
    private Long nodeId;
    private Integer endpointId;
    private Integer grpcEndpointId;
    private Map<String, Object> customParams;
}