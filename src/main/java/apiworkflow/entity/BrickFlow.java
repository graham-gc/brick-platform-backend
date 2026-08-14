package apiworkflow.entity;

import lombok.Data;
import java.io.Serializable;
import java.util.Date;

@Data
public class BrickFlow implements Serializable {
    private Integer id;
    private String name;
    private String env;
    private Integer swaggerMappingId;
    private String appConfigId;
    private Long tokenConfigId;
    private String description;
    private String status;
    private Integer version;
    private Integer flowTemplateId;
    private Integer isDeleted;
    private Integer source;
    private String sharedHeadersJson;
    private Double viewportX;
    private Double viewportY;
    private Double viewportZoom;
    private String createBy;
    private Date createTime;
    private String updateBy;
    private Date updateTime;
}