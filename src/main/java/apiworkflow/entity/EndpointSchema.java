package apiworkflow.entity;

import lombok.Data;

import java.io.Serializable;
import java.util.Date;

@Data
public class EndpointSchema implements Serializable {
    private Long id;
    private Integer swaggerMappingId;
    private String schemaRef;
    private String schemaName;
    private String schemaJson;
    private Integer isDeleted;
    private String createBy;
    private Date createTime;
    private String updateBy;
    private Date updateTime;
}
