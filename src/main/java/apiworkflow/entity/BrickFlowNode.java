package apiworkflow.entity;

import lombok.Data;
import java.io.Serializable;
import java.util.Date;

@Data
public class BrickFlowNode implements Serializable {
    private Long id;
    private Integer flowId;
    private Integer endpointId;
    private Integer timeoutSec;
    private Integer retries;
    private String headersJson;
    private String payloadJson;
    private String queryParamsJson;
    private String pathVarsJson;
    private Integer conditionGroupId;
    private Long tokenConfigId;
    private Long signConfigId;
    private Integer signEnabled;
    private String nodeType;
    private Integer grpcEndpointId;
    private String grpcDiscoveryConfig;
    private Double x;
    private Double y;
    private Integer isDeleted;
    private String endpointAppConfigId;
    private String createBy;
    private Date createTime;
    private String updateBy;
    private Date updateTime;
}