package apiworkflow.entity;

import lombok.Data;
import java.io.Serializable;
import java.util.Date;

@Data
public class BrickGlobalVariable implements Serializable {
    private Long id;
    private String name;
    private String type;
    private String description;
    private String config;
    private Integer isEnabled;
    private String category;
    private String syntax;
    private Integer hasParams;
    private String example;
    private String sampleResult;
    private String paramSchema;
    private String dataType;
    private String createBy;
    private Date createTime;
    private String updateBy;
    private Date updateTime;
}