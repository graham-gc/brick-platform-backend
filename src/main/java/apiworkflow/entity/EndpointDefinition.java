package apiworkflow.entity;

import lombok.Data;
import java.io.Serializable;
import java.util.Date;

@Data
public class EndpointDefinition implements Serializable {
    private Integer id;
    private String env;
    private Integer swaggerMappingId;
    private String appConfigId;
    private String protocol;
    private String host;
    private String basePath;
    private String endpointPath;
    private String fullUrl;
    private String httpMethod;
    private String operationId;
    private String summary;
    private String description;
    private String tags;
    private Integer deprecated;
    private String swaggerVersion;
    private String consumesTypes;
    private String producesTypes;
    private String requestDefinitionJson;
    private String swaggerUrl;
    private String docChecksum;
    private Integer isLightweight;
    private Integer isDeleted;
    private String createBy;
    private Date createTime;
    private String updateBy;
    private Date updateTime;
}
