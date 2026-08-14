package apiworkflow.entity;

import lombok.Data;
import java.io.Serializable;
import java.util.Date;

@Data
public class BrickTestSuite implements Serializable {
    private Integer id;
    private String name;
    private String env;
    private Integer swaggerMappingId;
    private String appConfigId;
    private String description;
    private Integer isDeleted;
    private String createBy;
    private Date createTime;
    private String updateBy;
    private Date updateTime;
}